<template>
  <div class="flex h-full min-h-0 gap-4">
    <!-- 桌面：左侧会话列表 -->
    <aside
      class="hidden w-64 shrink-0 flex-col overflow-hidden rounded-[28px] border border-outline-variant/40 bg-surface-container md:flex"
    >
      <div class="flex items-center justify-between gap-2 px-4 pb-2 pt-4">
        <span class="text-xs font-medium tracking-wide text-primary">会话</span>
        <mdui-button-icon icon="add" title="新建会话" @click="newConversation"></mdui-button-icon>
      </div>

      <div v-if="convLoading" class="space-y-2 px-3 py-2">
        <div v-for="i in 4" :key="i" class="h-12 animate-pulse rounded-2xl bg-surface-container-highest" />
      </div>

      <div v-else-if="!conversations.length" class="flex flex-1 flex-col items-center gap-2 px-4 py-10 text-center text-on-surface-variant">
        <mdui-icon name="chat_bubble--outlined" class="text-4xl opacity-60"></mdui-icon>
        <span class="text-xs">还没有会话</span>
      </div>

      <div v-else ref="convListRef" class="min-h-0 flex-1 overflow-y-auto px-2 pb-2" @scroll="onConvScroll">
        <button
          v-for="c in conversations"
          :key="c.conversationId"
          type="button"
          class="group mb-1 flex w-full items-center gap-2 rounded-2xl px-3 py-2.5 text-left transition-colors cursor-pointer"
          :class="
            c.conversationId === conversationId
              ? 'bg-primary-container text-on-primary-container'
              : 'text-on-surface hover:bg-on-surface/[0.08]'
          "
          @click="openConversation(c)"
        >
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm font-medium">{{ c.title }}</span>
            <span
              class="mt-0.5 block truncate text-xs"
              :class="c.conversationId === conversationId ? 'text-on-primary-container/70' : 'text-on-surface-variant'"
            >
              {{ fmtTime(c.lastMessageAt || c.createdAt) }}
            </span>
          </span>
          <mdui-button-icon
            icon="delete--outlined"
            class="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
            @click.stop="removeConversation(c)"
          ></mdui-button-icon>
        </button>
        <div v-if="convHasMore" class="flex justify-center py-2">
          <mdui-button variant="text" :loading="convLoadingMore" @click="loadMoreConversations">加载更多</mdui-button>
        </div>
      </div>
    </aside>

    <!-- 对话主区 -->
    <div class="mx-auto flex h-full min-h-0 w-full max-w-4xl flex-col gap-4">
      <!-- 工具栏 -->
      <div class="flex shrink-0 items-center gap-2 pt-[env(safe-area-inset-top)]">
        <mdui-button-icon
          icon="arrow_back"
          title="返回"
          @click="goBack"
        ></mdui-button-icon>

        <mdui-button-icon
          icon="menu"
          title="会话列表"
          @click="mobileOpen = true"
        ></mdui-button-icon>

        <h1 class="truncate px-1 text-xl font-medium text-on-surface md:hidden">
          {{ currentConversation?.title || "测试 Agent" }}
        </h1>

        <mdui-select
          variant="outlined"
          label="选择 Agent"
          icon="smart_toy--outlined"
          class="hidden min-w-0 sm:flex sm:max-w-xs md:flex"
          :value="selectedAgentId == null ? '' : String(selectedAgentId)"
          :disabled="!!conversationId"
          @change="onAgentChange"
        >
          <mdui-menu-item v-for="a in agents" :key="a.agentId" :value="String(a.agentId)">
            {{ a.agentName }}
          </mdui-menu-item>
        </mdui-select>

        <span
          v-if="currentConversation"
          class="hidden items-center gap-1.5 rounded-full bg-surface-container-high px-3 py-1.5 text-xs text-on-surface-variant lg:inline-flex"
        >
          <mdui-icon name="forum--outlined" class="text-[14px]"></mdui-icon>
          {{ currentConversation.messageCount }} 条消息
        </span>

        <div class="flex-1" />

        <mdui-button-icon
          :icon="isDark ? 'light_mode--outlined' : 'dark_mode--outlined'"
          :title="isDark ? '切换到浅色' : '切换到深色'"
          @click="setMode(isDark ? 'light' : 'dark')"
        ></mdui-button-icon>

        <mdui-button-icon icon="add_comment--outlined" title="新建会话" @click="newConversation"></mdui-button-icon>
      </div>

      <!-- 消息区 -->
      <div ref="listRef" class="m3-card min-h-0 flex-1 space-y-4 overflow-y-auto p-4 sm:p-6" @scroll="onListScroll">
        <!-- 空状态 -->
        <div
          v-if="!messages.length && !streaming"
          class="flex h-full flex-col items-center justify-center gap-3 text-center"
        >
          <span class="inline-flex h-16 w-16 items-center justify-center rounded-3xl bg-primary-container text-on-primary-container">
            <mdui-icon name="forum--outlined" class="text-[30px]"></mdui-icon>
          </span>
          <ShinyText
            text="还没有对话，试试问点什么"
            :speed="3"
            :color="palette['on-surface-variant']"
            :shine-color="palette.primary"
            class-name="text-sm"
          />
          <div class="mt-2 flex flex-wrap justify-center gap-2">
            <button
              v-for="q in suggestions"
              :key="q"
              type="button"
              class="rounded-full border border-outline-variant px-3 py-1.5 text-xs text-on-surface-variant transition-colors hover:bg-on-surface/[0.08] cursor-pointer"
              @click="input = q; send()"
            >
              {{ q }}
            </button>
          </div>
        </div>

        <!-- 历史加载 -->
        <div v-if="historyLoading" class="flex justify-center py-2">
          <mdui-circular-progress class="h-5 w-5"></mdui-circular-progress>
        </div>

        <TransitionGroup name="msg">
          <div
            v-for="m in messages"
            :key="m.id"
            class="flex items-end gap-2"
            :class="m.role === 'user' ? 'flex-row-reverse' : ''"
          >
            <span
              class="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-sm"
              :class="m.role === 'user' ? 'bg-primary text-on-primary' : 'bg-tertiary-container text-on-tertiary-container'"
            >
              <mdui-icon :name="m.role === 'user' ? 'person' : 'smart_toy--outlined'" class="text-[18px]"></mdui-icon>
            </span>
            <div
              class="selectable max-w-[80%] whitespace-pre-wrap break-words px-4 py-2.5 text-sm leading-relaxed"
              :class="
                m.role === 'user'
                  ? 'rounded-3xl rounded-br-md bg-primary-container text-on-primary-container'
                  : 'rounded-3xl rounded-bl-md bg-surface-container-highest text-on-surface'
              "
            >
              <span>{{ m.text }}</span>
              <!-- 流式光标 -->
              <span
                v-if="m.streaming"
                class="ml-0.5 inline-block h-[0.9em] w-[2px] translate-y-[2px] animate-pulse bg-primary"
              />
              <!-- 没收到任何增量时先显示占位 -->
              <span v-if="m.streaming && !m.text" class="inline-flex gap-1 py-1">
                <span class="h-1.5 w-1.5 animate-bounce rounded-full bg-on-surface-variant" />
                <span class="h-1.5 w-1.5 animate-bounce rounded-full bg-on-surface-variant [animation-delay:150ms]" />
                <span class="h-1.5 w-1.5 animate-bounce rounded-full bg-on-surface-variant [animation-delay:300ms]" />
              </span>
            </div>
          </div>
        </TransitionGroup>

        <ThoughtLine
          v-if="thinking"
          :label="thinkingLabel"
          done-label="已完成"
          :steps="steps"
          :working="thinking"
          :font-size="14"
          :settle-after="0"
          :color="palette['on-surface']"
          :glyph-color="palette.primary"
          class="pl-10 pt-2"
        />
      </div>

      <!-- 错误提示条 -->
      <div
        v-if="lastError"
        class="flex shrink-0 items-start gap-2 rounded-2xl border border-error/40 bg-error-container px-4 py-2.5 text-sm text-on-error-container"
      >
        <mdui-icon name="error--outlined" class="mt-0.5 shrink-0 text-[18px]"></mdui-icon>
        <div class="min-w-0 flex-1">
          <div class="break-words">{{ lastError.message }}</div>
          <div v-if="lastError.traceId" class="mt-0.5 selectable text-xs opacity-70">traceId: {{ lastError.traceId }}</div>
        </div>
        <mdui-button-icon icon="close" class="shrink-0" @click="lastError = null"></mdui-button-icon>
      </div>

      <!-- 输入栏 -->
      <div class="flex shrink-0 items-end gap-2 rounded-[28px] bg-surface-container-high p-2 pl-4">
        <mdui-text-field
          variant="filled"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          class="composer flex-1"
          :value="input"
          :disabled="streaming || !selectedAgentId"
          @input="input = $event.target.value"
          @keydown.enter.exact.prevent="send"
        ></mdui-text-field>
        <mdui-fab
          v-if="streaming"
          size="small"
          icon="stop"
          class="shrink-0"
          title="停止生成"
          @click="stop"
        ></mdui-fab>
        <mdui-fab
          v-else
          size="small"
          icon="send"
          class="shrink-0"
          :disabled="!input.trim() || !selectedAgentId"
          @click="send"
        ></mdui-fab>
      </div>
    </div>

    <!-- 移动端：会话抽屉 -->
    <mdui-dialog
      :open="mobileOpen"
      class="w-[min(88vw,22rem)]"
      @closed="mobileOpen = false"
    >
      <div slot="headline" class="flex items-center justify-between">
        <span>会话</span>
        <mdui-button-icon icon="add" title="新建会话" @click="mobileOpen = false; newConversation()"></mdui-button-icon>
      </div>
      <div class="-mx-2 max-h-[60vh] overflow-y-auto">
        <button
          v-for="c in conversations"
          :key="c.conversationId"
          type="button"
          class="group flex w-full items-center gap-2 rounded-2xl px-3 py-2.5 text-left cursor-pointer"
          :class="
            c.conversationId === conversationId
              ? 'bg-primary-container text-on-primary-container'
              : 'text-on-surface hover:bg-on-surface/[0.08]'
          "
          @click="mobileOpen = false; openConversation(c)"
        >
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm font-medium">{{ c.title }}</span>
            <span class="mt-0.5 block truncate text-xs opacity-70">{{ fmtTime(c.lastMessageAt || c.createdAt) }}</span>
          </span>
          <mdui-button-icon icon="delete--outlined" @click.stop="removeConversation(c)"></mdui-button-icon>
        </button>
        <div v-if="!conversations.length" class="px-4 py-8 text-center text-sm text-on-surface-variant">
          还没有会话
        </div>
      </div>
      <mdui-button slot="action" variant="text" @click="mobileOpen = false">关闭</mdui-button>
    </mdui-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import ThoughtLine from "../../components/ThoughtLine.vue";
