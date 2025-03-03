<template>
    <sidebar-menu
        :menu="disabledCurrentRoute(menu)"
        @toggle-collapse="onToggleCollapse"
        :show-one-child="true"
        width="268px"
        :collapsed="collapsed"
    >
        <div class="logo" slot="header">
            <router-link :to="{name: 'home'}">
                <span class="img" />
            </router-link>
        </div>

        <span slot="footer">
            <span class="version">{{ configs ? configs.version : '' }}</span>
        </span>

        <span slot="toggle-icon">
            <chevron-right v-if="collapsed" />
            <chevron-left v-else />
        </span>
    </sidebar-menu>
</template>

<script>
    import Vue from "vue";
    import {SidebarMenu} from "vue-sidebar-menu";
    import ChevronLeft from "vue-material-design-icons/ChevronLeft";
    import ChevronRight from "vue-material-design-icons/ChevronRight";
    import FileTreeOutline from "vue-material-design-icons/FileTreeOutline";
    import ContentCopy from "vue-material-design-icons/ContentCopy";
    import TimelineClockOutline from "vue-material-design-icons/TimelineClockOutline";
    import TimelineTextOutline from "vue-material-design-icons/TimelineTextOutline";
    import NotebookOutline from "vue-material-design-icons/NotebookOutline";
    import BookMultipleOutline from "vue-material-design-icons/BookMultipleOutline";
    import FileCodeOutline from "vue-material-design-icons/FileCodeOutline";
    import GoogleCirclesExtended from "vue-material-design-icons/GoogleCirclesExtended";
    import Slack from "vue-material-design-icons/Slack";
    import Github from "vue-material-design-icons/Github";
    import CogOutline from "vue-material-design-icons/CogOutline";
    import {mapState} from "vuex";

    Vue.component("FlowMenuIcon", FileTreeOutline);
    Vue.component("TemplateMenuIcon", ContentCopy);
    Vue.component("ExecutionMenuIcon", TimelineClockOutline);
    Vue.component("TaskRunMenuIcon", TimelineTextOutline);
    Vue.component("LogMenuIcon", NotebookOutline);
    Vue.component("DocumentationMenuIcon", BookMultipleOutline);
    Vue.component("DocumentationDeveloperMenuIcon", FileCodeOutline);
    Vue.component("DocumentationPluginsMenuIcon", GoogleCirclesExtended);
    Vue.component("Slack", Slack);
    Vue.component("Github", Github);
    Vue.component("SettingMenuIcon", CogOutline);

    export default {
        components: {
            ChevronLeft,
            ChevronRight,
            SidebarMenu,
        },
        methods: {
            onToggleCollapse(folded) {
                this.collapsed = folded;
                localStorage.setItem("menuCollapsed", folded ? "true" : "false");
                this.$emit("onMenuCollapse", folded);
            },
            disabledCurrentRoute(items) {
                return items
                    .map(r => {
                        if (r.href === this.$route.path) {
                            delete r.href;
                        }

                        return r;
                    })
            },
            generateMenu() {
                return [
                    {
                        href: "/flows",
                        alias: [
                            "/flows*"
                        ],
                        title: this.$t("flows"),
                        icon: {
                            element: "FlowMenuIcon",
                            class: "menu-icon",
                        },
                    },
                    {
                        href: "/templates",
                        alias: [
                            "/templates*"
                        ],
                        title: this.$t("templates"),
                        icon: {
                            element: "TemplateMenuIcon",
                            class: "menu-icon",
                        },
                    },
                    {
                        href: "/executions",
                        alias: [
                            "/executions*"
                        ],
                        title: this.$t("executions"),
                        icon: {
                            element: "ExecutionMenuIcon",
                            class: "menu-icon"
                        },
                    },
                    {
                        href: "/taskruns",
                        alias: ["/taskruns*"],
                        title: this.$t("taskruns"),
                        icon: {
                            element: "TaskRunMenuIcon",
                            class: "menu-icon"
                        },
                        hidden: !(this.configs && this.configs.isTaskRunEnabled)
                    },
                    {
                        href: "/logs",
                        alias: [
                            "/logs*"
                        ],
                        title: this.$t("logs"),
                        icon: {
                            element: "LogMenuIcon",
                            class: "menu-icon"
                        },
                    },
                    {
                        alias: [
                            "/plugins*"
                        ],
                        title: this.$t("documentation.documentation"),
                        icon: {
                            element: "DocumentationMenuIcon",
                            class: "menu-icon"
                        },
                        child: [
                            {
                                href: "https://kestra.io/docs/",
                                title: this.$t("documentation.developer"),
                                icon: {
                                    element: "DocumentationDeveloperMenuIcon",
                                    class: "menu-icon"
                                },
                                external: true
                            },
                            {
                                href: "/plugins",
                                title: this.$t("plugins.names"),
                                icon: {
                                    element: "DocumentationPluginsMenuIcon",
                                    class: "menu-icon"
                                },
                            },
                            {
                                href: "https://api.kestra.io/v1/communities/slack/redirect",
                                title: "Slack",
                                icon: {
                                    element: "Slack",
                                    class: "menu-icon"
                                },
                                external: true
                            },
                            {
                                href: "https://github.com/kestra-io/kestra/issues",
                                title: this.$t("documentation.github"),
                                icon: {
                                    element: "Github",
                                    class: "menu-icon"
                                },
                                external: true
                            },

                        ]
                    },
                    {
                        href: "/settings",
                        alias: [
                            "/settings*"
                        ],
                        title: this.$t("settings"),
                        icon: {
                            element: "SettingMenuIcon",
                            class: "menu-icon"
                        }
                    }
                ];
            }

        },
        created() {
            this.menu = this.disabledCurrentRoute(this.generateMenu());
        },
        watch: {
            $route() {
                this.menu = this.disabledCurrentRoute(this.generateMenu());
            }
        },
        data() {
            return {
                collapsed: localStorage.getItem("menuCollapsed") === "true",
                menu: []
            };
        },
        mounted() {
            this.$el.querySelectorAll(".vsm--item span").forEach(e => {
                //empty icon name on mouseover
                e.setAttribute("title","")
            })
        },
        computed: {
            ...mapState("misc", ["configs"])
        }
    };
