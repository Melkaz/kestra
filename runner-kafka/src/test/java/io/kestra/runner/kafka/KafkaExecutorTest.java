package io.kestra.runner.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.kestra.core.exceptions.InternalException;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.executions.ExecutionKilled;
import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.*;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.tasks.flows.Parallel;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.runner.kafka.configs.ClientConfig;
import io.kestra.runner.kafka.configs.StreamDefaultsConfig;
import io.kestra.runner.kafka.executors.*;
import io.kestra.runner.kafka.serializers.JsonSerde;
import io.kestra.runner.kafka.services.KafkaAdminService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@SuppressWarnings("resource")
@MicronautTest
@Slf4j
@Property(name = "kestra.server-type", value = "EXECUTOR")
class KafkaExecutorTest {
    @Inject
    ApplicationContext applicationContext;

    @Inject
    ExecutorMain executorMain;

    @Inject
    ExecutorStore executorStore;

    @Inject
    ExecutorFlowTrigger executorFlowTrigger;

    @Inject
    ExecutorWorkerRunning executorWorkerRunning;

    @Inject
    ClientConfig clientConfig;

    @Inject
    StreamDefaultsConfig streamConfig;

    @Inject
    KafkaAdminService kafkaAdminService;

    @Inject
    LocalFlowRepositoryLoader repositoryLoader;

    @Inject
    FlowRepositoryInterface flowRepository;

    @Inject
    KafkaTemplateExecutor kafkaTemplateExecutor;

    TopologyTestDriver testTopology;

    @BeforeEach
    void init() throws IOException, URISyntaxException {
        kafkaTemplateExecutor.setTemplates(List.of());
        TestsUtils.loads(repositoryLoader);
    }

    void startStream(KafkaExecutorInterface kafkaExecutorInterface) {
        Properties properties = new Properties();
        properties.putAll(clientConfig.getProperties());
        properties.putAll(streamConfig.getProperties());
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "unit-test");
        properties.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-stream-unit/" + UUID.randomUUID());