import ShinyText from "../../components/ShinyText.vue";
import { useTheme } from "../../composables/useTheme";
import { agentApi, type AgentResponse } from "../../api/agent";
import { conversationApi, type ConversationResponse, type MessageResponse } from "../../api/conversation";
import { streamChat, ChatStreamError } from "../../api/chat";
import { toast } from "../../composables/useToast";

interface Msg {
  id: number;
  role: "user" | "assistant";
  text: string;
  streaming?: boolean;
}

const router = useRouter();

const PAGE = 20;

const agents = ref<AgentResponse[]>([]);
const selectedAgentId = ref<number | null>(null);

const conversations = ref<ConversationResponse[]>([]);
const conversationId = ref<number | null>(null);
const convPage = ref(1);
const convTotal = ref(0);
const convLoading = ref(true);
const convLoadingMore = ref(false);
const convHasMore = computed(() => conversations.value.length < convTotal.value);
const convListRef = ref<HTMLElement | null>(null);
/** 会话列表请求序号，用于丢弃过期响应 */
let convLoadToken = 0;

const historyLoading = ref(false);
const historyPage = ref(1);
const historyHasMore = ref(false);

const messages = ref<Msg[]>([]);
const input = ref("");
const streaming = ref(false);
/** 切换会话/重建状态时递增，用于丢弃上一轮未完成的 SSE 回调 */
let runToken = 0;
const steps = ref<string[]>([]);
const lastError = ref<{ message: string; traceId?: string } | null>(null);
const mobileOpen = ref(false);

