<template>
  <div class="space-y-6 sm:space-y-8">
    <!-- Hero -->
    <SpotlightCard
      class-name="!p-6 sm:!p-10 !rounded-[28px] !border-outline-variant/40 bg-gradient-to-br from-primary-container/70 via-surface-container to-tertiary-container/50"
      :spotlight-color="spotlight"
    >
      <div class="pointer-events-none absolute inset-0 opacity-35">
        <SoftAurora
          :color1="palette.primary || '#ad6dff'"
          :color2="palette.tertiary || '#4c8dff'"
          :speed="0.4"
          :brightness="0.8"
          :enable-mouse-interaction="false"
        />
      </div>
      <div class="relative flex flex-col gap-6 md:flex-row md:items-end md:justify-between">
        <div class="min-w-0">
          <div class="mb-3 inline-flex items-center gap-2 rounded-full bg-surface/60 px-3 py-1 text-xs text-on-surface-variant backdrop-blur">
            <span class="h-2 w-2 rounded-full bg-primary animate-pulse" />
            {{ today }}
          </div>
          <BlurText
            :key="greeting"
            :text="`${greeting}，${name}`"
            animate-by="letters"
            :delay="40"
            :step-duration="0.3"
            direction="top"
            class-name="text-3xl sm:text-4xl lg:text-5xl font-semibold tracking-tight text-on-surface"
          />
          <div class="mt-3 flex flex-wrap items-center gap-1.5 max-w-xl text-sm sm:text-base text-on-surface-variant">
            <span>欢迎回到</span>
            <GradientText
              :colors="gradient"
              :animation-speed="6"
              class-name="!mx-0 !inline-flex align-baseline !rounded-none !cursor-default font-semibold"
            >Let Out House</GradientText>
            <span>，一站式</span>
            <RotatingText
              :texts="['智能问答', '知识库检索', '多模型调度', '提示词编排']"
              :rotation-interval="2600"
              main-class-name="inline-flex overflow-hidden rounded-lg bg-primary-container px-2 py-0.5 text-xs sm:text-sm font-medium text-on-primary-container"
              split-level-class-name="overflow-hidden"
            />
          </div>
        </div>
        <div class="flex flex-wrap gap-3">
          <mdui-button icon="add" @click="router.push('/profile/agent')">新建 Agent</mdui-button>
          <mdui-button variant="tonal" icon="science--outlined" @click="router.push('/profile/agent-test')">
            测试对话
          </mdui-button>
        </div>
      </div>
    </SpotlightCard>

    <!-- 统计 -->
    <section class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
      <SpotlightCard
        v-for="s in stats"
        :key="s.label"
        class-name="!p-4 sm:!p-5 !rounded-3xl !border-outline-variant/40 bg-surface-container"
        :spotlight-color="spotlight"
      >
        <div class="flex items-center justify-between">
          <span class="text-xs sm:text-sm text-on-surface-variant">{{ s.label }}</span>
          <span class="inline-flex h-9 w-9 items-center justify-center rounded-xl" :class="s.tone">
            <mdui-icon :name="s.icon" class="text-[20px]"></mdui-icon>
          </span>
        </div>
        <div class="mt-3 text-3xl sm:text-4xl font-semibold tabular-nums text-on-surface">
          <span v-if="loading" class="inline-block h-9 w-12 animate-pulse rounded-lg bg-surface-container-highest" />
          <CountUp v-else :to="s.value" :duration="1.2" />
        </div>
      </SpotlightCard>
    </section>

    <!-- 快捷入口 -->
    <section>
      <h2 class="mb-3 px-1 text-sm font-medium text-on-surface-variant">快捷入口</h2>
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 sm:gap-4">
        <SpotlightCard
          v-for="q in quick"
          :key="q.to"
          class-name="!p-0 !rounded-3xl !border-outline-variant/40 bg-surface-container"
          :spotlight-color="spotlight"
        >
          <button
            type="button"
            class="group flex w-full items-center gap-4 p-5 text-left cursor-pointer outline-none"
            @click="router.push(q.to)"
          >
            <span class="inline-flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl" :class="q.tone">
              <mdui-icon :name="q.icon" class="text-[24px]"></mdui-icon>
            </span>
            <span class="min-w-0 flex-1">
              <span class="block font-medium text-on-surface">{{ q.title }}</span>
              <span class="mt-0.5 block truncate text-sm text-on-surface-variant">{{ q.desc }}</span>
            </span>
            <mdui-icon
              name="arrow_forward"
              class="text-on-surface-variant transition-transform group-hover:translate-x-1"
            ></mdui-icon>
          </button>
        </SpotlightCard>
      </div>
    </section>

    <!-- 最近的 Agent / 最近的会话 -->
    <div class="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
      <section class="m3-card overflow-hidden">
        <div class="flex items-center justify-between px-5 pt-4">
          <h2 class="text-sm font-medium text-on-surface-variant">最近的 Agent</h2>
          <mdui-button variant="text" end-icon="chevron_right" @click="router.push('/profile/agent')">全部</mdui-button>
        </div>
        <div v-if="loading" class="space-y-2 p-5">
          <div v-for="i in 3" :key="i" class="h-14 animate-pulse rounded-2xl bg-surface-container-highest" />
        </div>
        <div v-else-if="!recent.length" class="flex flex-col items-center gap-2 px-5 py-10 text-on-surface-variant">
          <mdui-icon name="smart_toy--outlined" class="text-4xl opacity-60"></mdui-icon>
          <span class="text-sm">还没有 Agent，先去创建一个吧</span>
        </div>
        <mdui-list v-else>
          <mdui-list-item
            rounded
            v-for="a in recent"
            :key="a.agentId"
            :headline="a.agentName"
            :description="a.modelName"
            @click="router.push('/profile/agent')"
          >
            <span
              slot="icon"
              class="inline-flex h-10 w-10 items-center justify-center rounded-full bg-secondary-container text-on-secondary-container"
            >
              <mdui-icon :name="a.ragEnable ? 'menu_book--outlined' : 'smart_toy--outlined'"></mdui-icon>
            </span>
            <span
              v-if="a.ragEnable"
              slot="end-icon"
              class="rounded-full bg-tertiary-container px-2.5 py-0.5 text-xs text-on-tertiary-container"
            >RAG</span>
          </mdui-list-item>
        </mdui-list>
      </section>

      <section class="m3-card overflow-hidden">
        <div class="flex items-center justify-between px-5 pt-4">
          <h2 class="text-sm font-medium text-on-surface-variant">最近的会话</h2>
          <mdui-button variant="text" end-icon="chevron_right" @click="router.push('/profile/agent-test')">全部</mdui-button>
        </div>
        <div v-if="convLoading" class="space-y-2 p-5">
          <div v-for="i in 3" :key="i" class="h-14 animate-pulse rounded-2xl bg-surface-container-highest" />
        </div>
        <div v-else-if="!conversations.length" class="flex flex-col items-center gap-2 px-5 py-10 text-on-surface-variant">
          <mdui-icon name="forum--outlined" class="text-4xl opacity-60"></mdui-icon>
          <span class="text-sm">还没有会话，去发起一次对话吧</span>
        </div>
        <mdui-list v-else>
          <mdui-list-item
            rounded
            v-for="c in conversations"
            :key="c.conversationId"
            :headline="c.title"
            :description="`${c.messageCount} 条消息 · ${fmtTime(c.lastMessageAt || c.createdAt)}`"
            @click="router.push('/profile/agent-test')"
          >
            <span
              slot="icon"
              class="inline-flex h-10 w-10 items-center justify-center rounded-full bg-primary-container text-on-primary-container"
            >
              <mdui-icon name="chat_bubble--outlined"></mdui-icon>
            </span>
          </mdui-list-item>
        </mdui-list>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import SpotlightCard from "../components/SpotlightCard.vue";
