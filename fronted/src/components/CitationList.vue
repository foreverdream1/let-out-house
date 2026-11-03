<template>
  <!-- citations 为 null 时父组件不渲染；这里只处理 [] 和 [...] 两种情况 -->
  <div v-if="citations" class="mt-2">
    <!-- 未命中：轻量提示，不占视觉重量 -->
    <div
      v-if="!citations.length"
      class="inline-flex items-center gap-1.5 rounded-full bg-surface-container-high px-2.5 py-1 text-xs text-on-surface-variant"
    >
      <mdui-icon name="search_off" class="text-[14px]"></mdui-icon>
      未检索到相关文档
    </div>

    <template v-else>
      <!-- 角标行：点击展开/收起来源列表 -->
      <button
        type="button"
        class="inline-flex flex-wrap items-center gap-1.5 cursor-pointer"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        <span
          v-for="c in citations"
          :key="c.index"
          class="inline-flex h-5 min-w-5 items-center justify-center rounded-md px-1 text-[11px] font-medium tabular-nums"
          :class="tone(c.score)"
          :title="c.documentName"
        >
          {{ c.index }}
        </span>
        <span class="ml-1 inline-flex items-center gap-0.5 text-xs text-on-surface-variant">
          {{ citations.length }} 条引用
          <mdui-icon
            :name="expanded ? 'expand_less' : 'expand_more'"
            class="text-[16px]"
          ></mdui-icon>
        </span>
      </button>

      <Transition name="cite">
        <ul v-if="expanded" class="mt-2 space-y-2">
          <li
            v-for="c in citations"
            :key="c.index"
            class="rounded-2xl border border-outline-variant/40 bg-surface-container p-3"
          >
            <div class="flex items-center gap-2">
              <span
                class="inline-flex h-5 min-w-5 shrink-0 items-center justify-center rounded-md px-1 text-[11px] font-medium tabular-nums"
                :class="tone(c.score)"
              >
                {{ c.index }}
              </span>
              <span class="min-w-0 flex-1 truncate text-sm text-on-surface" :title="c.documentName">
                {{ c.documentName || "未命名文档" }}
              </span>
              <span class="shrink-0 text-xs tabular-nums text-on-surface-variant">
                {{ c.score.toFixed(2) }}
              </span>
            </div>
            <p class="selectable mt-2 text-xs leading-relaxed text-on-surface-variant">
              {{ c.snippet }}
            </p>
          </li>
        </ul>
      </Transition>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import type { Citation } from "../api/conversation";

defineProps<{ citations: Citation[] | null }>();

const expanded = ref(false);

/** 相关度分档上色，全部复用主题令牌，不引入新色值 */
const tone = (score: number) => {
  if (score >= 0.75) return "bg-tertiary-container text-on-tertiary-container";
  if (score >= 0.5) return "bg-primary-container text-on-primary-container";
  return "bg-secondary-container text-on-secondary-container";
};
</script>

<style scoped>
.cite-enter-active,
.cite-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.22s cubic-bezier(0.2, 0, 0, 1);
}
.cite-enter-from,
.cite-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