let seed = 0;
let controller: AbortController | null = null;

const { palette, isDark, setMode } = useTheme();
const listRef = ref<HTMLElement | null>(null);
const suggestions = ["你能做什么？", "帮我总结一下知识库", "介绍一下你自己"];

const currentConversation = computed(
  () => conversations.value.find((c) => c.conversationId === conversationId.value) ?? null,
);

const goBack = () => {
  if (window.history.state?.back) router.back();
  else router.push("/profile");
};

// 流式期间不打断用户上滑阅读
let stickToBottom = true;

const thinking = computed(() => streaming.value && steps.value.length > 0 && !messages.value.some((m) => m.streaming && m.text));
const thinkingLabel = computed(() =>
  steps.value[steps.value.length - 1] === "接收回答" ? "正在生成回答…" : "正在思考…",
);

const scrollToBottom = (smooth = true) => {
  const el = listRef.value;
  if (!el) return;
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? "smooth" : "auto" });
};

const onListScroll = () => {
  const el = listRef.value;
  if (!el) return;
  stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  if (stickToBottom) onScrollBottom();
};

watch(
  () => messages.value.length,
  async () => {
    await Promise.resolve();
    if (stickToBottom) scrollToBottom();
  },
);

watch(
  () => steps.value.length,
  () => {
    if (stickToBottom) scrollToBottom();
  },
);

