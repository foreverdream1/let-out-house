<template>
  <div class="space-y-6">
    <!-- 顶部 -->
    <header class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <p class="page-subtitle !mt-0">配置模型、参数与知识库关联。</p>
      <mdui-button icon="add" class="self-start sm:self-auto" @click="createNew">新建 Agent</mdui-button>
    </header>

    <!-- 支持的模型厂商 -->
    <section class="m3-card overflow-hidden py-4">
      <div class="mb-3 px-5 text-xs font-medium text-on-surface-variant">支持的模型厂商</div>
      <LogoLoop
        :logos="vendorLogos"
        :speed="55"
        :logo-height="36"
        :gap="56"
        fade-out
        :fade-out-color="fadeColor"
        scale-on-hover
        aria-label="支持的模型厂商"
      >
        <template #renderItem="{ item }">
          <img
            v-if="toVendor(item)"
            :src="toVendor(item)!.src"
            :alt="toVendor(item)!.alt"
            :title="toVendor(item)!.alt"
            class="h-[var(--logoloop-logoHeight)] w-auto object-contain"
            :style="toVendor(item)!.mono && isDark ? 'filter: invert(1)' : undefined"
          />
        </template>
      </LogoLoop>
    </section>

    <div class="grid grid-cols-1 items-start gap-6 lg:grid-cols-[340px_1fr]">
      <!-- 列表 -->
      <aside class="m3-card overflow-hidden lg:sticky lg:top-0">
        <div class="p-4">
          <mdui-text-field
            variant="outlined"
            label="搜索 Agent"
            clearable
            :value="keyword"
            @input="onSearch"
          >
            <mdui-icon slot="icon" name="search"></mdui-icon>
          </mdui-text-field>
        </div>

        <div v-if="loading" class="space-y-2 px-4 pb-4">
          <div v-for="i in 4" :key="i" class="h-14 animate-pulse rounded-2xl bg-surface-container-highest" />
        </div>
        <div v-else-if="!agents.length" class="flex flex-col items-center gap-2 px-4 py-10 text-on-surface-variant">
          <mdui-icon name="smart_toy--outlined" class="text-4xl opacity-60"></mdui-icon>
          <span class="text-sm">暂无 Agent</span>
        </div>
        <mdui-list v-else>
          <mdui-list-item
            v-for="a in agents"
            :key="a.agentId"
            rounded
            :headline="a.agentName"
            :description="a.modelName"
            :active="editingId === a.agentId"
            @click="startEdit(a.agentId)"
          >
            <mdui-icon
              slot="icon"
              :name="a.ragEnable ? 'menu_book--outlined' : 'smart_toy--outlined'"
            ></mdui-icon>
          </mdui-list-item>
        </mdui-list>

        <div class="flex items-center justify-between border-t border-outline-variant/40 px-4 py-2 text-sm">
          <span class="text-on-surface-variant">共 {{ total }} 条</span>
          <div class="flex items-center gap-1">
            <mdui-button-icon icon="chevron_left" :disabled="pageNum <= 1" @click="goto(pageNum - 1)"></mdui-button-icon>
            <span class="min-w-8 text-center tabular-nums text-on-surface-variant">{{ pageNum }}</span>
            <mdui-button-icon
              icon="chevron_right"
              :disabled="pageNum * pageSize >= total"
              @click="goto(pageNum + 1)"
            ></mdui-button-icon>
          </div>
        </div>
      </aside>

      <!-- 表单 -->
      <section ref="formRef" class="m3-card scroll-mt-4 space-y-5 p-5 sm:p-6">
        <div class="flex items-center justify-between gap-3">
          <div class="flex min-w-0 items-center gap-3">
            <span class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary-container text-on-primary-container">
              <mdui-icon :name="editingId === null ? 'add' : 'edit--outlined'"></mdui-icon>
            </span>
            <div class="min-w-0">
              <div class="truncate font-medium text-on-surface">
                {{ editingId === null ? "新建 Agent" : form.agentName || "编辑 Agent" }}
              </div>
              <div class="text-xs text-on-surface-variant">
                {{ editingId === null ? "填写下方信息创建新的 Agent" : "ID #" + editingId }}
              </div>
            </div>
          </div>
          <mdui-button
            v-if="editingId !== null"
            variant="text"
            icon="delete--outlined"
            class="text-error"
            @click="removeAgent(editingId)"
          >
            删除
          </mdui-button>
        </div>

        <div class="grid grid-cols-1 gap-5 md:grid-cols-2">
          <mdui-text-field
            variant="outlined"
            label="Agent 名称"
            required
            :value="form.agentName"
            @input="form.agentName = $event.target.value"
          ></mdui-text-field>

          <mdui-select
            variant="outlined"
            label="模型"
            :value="form.modelName"
            @change="form.modelName = $event.target.value"
          >
            <mdui-menu-item v-for="m in models" :key="m.value" :value="m.value">
              {{ m.label }}
            </mdui-menu-item>
          </mdui-select>
        </div>

        <mdui-text-field
          variant="outlined"
          label="描述"
          maxlength="512"
          counter
          :value="form.description"
          @input="form.description = $event.target.value"
        ></mdui-text-field>

        <mdui-text-field
          variant="outlined"
          label="系统提示词"
          type="textarea"
          rows="6"
          required
          :value="form.systemPrompt"
          @input="form.systemPrompt = $event.target.value"
        ></mdui-text-field>

        <label
          class="flex cursor-pointer items-center justify-between gap-4 rounded-2xl bg-surface-container-high px-4 py-3"
        >
          <div>
            <div class="text-sm font-medium text-on-surface">启用知识库检索 (RAG)</div>
            <div class="text-xs text-on-surface-variant">开启后回答会先检索关联的知识库</div>
          </div>
          <mdui-switch
            :checked="form.ragEnable"
            @change="form.ragEnable = $event.target.checked"
          ></mdui-switch>
        </label>

        <div class="grid grid-cols-1 gap-x-8 gap-y-4 rounded-2xl bg-surface-container-high px-4 py-4 md:grid-cols-2">
          <NumberSlider
            :model-value="form.temperature ?? 0.7"
            @update:model-value="form.temperature = $event"
            label="Temperature"
            :min="0"
            :max="2"
            :step="0.05"
            :decimals="2"
            hint="越高越发散，越低越稳定（0.00 – 2.00）"
          />
          <NumberSlider
            :model-value="form.maxToken ?? 2048"
            @update:model-value="form.maxToken = $event"
            label="Max Token"
            :min="1"
            :max="32000"
            :step="256"
            is-stepped
            hint="单次回答的最大长度"
          />
          <NumberSlider
            v-if="form.ragEnable"
            :model-value="form.topK ?? 3"
            @update:model-value="form.topK = $event"
            label="Top K"
            :min="1"
            :max="20"
            :step="1"
            is-stepped
            hint="检索返回的文档条数"
          />
        </div>

        <mdui-text-field
          v-if="form.ragEnable"
          variant="outlined"
          label="关联知识库 ID"
          placeholder="多个用英文逗号分隔，如 1,2"
          :value="kbInput"
          helper="后端暂未提供知识库列表接口，先手动填写"
          @input="onKbInput"
        ></mdui-text-field>

        <div class="flex flex-col-reverse gap-3 pt-2 sm:flex-row sm:justify-end">
          <mdui-button variant="outlined" @click="startCreate">重置</mdui-button>
          <mdui-button :loading="saving" icon="check" @click="save">保存</mdui-button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { confirm } from "mdui";