import BlurText from "../components/BlurText.vue";
import GradientText from "../components/GradientText.vue";
import RotatingText from "../components/RotatingText.vue";
import SoftAurora from "../components/SoftAurora.vue";
import CountUp from "../components/CountUp.vue";
import { agentApi, type AgentResponse } from "../api/agent";
import { conversationApi, type ConversationResponse } from "../api/conversation";
import { useTheme } from "../composables/useTheme";

const router = useRouter();
const { palette } = useTheme();

const user = JSON.parse(localStorage.getItem("user") || "null");
const name = user?.nickname || user?.username || "朋友";

const hour = new Date().getHours();
const greeting = hour < 6 ? "夜深了" : hour < 12 ? "早上好" : hour < 14 ? "中午好" : hour < 18 ? "下午好" : "晚上好";
const today = new Date().toLocaleDateString("zh-CN", { month: "long", day: "numeric", weekday: "long" });

const spotlight = computed(() => `${palette.value.primary}33`);
const gradient = computed(() => [palette.value.primary, palette.value.tertiary, palette.value.secondary]);

const loading = ref(true);
const agents = ref<AgentResponse[]>([]);
const total = ref(0);

const convLoading = ref(true);
const conversations = ref<ConversationResponse[]>([]);

const fmtTime = (iso: string | null) => {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const now = new Date();
  if (d.toDateString() === now.toDateString())
    return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
  return d.toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" });
};

const stats = computed(() => [
  {
    label: "Agent 总数",
    value: total.value,
    icon: "smart_toy--outlined",
    tone: "bg-primary-container text-on-primary-container",
  },
  {
    label: "启用 RAG",
    value: agents.value.filter((a) => a.ragEnable).length,
    icon: "menu_book--outlined",
    tone: "bg-tertiary-container text-on-tertiary-container",
  },
  {
    label: "使用模型",
    value: new Set(agents.value.map((a) => a.modelName)).size,
    icon: "memory",
    tone: "bg-secondary-container text-on-secondary-container",
  },
  {
    label: "关联知识库",
    value: new Set(agents.value.flatMap((a) => a.knowledgeBaseIds || [])).size,
    icon: "folder_open",
    tone: "bg-surface-container-highest text-on-surface",
  },
]);

const recent = computed(() => agents.value.slice(0, 5));

const quick = [
  {
    title: "Agent 配置",
    desc: "模型、参数与知识库",
    icon: "tune",
    to: "/profile/agent",
    tone: "bg-primary-container text-on-primary-container",
  },
  {
    title: "测试 Agent",
    desc: "实时对话验证效果",
    icon: "forum--outlined",
    to: "/profile/agent-test",
    tone: "bg-tertiary-container text-on-tertiary-container",
  },
  {
    title: "主题与配色",
    desc: "Material You 动态取色",
    icon: "palette--outlined",
    to: "/profile/theme",
    tone: "bg-secondary-container text-on-secondary-container",
  },
];

onMounted(async () => {
  const [agentTask, convTask] = [
    agentApi
      .list({ pageNum: 1, pageSize: 100 })
      .then((page) => {
        agents.value = page.list;
        total.value = page.total;
      })
      .catch(() => {
        // 统计失败不打扰用户，显示 0
      })
      .finally(() => {
        loading.value = false;
      }),
    conversationApi
      .list({ pageNum: 1, pageSize: 5 })
      .then((page) => {
        conversations.value = page.list;
      })
      .catch(() => {
        // 会话加载失败不影响首页其他区块
      })
      .finally(() => {
        convLoading.value = false;
      }),
  ];
  await Promise.all([agentTask, convTask]);
});
</script>