const fmtTime = (iso: string | null) => {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
  return d.toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" });
};

/* ---------------- 会话列表 ---------------- */

const loadConversations = async (reset = true) => {
  if (reset) {
    convLoading.value = true;
    convPage.value = 1;
  } else {
    convLoadingMore.value = true;
  }
  // 记下发请求时的 token，回来后若已有更新的一轮请求则丢弃本次结果，
  // 否则"发送后立即刷新"会和"发送前那次刷新"打架，把刚插到顶部的会话顶掉。
  const token = ++convLoadToken;
  try {
    const page = await conversationApi.list({ pageNum: convPage.value, pageSize: PAGE });
    if (token !== convLoadToken) return;
    conversations.value = reset ? page.list : [...conversations.value, ...page.list];
    convTotal.value = page.total;
  } catch (e) {
    if (token === convLoadToken) toast.error("加载会话失败", (e as Error).message);
  } finally {
    if (token === convLoadToken) {
      convLoading.value = false;
      convLoadingMore.value = false;
    }
  }
};

const loadMoreConversations = async () => {
  if (convLoadingMore.value || !convHasMore.value) return;
  convPage.value += 1;
  await loadConversations(false);
};

const onConvScroll = () => {
  const el = convListRef.value;
  if (!el || !convHasMore.value || convLoadingMore.value) return;
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) loadMoreConversations();
};

/* ---------------- 打开 / 新建 / 删除会话 ---------------- */

const resetConversationState = () => {
  conversationId.value = null;
  messages.value = [];
  steps.value = [];
  lastError.value = null;
  historyPage.value = 1;
  historyHasMore.value = false;
  seed = 0;
};

const toMsg = (m: MessageResponse): Msg => ({
  id: m.messageId,
  role: m.role === "user" ? "user" : "assistant",
  text: m.content,
});

