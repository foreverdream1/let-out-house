<template>
  <div class="mx-auto max-w-5xl space-y-6">
    <!-- 用户卡片 -->
    <SpotlightCard
      class-name="!p-6 sm:!p-8 !rounded-[28px] !border-outline-variant/40 bg-gradient-to-br from-primary-container/60 via-surface-container to-surface-container"
      :spotlight-color="`${palette.primary}33`"
    >
      <div class="flex flex-col items-center gap-5 text-center sm:flex-row sm:text-left">
        <mdui-avatar class="h-20 w-20 shrink-0 bg-primary text-on-primary text-3xl font-semibold">
          {{ initial }}
        </mdui-avatar>
        <div class="min-w-0 flex-1">
          <div class="truncate text-2xl font-semibold text-on-surface">
            {{ user?.nickname || user?.username || "未登录" }}
          </div>
          <div class="mt-2 flex flex-wrap justify-center gap-2 sm:justify-start">
            <span class="rounded-full bg-surface/60 px-3 py-1 text-xs text-on-surface-variant backdrop-blur">
              <DecryptedText :text="`@${user?.username || '—'}`" animate-on="view" :speed="45" />
            </span>
            <span
              v-for="r in roles"
              :key="r"
              class="rounded-full bg-secondary-container px-3 py-1 text-xs text-on-secondary-container"
            >
              {{ r }}
            </span>
          </div>
        </div>
        <mdui-button variant="tonal" icon="logout" class="hidden sm:inline-flex" @click="logout">退出登录</mdui-button>
      </div>
    </SpotlightCard>

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-2 lg:items-start">
      <!-- 账号信息 -->
      <section class="m3-card overflow-hidden">
        <div class="m3-section-title">账号信息</div>
        <mdui-list>
          <mdui-list-item
            rounded
            v-for="row in info"
            :key="row.label"
            :headline="row.label"
            :description="row.value"
            nonclickable
          >
            <mdui-icon slot="icon" :name="row.icon"></mdui-icon>
          </mdui-list-item>
        </mdui-list>
      </section>

      <!-- 设置入口 -->
      <section class="m3-card overflow-hidden">
        <div class="m3-section-title">设置</div>
        <mdui-list>
          <mdui-list-item
            rounded
            v-for="s in settings"
            :key="s.to"
            :headline="s.title"
            :description="s.desc"
            @click="router.push(s.to)"
          >
            <span
              slot="icon"
              class="inline-flex h-10 w-10 items-center justify-center rounded-full"
              :class="s.tone"
            >
              <mdui-icon :name="s.icon"></mdui-icon>
            </span>
            <mdui-icon slot="end-icon" name="chevron_right"></mdui-icon>
          </mdui-list-item>
        </mdui-list>
      </section>
    </div>

    <mdui-button variant="tonal" icon="logout" full-width class="sm:hidden" @click="logout">退出登录</mdui-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import SpotlightCard from "../components/SpotlightCard.vue";
import DecryptedText from "../components/DecryptedText.vue";
import { toast } from "../composables/useToast";
import { useTheme } from "../composables/useTheme";

const router = useRouter();
const { palette } = useTheme();
const user = JSON.parse(localStorage.getItem("user") || "null");

const initial = computed(() => {
  const name = user?.nickname || user?.username || "?";
  return name.slice(0, 1).toUpperCase();
});

const roles = computed<string[]>(() => (user?.roleList?.length ? user.roleList : ["普通用户"]));

const info = [
  { label: "用户名", value: user?.username || "—", icon: "person--outlined" },
  { label: "用户 ID", value: String(user?.userId ?? "—"), icon: "badge--outlined" },
  { label: "租户", value: `${user?.tenantCode || "—"} (#${user?.tenantId ?? "—"})`, icon: "apartment--outlined" },
  { label: "角色", value: (user?.roleList || []).join("、") || "—", icon: "verified_user--outlined" },
];

const settings = [
  {
    title: "Agent 配置",
    desc: "配置模型、参数与知识库",
    icon: "smart_toy--outlined",
    to: "/profile/agent",
    tone: "bg-primary-container text-on-primary-container",
  },
  {
    title: "测试 Agent",
    desc: "验证配置效果",
    icon: "science--outlined",
    to: "/profile/agent-test",
    tone: "bg-tertiary-container text-on-tertiary-container",
  },
  {
    title: "知识库文档",
    desc: "上传与管理知识库文件",
    icon: "folder--outlined",
    to: "/profile/knowledge",
    tone: "bg-surface-container-highest text-on-surface",
  },
  {
    title: "用量与账单",
    desc: "Token 消耗与费用明细",
    icon: "receipt_long--outlined",
    to: "/profile/billing",
    tone: "bg-primary-container text-on-primary-container",
  },
  {
    title: "主题与配色",
    desc: "明暗模式与动态配色",
    icon: "palette--outlined",
    to: "/profile/theme",
    tone: "bg-secondary-container text-on-secondary-container",
  },
];

const logout = () => {
  localStorage.removeItem("token");
  localStorage.removeItem("user");
  localStorage.removeItem("isLogin");
  toast.success("已退出登录");
  router.replace("/login");
};
</script>