import LogoLoop from "../../components/LogoLoop.vue";
import NumberSlider from "../../components/NumberSlider.vue";
import { agentApi, type AgentSaveRequest } from "../../api/agent";

import qwenLogo from "../../assets/logos/qwen-color.svg";
import deepseekLogo from "../../assets/logos/deepseek-color.svg";
import zhipuLogo from "../../assets/logos/zhipu-color.svg";
import moonshotLogo from "../../assets/logos/moonshot.svg";
import baiduLogo from "../../assets/logos/baidu-color.svg";
import bytedanceLogo from "../../assets/logos/bytedance-color.svg";
import tencentLogo from "../../assets/logos/tencent-color.svg";
import openaiLogo from "../../assets/logos/openai.svg";
import anthropicLogo from "../../assets/logos/anthropic.svg";
import googleLogo from "../../assets/logos/google-color.svg";
import mistralLogo from "../../assets/logos/mistral-color.svg";
import ollamaLogo from "../../assets/logos/ollama.svg";
import minimaxLogo from "../../assets/logos/minimax.svg";
import stepfunLogo from "../../assets/logos/stepfun.svg";
import hunyuanLogo from "../../assets/logos/hunyuan.svg";
import { toast } from "../../composables/useToast";
import { useTheme } from "../../composables/useTheme";

// 明暗模式来自全局主题，单色厂商图标在夜间模式下反色
const { isDark } = useTheme();
const formRef = ref<HTMLElement | null>(null);

// 窄屏下列表和表单上下排列，切换编辑对象时把表单滚动到可视区
const revealForm = () => {
  if (window.matchMedia("(max-width: 1199px)").matches) {
    formRef.value?.scrollIntoView({ behavior: "smooth", block: "start" });
  }
};

// 轮播两端渐隐色跟随主题，避免硬编码背景色
const fadeColor = "rgb(var(--mdui-color-surface-container))";

const models = [
  { label: "通义千问 qwen-plus", value: "qwen-plus" },
  { label: "通义千问 qwen-turbo", value: "qwen-turbo" },
  { label: "通义千问 qwen-max", value: "qwen-max" },
  { label: "DeepSeek chat", value: "deepseek-chat" },
  { label: "GLM-4", value: "glm-4" },
];

// AI 厂商图标来自 @lobehub/icons（静态 SVG），已下载到 src/assets/logos
// mono = true 表示图标是单色（currentColor），夜间模式下反转为白色
interface VendorLogo {
  src: string;
  alt: string;
  mono: boolean;
}

const toVendor = (item: unknown) => item as VendorLogo;