</script>

<style lang="scss" scoped>
    @import "../../styles/variable";

    .logo {
        overflow: hidden;
        padding: 35px 0;
        height: 133px;
        position: relative;
        a {
            transition: 0.3s all;
            position: absolute;
            left: 37px;
            display: block;
            height: 55px;
            width: 100%;
            overflow: hidden;

            span.img {
                height: 100%;
                background: url(../../../src/assets/logo.svg) 0 0 no-repeat;
                background-size: 179px 55px;
                display: block;

                .theme-dark & {
                    background: url(../../../src/assets/logo-white.svg) 0 0 no-repeat;
                    background-size: 179px 55px;
                }
            }
        }
    }

    span.version {
        transition: 0.3s all;
        white-space: nowrap;
        font-size: $font-size-xs;
        text-align: center;
        display: block;
        color: var(--tertiary);
    }

    .vsm_collapsed {
        .logo {
            a {
                left: 0;
            }
        }

        span.version {
            opacity: 0;
        }
    }

    ::v-deep a.vsm--link_active[href="#"] {
        cursor: initial !important;
    }

    ::v-deep .vsm--item {
        transition: opacity 0.2s;
    }

    ::v-deep .vsm--dropdown {
        .vsm--title {
            top: 3px;
        }
    }

    ::v-deep .menu-icon {
        font-size: 1.5em;
        background-color: transparent !important;
        padding-bottom: 15px;

        svg {
            top: 3px;
            left: 3px;
        }
    }


    ::v-deep .vsm--dropdown_mobile-item {
        .vsm--item {
            .vsm--title {
                left: 0;
                position: relative;
            }
        }
    }
</style>
