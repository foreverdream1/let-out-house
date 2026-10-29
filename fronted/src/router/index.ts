import { createRouter, createWebHistory } from "vue-router";
import Login from "../Login.vue";
import AppShell from "../layouts/AppShell.vue";
import HomeView from "../views/HomeView.vue";
import CategoryView from "../views/CategoryView.vue";
import ProfileView from "../views/ProfileView.vue";
import ThemeView from "../views/profile/ThemeView.vue";
import AgentSettings from "../views/profile/AgentSettings.vue";
import AgentTestView from "../views/profile/AgentTestView.vue";

const routes = [
  { path: "/login", name: "Login", component: Login, meta: { public: true } },
  {
    path: "/",
    component: AppShell,
    children: [
      { path: "", name: "Home", component: HomeView, meta: { title: "主页", rail: "home" } },
      {
        path: "category",
        name: "Category",
        component: CategoryView,
        meta: { title: "分类", rail: "category" },
      },
      {
        path: "profile",
        name: "Profile",
        component: ProfileView,
        meta: { title: "我的", rail: "profile" },
      },
      {
        path: "profile/agent",
        name: "AgentSettings",
        component: AgentSettings,
        meta: { title: "Agent 配置", rail: "profile" },
      },
      {
        path: "profile/agent-test",
        name: "AgentTest",
        component: AgentTestView,
        // hideHeader：页面自带会话列表与工具栏，由自身布局充当标题栏
        meta: { title: "测试 Agent", rail: "profile", fullHeight: true, hideHeader: true },
      },
      {
        path: "profile/theme",
        name: "Theme",
        component: ThemeView,
        meta: { title: "主题与配色", rail: "profile" },
      },
    ],
  },
  { path: "/:pathMatch(.*)*", redirect: "/" },
];

const router = createRouter({ history: createWebHistory(), routes });

router.beforeEach((to) => {
  const token = localStorage.getItem("token");
  if (!token && !to.meta.public) return "/login";
  if (token && to.meta.public) return "/";
  return true;
});

export default router;