const vendorLogos: VendorLogo[] = [
  { src: qwenLogo, alt: "通义千问", mono: false },
  { src: deepseekLogo, alt: "DeepSeek", mono: false },
  { src: zhipuLogo, alt: "智谱 GLM", mono: false },
  { src: baiduLogo, alt: "文心一言", mono: false },
  { src: bytedanceLogo, alt: "豆包", mono: false },
  { src: tencentLogo, alt: "腾讯混元", mono: false },
  { src: googleLogo, alt: "Gemini", mono: false },
  { src: mistralLogo, alt: "Mistral", mono: false },
  { src: moonshotLogo, alt: "Moonshot", mono: true },
  { src: openaiLogo, alt: "OpenAI", mono: true },
  { src: anthropicLogo, alt: "Anthropic", mono: true },
  { src: ollamaLogo, alt: "Ollama", mono: true },
  { src: minimaxLogo, alt: "MiniMax", mono: true },
  { src: stepfunLogo, alt: "阶跃星辰", mono: true },
  { src: hunyuanLogo, alt: "混元", mono: true },
];

const emptyForm = (): AgentSaveRequest => ({
  agentName: "",
  description: "",
  systemPrompt: "",
  modelName: "qwen-plus",
  ragEnable: true,
  maxToken: 2048,
  temperature: 0.7,
  topK: 3,
  knowledgeBaseIds: [],
});

const form = reactive<AgentSaveRequest>(emptyForm());
const kbInput = ref("");
const agents = ref<{ agentId: number; agentName: string; modelName: string; ragEnable?: boolean }[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = 10;
const keyword = ref("");
const loading = ref(false);
const saving = ref(false);
const editingId = ref<number | null>(null);

let searchTimer = 0;
const onSearch = (e: Event) => {
  keyword.value = (e.target as HTMLInputElement).value;
  clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => {
    pageNum.value = 1;
    loadList();
  }, 300);
};

const loadList = async () => {
  loading.value = true;
  try {
    const page = await agentApi.list({
      pageNum: pageNum.value,
      pageSize,
      keyword: keyword.value || undefined,
    });
    agents.value = page.list;
    total.value = page.total;
  } catch (e) {
    toast.error("加载列表失败", (e as Error).message);
  } finally {
    loading.value = false;
  }
};

const goto = (p: number) => {
  pageNum.value = p;
  loadList();
};

const startCreate = () => {
  editingId.value = null;
  Object.assign(form, emptyForm());
  kbInput.value = "";
};

const createNew = () => {
  startCreate();
  revealForm();
};

const startEdit = async (id: number) => {
  try {
    const a = await agentApi.detail(id);
    editingId.value = id;
    Object.assign(form, {
      agentName: a.agentName,
      description: a.description || "",
      systemPrompt: a.systemPrompt,
      modelName: a.modelName,
      ragEnable: a.ragEnable ?? true,
      maxToken: a.maxToken ?? 2048,
      temperature: Number(a.temperature ?? 0.7),
      topK: a.topK ?? 3,
      knowledgeBaseIds: a.knowledgeBaseIds || [],
    });
    kbInput.value = (a.knowledgeBaseIds || []).join(",");
    revealForm();
  } catch (e) {
    toast.error("读取详情失败", (e as Error).message);
  }
};

const onKbInput = (e: Event) => {
  kbInput.value = (e.target as HTMLInputElement).value;
  form.knowledgeBaseIds = kbInput.value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map(Number)
    .filter((n) => !Number.isNaN(n));
};

const save = async () => {
  if (!form.agentName?.trim()) return toast.error("校验失败", "Agent 名称不能为空");
  if (!form.systemPrompt?.trim()) return toast.error("校验失败", "系统提示词不能为空");
  if (!form.modelName) return toast.error("校验失败", "请选择模型");

  saving.value = true;
  try {
    const payload: AgentSaveRequest = {
      ...form,
      temperature: Number(Number(form.temperature).toFixed(2)),
      maxToken: Math.round(Number(form.maxToken)),
      topK: Math.round(Number(form.topK)),
    };
    if (editingId.value === null) {
      await agentApi.create(payload);
      toast.success("创建成功", payload.agentName);
    } else {
      await agentApi.update(editingId.value, payload);
      toast.success("保存成功", payload.agentName);
    }
    await loadList();
    if (editingId.value === null) startCreate();
  } catch (e) {
    toast.error("保存失败", (e as Error).message);
  } finally {
    saving.value = false;
  }
};

const removeAgent = async (id: number) => {
  try {
    await confirm({
      headline: "删除 Agent",
      description: `确定删除「${form.agentName || "#" + id}」吗？此操作不可撤销。`,
      confirmText: "删除",
      cancelText: "取消",
      icon: "delete--outlined",
    });
  } catch {
    return; // 用户取消
  }
  try {
    await agentApi.remove(id);
    toast.success("已删除");
    if (editingId.value === id) startCreate();
    await loadList();
  } catch (e) {
    toast.error("删除失败", (e as Error).message);
  }
};

onMounted(loadList);
</script>

