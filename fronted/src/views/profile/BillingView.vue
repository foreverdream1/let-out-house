<template>
  <div class="space-y-6">
    <!-- 时间范围 -->
    <header class="flex items-center justify-between gap-3">
      <div class="flex flex-wrap gap-2">
        <button
          v-for="r in ranges"
          :key="r.value"
          type="button"
          class="rounded-full px-3.5 py-1.5 text-sm transition-colors cursor-pointer"
          :class="
            range === r.value
              ? 'bg-primary-container text-on-primary-container'
              : 'border border-outline-variant text-on-surface-variant hover:bg-on-surface/[0.08]'
          "
          @click="selectRange(r.value)"
        >
          {{ r.label }}
        </button>
      </div>
      <mdui-button-icon icon="refresh" title="刷新" :disabled="loading" class="shrink-0" @click="loadAll"></mdui-button-icon>
    </header>

    <!-- 汇总 -->
    <section class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
      <SpotlightCard
        v-for="s in cards"
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
        <div class="mt-3 flex items-baseline gap-1 text-3xl sm:text-4xl font-semibold tabular-nums text-on-surface">
          <span v-if="loading" class="inline-block h-9 w-16 animate-pulse rounded-lg bg-surface-container-highest" />
          <!-- 汇总加载失败时显示占位符，避免把 0 误读成真实数据 -->
          <span v-else-if="!summary" class="text-2xl font-normal text-on-surface-variant">—</span>
          <template v-else>
            <CountUp :to="s.value" :duration="1.2" :decimals="s.decimals" class-name="tabular-nums" />
            <span v-if="s.unit" class="text-sm font-normal text-on-surface-variant">{{ s.unit }}</span>
          </template>
        </div>
      </SpotlightCard>
    </section>

    <!-- 说明：免费模型费用为 0 但照常计次 -->
    <p v-if="summary?.callCount" class="px-1 text-xs text-on-surface-variant">
      共 {{ summary.callCount }} 次计费调用；免费模型的费用为 0，但仍计入调用次数。
    </p>

    <!-- 明细 -->
    <section class="m3-card overflow-hidden">
      <div class="flex items-center justify-between px-5 pt-4 pb-2">
        <h2 class="text-sm font-medium text-on-surface-variant">
          用量明细
          <span v-if="total" class="ml-1 opacity-60">({{ total }})</span>
        </h2>
        <!-- 后端 detail 接口不支持时间过滤，说明一下避免误解顶部筛选也作用于明细 -->
        <span class="hidden text-xs text-on-surface-variant opacity-70 sm:inline">
          显示全部记录，不受上方时间筛选影响
        </span>
      </div>

      <div v-if="loading" class="space-y-2 p-5">
        <div v-for="i in 4" :key="i" class="h-12 animate-pulse rounded-2xl bg-surface-container-highest" />
      </div>

      <div v-else-if="!details.length" class="flex flex-col items-center gap-2 px-5 py-14 text-on-surface-variant">
        <mdui-icon name="receipt_long--outlined" class="text-4xl opacity-60"></mdui-icon>
        <span class="text-sm">该时间段没有调用记录</span>
      </div>

      <!-- 桌面表格 -->
      <div v-else class="overflow-x-auto">
        <table class="w-full min-w-[640px] text-sm">
          <thead>
            <tr class="text-left text-xs text-on-surface-variant">
              <th class="px-5 py-2 font-medium">时间</th>
              <th class="px-3 py-2 font-medium">模型</th>
              <th class="px-3 py-2 text-right font-medium">输入</th>
              <th class="px-3 py-2 text-right font-medium">输出</th>
              <th class="px-3 py-2 text-right font-medium">费用</th>
              <th class="px-5 py-2 text-right font-medium">会话</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="d in details"
              :key="d.id"
              class="border-t border-outline-variant/30 transition-colors hover:bg-on-surface/[0.04]"
            >
              <td class="whitespace-nowrap px-5 py-3 text-on-surface-variant">{{ fmtTime(d.createdAt) }}</td>
              <td class="px-3 py-3">
                <span class="rounded-full bg-secondary-container px-2 py-0.5 text-xs text-on-secondary-container">
                  {{ d.modelName }}
                </span>
              </td>
              <td class="px-3 py-3 text-right tabular-nums text-on-surface">{{ d.inputTokens }}</td>
              <td class="px-3 py-3 text-right tabular-nums text-on-surface">{{ d.outputTokens }}</td>
              <td class="px-3 py-3 text-right tabular-nums" :class="d.totalCost > 0 ? 'text-on-surface' : 'text-on-surface-variant'">
                {{ formatCost(d.totalCost) }}
              </td>
              <td class="px-5 py-3 text-right tabular-nums text-on-surface-variant">#{{ d.conversationId }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="details.length && total > details.length" class="flex justify-center px-5 py-3">
        <mdui-button variant="text" :loading="loadingMore" @click="loadMore">加载更多</mdui-button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import SpotlightCard from "../../components/SpotlightCard.vue";
