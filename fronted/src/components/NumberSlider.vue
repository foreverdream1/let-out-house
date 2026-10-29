<template>
  <div class="w-full">
    <div class="flex items-baseline justify-between gap-3 mb-1">
      <span class="text-sm opacity-70">{{ label }}</span>

      <!-- 平时显示数值，点击后变成输入框，可直接键入精确值 -->
      <input
        v-if="editing"
        ref="inputRef"
        v-model="draft"
        type="text"
        inputmode="decimal"
        class="w-20 rounded-md border border-primary bg-surface px-1.5 py-0.5 text-right text-sm font-mono tabular-nums text-on-surface outline-none"
        @keydown.enter.prevent="commit"
        @keydown.esc.prevent="cancel"
        @blur="commit"
      />
      <button
        v-else
        type="button"
        class="rounded-md px-1.5 py-0.5 text-sm font-mono tabular-nums text-on-surface transition-colors hover:bg-on-surface/[0.08] cursor-pointer"
        :title="`点击输入精确值（${min} – ${max}）`"
        @click="startEdit"
      >
        {{ display }}
      </button>
    </div>

    <ElasticSlider
      ref="sliderRef"
      :default-value="modelValue"
      :starting-value="min"
      :max-value="max"
      :is-stepped="isStepped"
      :step-size="step"
      :left-icon="'−'"
      :right-icon="'+'"
      class="!w-full"
    />
    <div v-if="hint" class="text-xs opacity-40 -mt-2">{{ hint }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted, onBeforeUnmount } from "vue";
import ElasticSlider from "./ElasticSlider.vue";

const props = withDefaults(
  defineProps<{
    modelValue: number;
    min?: number;
    max?: number;
    step?: number;
    isStepped?: boolean;
    label?: string;
    hint?: string;
    decimals?: number;
  }>(),
  { min: 0, max: 100, step: 1, isStepped: false, label: "", hint: "", decimals: 0 }
);

const emit = defineEmits<{ "update:modelValue": [number] }>();

const sliderRef = ref<InstanceType<typeof ElasticSlider> | null>(null);
const inputRef = ref<HTMLInputElement | null>(null);

const editing = ref(false);
const draft = ref("");
let raf = 0;
let last = props.modelValue;

const display = computed(() => Number(props.modelValue).toFixed(props.decimals));

const clamp = (v: number) => Math.min(Math.max(v, props.min), props.max);

const startEdit = async () => {
  draft.value = String(props.modelValue);
  editing.value = true;
  await nextTick();
  inputRef.value?.focus();
  inputRef.value?.select();
};

const cancel = () => {
  editing.value = false;
  draft.value = "";
};

// 回车或失焦时提交：非法输入直接丢弃，超范围则钳制到边界。
// 手动输入不做步长吸附——用户想要 3000 就是 3000，不该被凑成 3072；
// 步长只在拖动滑块时生效。
const commit = () => {
  if (!editing.value) return;
  const raw = draft.value.trim();
  const n = Number(raw);
  if (raw === "" || !Number.isFinite(n)) return cancel();

  const rounded = props.decimals > 0
    ? Number(clamp(n).toFixed(props.decimals))
    : Math.round(clamp(n));

  last = rounded;
  emit("update:modelValue", rounded);
  editing.value = false;
  draft.value = "";
};

// ElasticSlider 内部只更新自己的 value，这里轮询同步回父组件。
// 注意：手动输入的值可能不是步长的整数倍（如 MaxToken 输入 3000），
// 所以 emit 出去的值绝不能再用步长吸附，否则会被 tick 改写成 3072。
const tick = () => {
  const v = sliderRef.value?.value;
  if (typeof v === "number" && !editing.value) {
    const next = props.decimals > 0 ? Number(v.toFixed(props.decimals)) : Math.round(v);
    if (next !== last) {
      last = next;
      emit("update:modelValue", next);
    }
  }
  raf = requestAnimationFrame(tick);
};

watch(
  () => props.modelValue,
  (v) => {
    last = v;
  }
);

onMounted(() => {
  raf = requestAnimationFrame(tick);
});
onBeforeUnmount(() => cancelAnimationFrame(raf));
</script>