const openConversation = async (c: ConversationResponse) => {
  if (streaming.value) {
    toast.info("请先等待当前回答结束");
    return;
  }
  resetConversationState();
  const token = ++runToken;
  conversationId.value = c.conversationId;
  selectedAgentId.value = c.agentId;
  historyLoading.value = true;
  try {
    // 先取第 1 页拿 total，再直接跳到最后一页，避免长会话从头翻
    const first = await conversationApi.history(c.conversationId, { pageNum: 1, pageSize: PAGE });
    if (token !== runToken) return;

    const visible = (list: MessageResponse[]) => list.filter((m) => m.role !== "system").map(toMsg);
    let list = visible(first.list);
    let total = first.total;
    const lastPage = Math.max(1, Math.ceil(total / PAGE));

    if (lastPage > 1) {
      historyPage.value = lastPage;
      const last = await conversationApi.history(c.conversationId, {
        pageNum: lastPage,
        pageSize: PAGE,
      });
      if (token !== runToken) return;
      list = visible(last.list);
      total = last.total;
    } else {
      historyPage.value = 1;
    }

    messages.value = list;
    historyHasMore.value = list.length < total;
    seed = list.reduce((mx, m) => Math.max(mx, m.id), 0);
  } catch (e) {
    if (token === runToken) lastError.value = { message: (e as Error).message };
  } finally {
    if (token === runToken) {
      historyLoading.value = false;
      stickToBottom = true;
      await Promise.resolve();
      scrollToBottom(false);
    }
  }
};

/**
 * 后端 history 按 messageId 正序返回，第 1 页是最早的一批消息，
 * 所以"更多历史"是往后翻页并追加到列表末尾。
 * 长会话进入时直接跳到最后一页，更符合聊天场景。
 */
const loadNextHistoryPage = async () => {
  if (!conversationId.value || historyLoading.value || !historyHasMore.value) return;
  historyLoading.value = true;
  try {
    historyPage.value += 1;
    const page = await conversationApi.history(conversationId.value, {
      pageNum: historyPage.value,
      pageSize: PAGE,
    });
    messages.value = [...messages.value, ...page.list.filter((m) => m.role !== "system").map(toMsg)];
    historyHasMore.value = messages.value.length < page.total;
  } catch (e) {
    historyPage.value -= 1;
    toast.error("加载更多历史失败", (e as Error).message);
  } finally {
    historyLoading.value = false;
  }
};

// 滑到底部且有更早消息没取完时（用户还没开始发消息）继续补全
const onScrollBottom = () => {
  const el = listRef.value;
  if (!el) return;
  if (streaming.value) return;
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 40) {
    loadNextHistoryPage().then(() => {
      if (historyHasMore.value) {
        stickToBottom = true;
        scrollToBottom(false);
      }
    });
  }
};

onBeforeUnmount(() => {
  controller?.abort();
});

const newConversation = () => {
  if (streaming.value) {
    toast.info("请先等待当前回答结束");
    return;
  }
  resetConversationState();
};

const removeConversation = async (c: ConversationResponse) => {
  if (streaming.value && c.conversationId === conversationId.value) {
    toast.info("请先等待当前回答结束");
    return;
  }
  try {
    await conversationApi.remove(c.conversationId);
    conversations.value = conversations.value.filter((x) => x.conversationId !== c.conversationId);
    convTotal.value = Math.max(0, convTotal.value - 1);
    if (c.conversationId === conversationId.value) resetConversationState();
    toast.success("会话已删除");
  } catch (e) {
    toast.error("删除失败", (e as Error).message);
  }
};

const onAgentChange = (ev: Event) => {
  const v = (ev.target as HTMLSelectElement).value;
  selectedAgentId.value = v ? Number(v) : null;
};

/* ---------------- 发送 ---------------- */

const stop = () => controller?.abort();

