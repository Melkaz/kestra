<template>
    <span v-if="date" v-b-tooltip.hover="inverted ? from : full">{{ inverted ? full : from }}</span>
</template>
<script>
    import Vue from "vue";

    export default {
        props: {
            date: {
                type: String,
                default: undefined
            },
            inverted: {
                type: Boolean,
                default: false
            },
            format: {
                type: String,
                default: "LLLL"
            }
        },
        computed: {
            from() {
                return Vue.moment(this.date).fromNow();
            },
            full() {
                return Vue.moment(this.date).format(this.format);
            }
        }
    };
</script>