import CountUp from "../../components/CountUp.vue";
import { useTheme } from "../../composables/useTheme";
import { toast } from "../../composables/useToast";
import {
  billingApi,
  formatBillingTime,
  type UsageDetail,
  type UsageSummary,
} from "../../api/billing";

const PAGE = 20;

type RangeKey = "today" | "7d" | "30d" | "all";

const ranges: { value: RangeKey; label: string }[] = [
  { value: "today", label: "今天" },
  { value: "7d", label: "近 7 天" },
  { value: "30d", label: "近 30 天" },
  { value: "all", label: "全部" },
];

const range = ref<RangeKey>("7d");
const summary = ref<UsageSummary | null>(null);
const details = ref<UsageDetail[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(true);
const loadingMore = ref(false);

const { palette } = useTheme();
const spotlight = computed(() => `${palette.value.primary}33`);

/** 后端要求 yyyy-MM-dd HH:mm:ss，左闭右开 */
const currentRange = (): { from?: string; to?: string } => {
  const now = new Date();
  if (range.value === "all") return {};

  if (range.value === "today") {
    const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    return { from: formatBillingTime(start) };
  }

  const days = range.value === "7d" ? 7 : 30;
  const start = new Date(now.getTime() - days * 24 * 60 * 60 * 1000);
  return { from: formatBillingTime(start) };
};

const cards = computed(() => [
  {
    label: "输入 Token",
    value: summary.value?.totalInputTokens ?? 0,
    decimals: 0,
    unit: "",
    icon: "arrow_downward",
    tone: "bg-primary-container text-on-primary-container",
  },
  {
    label: "输出 Token",
    value: summary.value?.totalOutputTokens ?? 0,
    decimals: 0,
    unit: "",
    icon: "arrow_upward",
    tone: "bg-tertiary-container text-on-tertiary-container",
  },
  {
    label: "调用次数",
    value: summary.value?.callCount ?? 0,
    decimals: 0,
    unit: "次",
    icon: "bolt",
    tone: "bg-secondary-container text-on-secondary-container",
  },
  {
    label: "总费用",
    // 6 位小数，按 4 位展示，避免四舍五入成 0
    value: summary.value?.totalCost ?? 0,
    decimals: 4,
    unit: "元",
    icon: "paid--outlined",
    tone: "bg-surface-container-highest text-on-surface",
  },
]);

const formatCost = (v: number) => (v > 0 ? `¥${v.toFixed(4)}` : "免费");

const fmtTime = (iso: string) => {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
};

/**
 * 请求序号：切换时间范围时会有多个 summary 请求在飞，
 * 没有这个的话旧请求可能后到并覆盖新结果（曾出现数字倒挂）。
 */
let summaryToken = 0;
let detailToken = 0;

const loadDetails = async (reset = true) => {
  const token = ++detailToken;
  if (reset) {
    loading.value = true;
    page.value = 1;
  } else {
    loadingMore.value = true;
  }
  try {
    const res = await billingApi.detail({ pageNum: page.value, pageSize: PAGE });
    if (token !== detailToken) return;
    details.value = reset ? res.list : [...details.value, ...res.list];
    total.value = res.total;
  } catch (e) {
    if (token === detailToken) toast.error("加载明细失败", (e as Error).message);
  } finally {
    if (token === detailToken) {
      loading.value = false;
      loadingMore.value = false;
    }
  }
};

const loadSummary = async () => {
  const token = ++summaryToken;
  try {
    const res = await billingApi.summary(currentRange());
    if (token !== summaryToken) return;
    summary.value = res;
  } catch (e) {
    if (token !== summaryToken) return;
    summary.value = null;
    toast.error("加载汇总失败", (e as Error).message);
  }
};

const loadAll = async () => {
  loading.value = true;
  await Promise.all([loadSummary(), loadDetails()]);
};

const loadMore = async () => {
  if (loadingMore.value || details.value.length >= total.value) return;
  page.value += 1;
  await loadDetails(false);
};

const selectRange = (r: RangeKey) => {
  if (range.value === r) return;
  range.value = r;
  loadAll();
};

onMounted(loadAll);
</script>