const send = async () => {
  const text = input.value.trim();
  if (!text || streaming.value) return;
  if (!selectedAgentId.value) return toast.error("请先选择 Agent");

  lastError.value = null;
  input.value = "";
  stickToBottom = true;

  let convId = conversationId.value;
  if (!convId) {
    try {
      convId = await conversationApi.create({ agentId: selectedAgentId.value });
      conversationId.value = convId;
      // 新会话先插到列表顶部，标题以首条问题占位（后端落库后会改成缩写）
      conversations.value.unshift({
        conversationId: convId,
        agentId: selectedAgentId.value,
        // 与后端自动标题规则一致：取问题前 20 字，超出加省略号
        title: text.length > 20 ? text.slice(0, 20) + "..." : text,
        messageCount: 0,
        lastMessageAt: null,
        createdAt: new Date().toISOString(),
      });
      convTotal.value += 1;
    } catch (e) {
      lastError.value = { message: (e as Error).message };
      input.value = text;
      return;
    }
  }

  const userMsg: Msg = { id: ++seed, role: "user", text };
  const aiMsg: Msg = { id: ++seed, role: "assistant", text: "", streaming: true };
  messages.value.push(userMsg, aiMsg);

  streaming.value = true;
  steps.value = ["解析问题", "调用 Agent"];

  // 必须从响应式数组里取回消息对象：直接持有 aiMsg 原始对象的话，
  // 对 target.text 的修改不会触发 Vue 依赖追踪，界面永远停在"输入中"。
  const target = messages.value[messages.value.length - 1];
  const token = runToken;
  controller = new AbortController();

  try {
    await streamChat({
      conversationId: convId,
      query: text,
      signal: controller.signal,
      onEvent: (ev) => {
        if (token !== runToken) return;
        if (ev.type === "chunk") {
          if (steps.value[steps.value.length - 1] !== "接收回答") steps.value.push("接收回答");
          target.text += ev.content;
          if (stickToBottom) scrollToBottom(false);
        } else if (ev.type === "end") {
          if (ev.fullContent && !target.text) target.text = ev.fullContent;
        } else if (ev.type === "error") {
          lastError.value = { message: ev.content, traceId: ev.traceId };
        }
      },
    });
  } catch (e) {
    const err = e as Error;
    // 用户主动停止不算错误
    if (err.name !== "AbortError") {
      const traceId = e instanceof ChatStreamError ? e.traceId : undefined;
      if (token === runToken) lastError.value = { message: err.message, traceId };
      if (!target.text) messages.value = messages.value.filter((m) => m.id !== target.id);
    }
  } finally {
    target.streaming = false;
    streaming.value = false;
    steps.value = [];
    controller = null;
    if (token === runToken) {
      // 先本地即时更新，避免刷新过程中头部仍显示旧的消息数
      const c = conversations.value.find((x) => x.conversationId === convId);
      if (c) {
        c.messageCount += target.text ? 2 : 1;
        c.lastMessageAt = new Date().toISOString();
        if (c.title === "新对话") c.title = text.length > 20 ? text.slice(0, 20) + "..." : text;
      }
      // 再以后端为准刷新顺序与标题
      loadConversations();
    }
  }
};

/* ---------------- 初始化 ---------------- */

onMounted(async () => {
  try {
    const page = await agentApi.list({ pageNum: 1, pageSize: 100 });
    agents.value = page.list;
    selectedAgentId.value = page.list[0]?.agentId ?? null;
  } catch (e) {
    toast.error("加载 Agent 失败", (e as Error).message);
  }
  await loadConversations();
});
</script>

<style scoped>
/* 输入框融入胶囊输入栏 */
.composer::part(container) {
  background: transparent;
  box-shadow: none;
  border-radius: 0;
  min-height: 2.5rem;
}
.msg-enter-active {
  transition:
    opacity 0.25s ease,
    transform 0.3s cubic-bezier(0.2, 0, 0, 1);
}
.msg-enter-from {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}
</style>