        // @TODO
        properties.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);

        Topology topology = kafkaExecutorInterface.topology().build();

        if (log.isTraceEnabled()) {
            log.trace(topology.describe().toString());
        }

        if (testTopology != null) {
            testTopology.close();
        }

        testTopology = new TopologyTestDriver(topology, properties);
    }

    @AfterEach
    void tear() {
        if (this.testTopology != null) {
            testTopology.close();
        }
    }

    @Test
    void standard() {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "logs").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        Execution execution = createExecution(flow);

        // task
        execution = runningAndSuccessSequential(flow, execution, 0);
        execution = runningAndSuccessSequential(flow, execution, 1);
        execution = runningAndSuccessSequential(flow, execution, 2);

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));

        // running most be deleted at the end
        assertThat(workerTaskRunningOutput().readRecord().value(), is(nullValue()));
        assertThat(workerTaskRunningOutput().readRecord().value(), is(nullValue()));
        assertThat(workerTaskRunningOutput().readRecord().value(), is(nullValue()));
        assertThat(workerTaskRunningOutput().isEmpty(), is(true));

        // executor topic must be deleted
        TestRecord<String, Executor> executor = executorOutput().readRecord();
        assertThat(executor.value(), is(nullValue()));
        assertThat(executorOutput().isEmpty(), is(true));
    }

    @Test
    void concurrent() {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "logs").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        Execution execution = createExecution(flow);

        // task
        execution = runningAndSuccessSequential(flow, execution, 0);
        execution = runningAndSuccessSequential(flow, execution, 1);

        // next
        // TestRecord<String, Execution> executionRecord = executionOutput().readRecord();
        assertThat(execution.getTaskRunList(), hasSize(3));
        assertThat(execution.getTaskRunList().get(2).getState().getCurrent(), is(State.Type.CREATED));

        Task task = flow.getTasks().get(2);
        TaskRun taskRun = execution.getTaskRunList().get(2);

        // concurrent
        this.changeStatus(task, taskRun, State.Type.RUNNING);
        this.changeStatus(task, taskRun, State.Type.SUCCESS);

        execution = executionOutput().readRecord().getValue();
        execution = executionOutput().readRecord().getValue();

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }

    @Test
    void killed() {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "logs").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        Execution execution = createExecution(flow);

        // task
        execution = runningAndSuccessSequential(flow, execution, 0);

        // running first
        Task task = flow.getTasks().get(1);
        TaskRun taskRun = execution.getTaskRunList().get(1);
        this.changeStatus(task, taskRun, State.Type.RUNNING);


        // multiple killed should have no impact
        createKilled(execution);
        createKilled(execution);
        createKilled(execution);

        // next
        execution = executionOutput().readValue();
        execution = executionOutput().readValue();
        assertThat(execution.getTaskRunList(), hasSize(2));
        assertThat(execution.getState().getCurrent(), is(State.Type.KILLING));

        // late arrival from worker
        this.changeStatus(task, taskRun, State.Type.SUCCESS);

        execution = executionOutput().readValue();

        assertThat(execution.getTaskRunList(), hasSize(2));
        assertThat(execution.getTaskRunList().get(1).getState().getCurrent(), is(State.Type.SUCCESS));

        assertThat(execution.getState().getCurrent(), is(State.Type.KILLED));
        assertThat(executionOutput().isEmpty(), is(true));
    }

    @Test
    void killedAlreadyFinished() {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "logs").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        Execution execution = createExecution(flow);

        // task
        execution = runningAndSuccessSequential(flow, execution, 0);
        execution = runningAndSuccessSequential(flow, execution, 1);
        execution = runningAndSuccessSequential(flow, execution, 2);

        createKilled(execution);

        // next
        assertThat(executionOutput().isEmpty(), is(true));
        assertThat(execution.getTaskRunList(), hasSize(3));
        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void killedParallel(boolean killed) {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "parallel").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        createExecution(flow);
        Parallel parent = (Parallel) flow.getTasks().get(0);
        Task firstChild = parent.getTasks().get(0);
        Task secondChild = parent.getTasks().get(1);

        // parent > worker > RUNNING
        Execution executionRecord = executionOutput().readRecord().value();
        assertThat(executionRecord.getTaskRunList(), hasSize(1));
        this.changeStatus(parent, executionRecord.getTaskRunList().get(0), State.Type.RUNNING);

        // parent > execution RUNNING
        executionRecord = executionOutput().readRecord().value();
        assertThat(executionRecord.getTaskRunList(), hasSize(7));
        assertThat(executionRecord.getTaskRunList().get(0).getState().getCurrent(), is(State.Type.RUNNING));
        assertThat(executionRecord.getTaskRunList().get(1).getState().getCurrent(), is(State.Type.CREATED));

        // first child > RUNNING
        this.changeStatus(firstChild, executionRecord.getTaskRunList().get(1), State.Type.RUNNING);
        executionRecord = executionOutput().readRecord().value();
        assertThat(executionRecord.getTaskRunList().get(1).getState().getCurrent(), is(State.Type.RUNNING));
        assertThat(executionRecord.getTaskRunList().get(2).getState().getCurrent(), is(State.Type.CREATED));

        // killed execution
        createKilled(executionRecord);
        executionRecord = executionOutput().readRecord().value();
        executionRecord = executionOutput().readRecord().value();
        assertThat(executionRecord.getState().getCurrent(), is(State.Type.KILLING));


        // killed all the creation and killing the parent
        for (int i = 0; i < 5; i++) {
            executionRecord = executionOutput().readRecord().value();
        }

        assertThat(executionRecord.getTaskRunList().get(0).getState().getCurrent(), is(State.Type.KILLING));

        for (int i = 2; i < 5; i++) {
            assertThat(executionRecord.getTaskRunList().get(i).getState().getCurrent(), is(State.Type.KILLED));
        }

        // kill the first child
        if (killed) {
            this.changeStatus(firstChild, executionRecord.getTaskRunList().get(1), State.Type.KILLED);
        } else {
            this.changeStatus(firstChild, executionRecord.getTaskRunList().get(1), State.Type.SUCCESS);
        }

        // killing state
        for (int i = 0; i < 2; i++) {
            executionRecord = executionOutput().readRecord().value();
        }

        // control
        assertThat(executionRecord.getTaskRunList().get(0).getState().getCurrent(), is(State.Type.KILLED));
        assertThat(executionRecord.getTaskRunList().get(1).getState().getCurrent(), is(killed ? State.Type.KILLED : State.Type.SUCCESS));
        assertThat(executionRecord.getState().getCurrent(), is(State.Type.KILLED));

        assertThat(executionOutput().isEmpty(), is(true));
    }

    @Test
    void eachNull() {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "each-null").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        Execution execution = createExecution(flow);
        execution = executionOutput().readRecord().value();
        execution = executionOutput().readRecord().value();
        execution = executionOutput().readRecord().value();

        assertThat(executionOutput().isEmpty(), is(true));
        assertThat(execution.getTaskRunList(), hasSize(1));
        assertThat(execution.getState().getCurrent(), is(State.Type.FAILED));
    }

    @Test
    void parallel() {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "parallel").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        createExecution(flow);
        Parallel parent = (Parallel) flow.getTasks().get(0);
        Task last = flow.getTasks().get(1);

        // parent > worker > RUNNING
        TestRecord<String, Execution> executionRecord = executionOutput().readRecord();
        assertThat(executionRecord.value().getTaskRunList(), hasSize(1));
        this.changeStatus(parent, executionRecord.value().getTaskRunList().get(0), State.Type.RUNNING);

        // parent > execution RUNNING and all created
        executionRecord = executionOutput().readRecord();
        assertThat(executionRecord.value().getTaskRunList(), hasSize(7));
        assertThat(executionRecord.value().getTaskRunList().stream().filter(r -> r.getState().getCurrent() == State.Type.CREATED).count(), is(6L));

        // Task > all RUNNING
        for (ListIterator<Task> it = parent.getTasks().listIterator(); it.hasNext(); ) {
            int index = it.nextIndex();
            Task next = it.next();

            // Task > CREATED
            assertThat(executionRecord.value().getTaskRunList().get(index + 1).getState().getCurrent(), is(State.Type.CREATED));

            // Task > RUNNING
            this.changeStatus(next, executionRecord.value().getTaskRunList().get(index + 1), State.Type.RUNNING);

            executionRecord = executionOutput().readRecord();
            assertThat(executionRecord.value().getTaskRunList().get(index + 1).getState().getCurrent(), is(State.Type.RUNNING));
        }

        // Task > all SUCCESS
        for (ListIterator<Task> it = parent.getTasks().listIterator(); it.hasNext(); ) {
            int index = it.nextIndex();
            Task next = it.next();

            this.changeStatus(next, executionRecord.value().getTaskRunList().get(index + 1), State.Type.SUCCESS);
            executionRecord = executionOutput().readRecord();
            assertThat(executionRecord.value().getTaskRunList().get(index + 1).getState().getCurrent(), is(State.Type.SUCCESS));
        }

        // parent terminated
        executionRecord = executionOutput().readRecord();
        assertThat(executionRecord.value().getTaskRunList().get(0).getState().getCurrent(), is(State.Type.SUCCESS));

        // last
        this.changeStatus(last, executionRecord.value().getTaskRunList().get(7), State.Type.RUNNING);

        this.changeStatus(last, executionRecord.value().getTaskRunList().get(7), State.Type.RUNNING);
        executionRecord = executionOutput().readRecord();
        assertThat(executionRecord.value().getTaskRunList().get(7).getState().getCurrent(), is(State.Type.RUNNING));

        this.changeStatus(last, executionRecord.value().getTaskRunList().get(7), State.Type.SUCCESS);
        executionRecord = executionOutput().readRecord();
        assertThat(executionRecord.value().getTaskRunList().get(7).getState().getCurrent(), is(State.Type.SUCCESS));

        // ok
        assertThat(executionRecord.value().getState().getCurrent(), is(State.Type.SUCCESS));
    }

    @Test
    void eachParallelNested() throws InternalException {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "each-parallel-nested").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        Execution execution = createExecution(flow);

        for (int i = 0; i < 5; i++) {
            execution = executionOutput().readRecord().value();
        }

        for (int i = 0; i <= 3; i++) {
            assertThat(execution.getTaskRunList().get(i).getState().getCurrent(), is(State.Type.RUNNING));
        }

        for (int i = 4; i <= 6; i++) {
            assertThat(execution.getTaskRunList().get(i).getState().getCurrent(), is(State.Type.CREATED));

            this.changeStatus(flow.findTaskByTaskId(execution.getTaskRunList().get(i).getTaskId()), execution.getTaskRunList().get(i), State.Type.RUNNING);
            this.changeStatus(flow.findTaskByTaskId(execution.getTaskRunList().get(i).getTaskId()), execution.getTaskRunList().get(i), State.Type.SUCCESS);
        }

        for (int i = 0; i < 6; i++) {
            execution = executionOutput().readRecord().value();
        }

        for (int i = 4; i <= 6; i++) {
            assertThat(execution.getTaskRunList().get(i).getState().getCurrent(), is(State.Type.SUCCESS));
        }

        for (int i = 7; i <= 9; i++) {
            assertThat(execution.getTaskRunList().get(i).getState().getCurrent(), is(State.Type.CREATED));

            this.changeStatus(flow.findTaskByTaskId(execution.getTaskRunList().get(i).getTaskId()), execution.getTaskRunList().get(i), State.Type.RUNNING);
            this.changeStatus(flow.findTaskByTaskId(execution.getTaskRunList().get(i).getTaskId()), execution.getTaskRunList().get(i), State.Type.SUCCESS);
        }

        for (int i = 0; i < 10; i++) {
            execution = executionOutput().readRecord().value();
        }

        for (int i = 7; i <= 9; i++) {
            assertThat(execution.getTaskRunList().get(i).getState().getCurrent(), is(State.Type.SUCCESS));
        }

        assertThat(execution.getTaskRunList().get(10).getState().getCurrent(), is(State.Type.CREATED));
        this.changeStatus(flow.findTaskByTaskId(execution.getTaskRunList().get(10).getTaskId()), execution.getTaskRunList().get(10), State.Type.RUNNING);
        this.changeStatus(flow.findTaskByTaskId(execution.getTaskRunList().get(10).getTaskId()), execution.getTaskRunList().get(10), State.Type.SUCCESS);

        for (int i = 0; i < 2; i++) {
            execution = executionOutput().readRecord().value();
        }

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }

    @Test
    void workerSuccess() throws InternalException {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "worker").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        io.kestra.core.tasks.flows.Worker worker = (io.kestra.core.tasks.flows.Worker) flow.findTaskByTaskId("worker");
        Task first = flow.findTaskByTaskId("first");
        Task second = flow.findTaskByTaskId("second");

        startStream(this.executorMain);

        Execution execution = createExecution(flow);

        for (int i = 0; i < 2; i++) {
            execution = executionOutput().readRecord().value();
        }

        assertThat(execution.getTaskRunList().get(0).getState().getCurrent(), is(State.Type.RUNNING));

        for (Task task : List.of(first, second)) {
            WorkerTask workerTask = worker.workerTask(execution.getTaskRunList().get(0), task, new RunContext());
            TaskRun taskRun = workerTask.getTaskRun();

            taskRun = taskRun.withState(State.Type.RUNNING);
            this.workerTaskResultInput().pipeInput(new WorkerTaskResult(workerTask.withTaskRun(taskRun)));

            taskRun = taskRun.withState(State.Type.SUCCESS);
            this.workerTaskResultInput().pipeInput(new WorkerTaskResult(workerTask.withTaskRun(taskRun)));
        }

        for (int i = 0; i < 5; i++) {
            execution = executionOutput().readRecord().value();
        }

        assertThat(execution.getTaskRunList().get(1).getState().getCurrent(), is(State.Type.SUCCESS));
        assertThat(execution.getTaskRunList().get(2).getState().getCurrent(), is(State.Type.SUCCESS));
        assertThat(execution.getTaskRunList().get(3).getState().getCurrent(), is(State.Type.CREATED));

        this.changeStatus(flow.findTaskByTaskId(execution.getTaskRunList().get(3).getTaskId()), execution.getTaskRunList().get(3), State.Type.RUNNING);
        this.changeStatus(flow.findTaskByTaskId(execution.getTaskRunList().get(3).getTaskId()), execution.getTaskRunList().get(3), State.Type.SUCCESS);

        for (int i = 0; i < 2; i++) {
            execution = executionOutput().readRecord().value();
        }

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));
    }

    @Test
    void flowTrigger() {
        startStream(this.executorStore);

        Flow triggerFlow = flowRepository.findById("io.kestra.tests", "trigger-flow-listener-no-inputs").orElseThrow();
        this.flowInput().pipeInput(triggerFlow.uid(), triggerFlow);

        // we add 2 version of the same triggering flow to be sure to have only the last one triggered
        Flow updateTriggerFlow = triggerFlow.withRevision(2);
        this.flowInput().pipeInput(updateTriggerFlow.uid(), updateTriggerFlow.withRevision(2));

        Flow firstFlow = flowRepository.findById("io.kestra.tests", "trigger-flow").orElseThrow();
        this.flowInput().pipeInput(firstFlow.uid(), firstFlow);

        startStream(this.executorMain);

        Execution firstExecution = createExecution(firstFlow);

        // task
        firstExecution = runningAndSuccessSequential(firstFlow, firstExecution, 0);
        assertThat(firstExecution.getState().getCurrent(), is(State.Type.SUCCESS));

        io.kestra.runner.kafka.streams.ExecutorFlowTrigger executorFlowTrigger = executorFlowTriggerOutput().readRecord().value();
        assertThat(executorFlowTrigger.getFlowHavingTrigger().getId(), is("trigger-flow-listener-no-inputs"));

        assertThat(executorFlowTriggerOutput().isEmpty(), is(true));

        startStream(this.executorFlowTrigger);

        executorFlowTriggerInput().pipeInput(executorFlowTrigger.getFlowHavingTrigger().uid(), executorFlowTrigger);

        Execution triggerExecution = executionOutput().readRecord().getValue();
        assertThat(triggerExecution.getState().getCurrent(), is(State.Type.CREATED));
        assertThat(triggerExecution.getFlowId(), is("trigger-flow-listener-no-inputs"));

        assertThat(executionOutput().isEmpty(), is(true));
    }

    @Test
    void multipleTrigger() {
        startStream(this.executorStore);

        Flow flowA = flowRepository.findById("io.kestra.tests", "trigger-multiplecondition-flow-a").orElseThrow();
        this.flowInput().pipeInput(flowA.uid(), flowA);

        Flow flowB = flowRepository.findById("io.kestra.tests", "trigger-multiplecondition-flow-b").orElseThrow();
        this.flowInput().pipeInput(flowB.uid(), flowB);

        Flow triggerFlow = flowRepository.findById("io.kestra.tests", "trigger-multiplecondition-listener").orElseThrow();
        this.flowInput().pipeInput(triggerFlow.uid(), triggerFlow);

        this.flowInput().pipeInput(flowA.uid(), flowA);
        this.flowInput().pipeInput(flowB.uid(), flowB);
        this.flowInput().pipeInput(triggerFlow.uid(), triggerFlow);

        startStream(this.executorMain);

        // first
        Execution executionA = createExecution(flowA);
        executionA = runningAndSuccessSequential(flowA, executionA, 0);
        assertThat(executionA.getState().getCurrent(), is(State.Type.SUCCESS));

        // second
        Execution executionB = createExecution(flowB);
        executionB = runningAndSuccessSequential(flowB, executionB, 0);
        assertThat(executionB.getState().getCurrent(), is(State.Type.SUCCESS));

        // get trigger
        io.kestra.runner.kafka.streams.ExecutorFlowTrigger executorFlowTriggerA = executorFlowTriggerOutput().readRecord().value();
        assertThat(executorFlowTriggerA.getExecution().getFlowId(), is("trigger-multiplecondition-flow-a"));
        io.kestra.runner.kafka.streams.ExecutorFlowTrigger executorFlowTriggerB = executorFlowTriggerOutput().readRecord().value();
        assertThat(executorFlowTriggerB.getExecution().getFlowId(), is("trigger-multiplecondition-flow-b"));

        startStream(this.executorFlowTrigger);

        executorFlowTriggerInput().pipeInput(executorFlowTriggerA.getFlowHavingTrigger().uid(), executorFlowTriggerA);
        executorFlowTriggerInput().pipeInput(executorFlowTriggerB.getFlowHavingTrigger().uid(), executorFlowTriggerB);

        // trigger start
        Execution triggerExecution = executionOutput().readRecord().getValue();
        assertThat(triggerExecution.getState().getCurrent(), is(State.Type.CREATED));
        assertThat(triggerExecution.getFlowId(), is("trigger-multiplecondition-listener"));
    }

    @Test
    void paused() {
        startStream(this.executorStore);

        Flow flow = flowRepository.findById("io.kestra.tests", "pause-delay").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);


        startStream(this.executorMain);

        Execution execution = createExecution(flow);
        assertThat(execution.getState().getCurrent(), is(State.Type.CREATED));

        execution = executionOutput().readRecord().getValue();
        execution = executionOutput().readRecord().getValue();
        execution = executionOutput().readRecord().getValue();

        assertThat(execution.getState().getCurrent(), is(State.Type.PAUSED));
        this.testTopology.advanceWallClockTime(Duration.ofSeconds(10));

        execution = executionOutput().readRecord().getValue();
        assertThat(execution.getState().getCurrent(), is(State.Type.RESTARTED));

        execution = executionOutput().readRecord().getValue();
        assertThat(execution.getState().getCurrent(), is(State.Type.RUNNING));
    }

    @Test
    void workerRebalanced() {
        startStream(this.executorStore);

        // start an execution to generate some id
        Flow flow = flowRepository.findById("io.kestra.tests", "logs").orElseThrow();
        this.flowInput().pipeInput(flow.uid(), flow);

        startStream(this.executorMain);

        Execution execution = createExecution(flow);

        execution = runningAndSuccessSequential(flow, execution, 0, State.Type.RUNNING);
        String taskRunId = execution.getTaskRunList().get(0).getId();

        assertThat(execution.getTaskRunList().get(0).getState().getCurrent(), is(State.Type.RUNNING));

        WorkerTask workerTask = this.workerTaskOutput().readRecord().value();
        assertThat(workerTask.getTaskRun().getState().getCurrent(), is(State.Type.CREATED));

        // start worker running stream
        startStream(this.executorWorkerRunning);

        WorkerInstance startInstance = workerInstance();
        this.workerInstanceInput().pipeInput(startInstance.getWorkerUuid().toString(), startInstance);

        // add a running
        WorkerTaskRunning running = WorkerTaskRunning.of(workerTask, startInstance, 0);
        this.workerTaskRunningInput().pipeInput(running.getTaskRun().getId(), running);

        // declare a new worker instance
        WorkerInstance newInstance = workerInstance();
        this.workerInstanceInput().pipeInput(newInstance.getWorkerUuid().toString(), newInstance);

        // receive a new WorkTask meaning that the resend is done
        TestRecord<String, WorkerTask> workerTaskRecord = this.workerTaskOutput().readRecord();
        assertThat(workerTaskRecord.value().getTaskRun().getState().getCurrent(), is(State.Type.CREATED));
        assertThat(workerTaskRecord.value().getTaskRun().getId(), is(taskRunId));
        assertThat(workerTaskRecord.value().getTaskRun().getAttempts().size(), is(1));
        assertThat(workerTaskRecord.value().getTaskRun().getAttempts().get(0).getState().getCurrent(), is(State.Type.KILLED));

        // running is deleted
        TestRecord<String, WorkerTaskRunning> workerTaskRunningRecord = workerTaskRunningOutput().readRecord();
        assertThat(workerTaskRunningRecord.value(), is(nullValue()));
        assertThat(workerTaskRunningRecord.key(), is(taskRunId));
    }

    @Test
    void invalidStore() throws JsonProcessingException {
        startStream(this.executorStore);

        KeyValueStore<String, String> flow = this.testTopology.getKeyValueStore("flow");
        flow.put("io.kestra.unittest_invalid_1", JacksonMapper.ofJson().writeValueAsString(Map.of(
            "id", "invalid",
            "namespace", "io.kestra.unittest",
            "revision", 1,
            "tasks", List.of(
                Map.of(
                    "id", "invalid",
                    "type", "io.kestra.core.tasks.debugs.Echo",
                    "level", "invalid"
                )
            )
        )));

        Flow triggerFlow = flowRepository.findById("io.kestra.tests", "trigger-flow-listener-no-inputs").orElseThrow();
        this.flowInput().pipeInput(triggerFlow.uid(), triggerFlow);

        Flow firstFlow = flowRepository.findById("io.kestra.tests", "trigger-flow").orElseThrow();
        this.flowInput().pipeInput(firstFlow.uid(), firstFlow);

        startStream(this.executorMain);

        Execution firstExecution = createExecution(firstFlow);

        // task
        firstExecution = runningAndSuccessSequential(firstFlow, firstExecution, 0);
        assertThat(firstExecution.getState().getCurrent(), is(State.Type.SUCCESS));

        io.kestra.runner.kafka.streams.ExecutorFlowTrigger executorFlowTrigger = executorFlowTriggerOutput().readRecord().value();
        assertThat(executorFlowTrigger.getFlowHavingTrigger().getId(), is("trigger-flow-listener-no-inputs"));
    }

    private Execution createExecution(Flow flow) {
        Execution execution = Execution.builder()
            .id(IdUtils.create())
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .flowRevision(flow.getRevision())
            .state(new State())
            .build();

        this.executionInput().pipeInput(execution.getId(), execution);

        return execution;
    }

    private void createKilled(Execution execution) {
        ExecutionKilled executionKilled = ExecutionKilled.builder()
            .executionId(execution.getId())
            .build();

        this.executionKilledInput().pipeInput(executionKilled.getExecutionId(), executionKilled);
    }

    private static WorkerInstance workerInstance() {
        return WorkerInstance
            .builder()
            .partitions(Collections.singletonList(0))
            .workerUuid(UUID.randomUUID())
            .hostname("unit-test")
            .build();
    }

    private Execution runningAndSuccessSequential(Flow flow, Execution execution, int index) {
        return runningAndSuccessSequential(flow, execution, index, State.Type.SUCCESS);
    }

    private Execution runningAndSuccessSequential(Flow flow, Execution execution, int index, State.Type lastState) {
        Task task = flow.getTasks().get(index);

        // taskRun is created by a previous tasks, no need to fetch created
        if (execution.getTaskRunList() == null || execution.getTaskRunList().size() != index + 1) {
            execution = executionOutput().readRecord().getValue();

            // CREATED
            assertThat(execution.getTaskRunList(), hasSize(index + 1));
            assertThat(execution.getTaskRunList().get(index).getState().getCurrent(), is(State.Type.CREATED));
        }

        if (lastState == State.Type.CREATED) {
            return execution;
        }

        // RUNNING
        this.changeStatus(task, execution.getTaskRunList().get(index), State.Type.RUNNING);

        execution = executionOutput().readRecord().value();
        assertThat(execution.getTaskRunList(), hasSize(index + 1));
        assertThat(execution.getTaskRunList().get(index).getState().getCurrent(), is(State.Type.RUNNING));

        if (lastState == State.Type.RUNNING) {
            return execution;
        }

        // SUCCESS
        this.changeStatus(task, execution.getTaskRunList().get(index), State.Type.SUCCESS);

        execution = executionOutput().readRecord().getValue();
        assertThat(execution.getTaskRunList().get(index).getState().getCurrent(), is(State.Type.SUCCESS));

        return execution;
    }

    private void changeStatus(Task task, TaskRun taskRun, State.Type state) {
        this.workerTaskResultInput()
            .pipeInput("unittest", WorkerTaskResult.builder()
                .taskRun(taskRun.withState(state))
                .build()
            );

    }

    private TestInputTopic<String, Flow> flowInput() {
        return this.testTopology
            .createInputTopic(
                kafkaAdminService.getTopicName(Flow.class),
                Serdes.String().serializer(),
                JsonSerde.of(Flow.class).serializer()
            );
    }

    private TestInputTopic<String, String> flowRawInput() {
        return this.testTopology
            .createInputTopic(
                kafkaAdminService.getTopicName(Flow.class),
                Serdes.String().serializer(),
                new StringSerializer()
            );
    }

    private TestInputTopic<String, Execution> executionInput() {
        return this.testTopology
            .createInputTopic(
                kafkaAdminService.getTopicName(Execution.class),
                Serdes.String().serializer(),
                JsonSerde.of(Execution.class).serializer()
            );
    }

    private TestInputTopic<String, io.kestra.runner.kafka.streams.ExecutorFlowTrigger> executorFlowTriggerInput() {
        return this.testTopology
            .createInputTopic(
                kafkaAdminService.getTopicName(io.kestra.runner.kafka.streams.ExecutorFlowTrigger.class),
                Serdes.String().serializer(),
                JsonSerde.of(io.kestra.runner.kafka.streams.ExecutorFlowTrigger.class).serializer()
            );
    }


    private TestOutputTopic<String, io.kestra.runner.kafka.streams.ExecutorFlowTrigger> executorFlowTriggerOutput() {
        return this.testTopology
            .createOutputTopic(
                kafkaAdminService.getTopicName(io.kestra.runner.kafka.streams.ExecutorFlowTrigger.class),
                Serdes.String().deserializer(),
                JsonSerde.of(io.kestra.runner.kafka.streams.ExecutorFlowTrigger.class).deserializer()
            );
    }

    private TestInputTopic<String, ExecutionKilled> executionKilledInput() {
        return this.testTopology
            .createInputTopic(
                kafkaAdminService.getTopicName(ExecutionKilled.class),
                Serdes.String().serializer(),
                JsonSerde.of(ExecutionKilled.class).serializer()
            );
    }

    private TestInputTopic<String, WorkerTaskResult> workerTaskResultInput() {
        return this.testTopology
            .createInputTopic(
                kafkaAdminService.getTopicName(WorkerTaskResult.class),
                Serdes.String().serializer(),
                JsonSerde.of(WorkerTaskResult.class).serializer()
            );
    }

    private TestInputTopic<String, WorkerTaskRunning> workerTaskRunningInput() {
        return this.testTopology
            .createInputTopic(
                kafkaAdminService.getTopicName(WorkerTaskRunning.class),
                Serdes.String().serializer(),
                JsonSerde.of(WorkerTaskRunning.class).serializer()
            );
    }

    private TestInputTopic<String, WorkerInstance> workerInstanceInput() {
        return this.testTopology
            .createInputTopic(
                kafkaAdminService.getTopicName(WorkerInstance.class),
                Serdes.String().serializer(),
                JsonSerde.of(WorkerInstance.class).serializer()
            );
    }

    private TestOutputTopic<String, Execution> executionOutput() {
        return this.testTopology
            .createOutputTopic(
                kafkaAdminService.getTopicName(Execution.class),
                Serdes.String().deserializer(),
                JsonSerde.of(Execution.class).deserializer()
            );
    }

    private TestOutputTopic<String, Executor> executorOutput() {
        return this.testTopology
            .createOutputTopic(
                kafkaAdminService.getTopicName(Executor.class),
                Serdes.String().deserializer(),
                JsonSerde.of(Executor.class).deserializer()
            );
    }

    private TestOutputTopic<String, WorkerTaskResult> workerTaskResultOutput() {
        return this.testTopology
            .createOutputTopic(
                kafkaAdminService.getTopicName(WorkerTaskResult.class),
                Serdes.String().deserializer(),
                JsonSerde.of(WorkerTaskResult.class).deserializer()
            );
    }

    private TestOutputTopic<String, WorkerTaskRunning> workerTaskRunningOutput() {
        return this.testTopology
            .createOutputTopic(
                kafkaAdminService.getTopicName(WorkerTaskRunning.class),
                Serdes.String().deserializer(),
                JsonSerde.of(WorkerTaskRunning.class).deserializer()
            );
    }

    private TestOutputTopic<String, WorkerTask> workerTaskOutput() {
        return this.testTopology
            .createOutputTopic(
                kafkaAdminService.getTopicName(WorkerTask.class),
                Serdes.String().deserializer(),
                JsonSerde.of(WorkerTask.class).deserializer()
            );
    }
}
