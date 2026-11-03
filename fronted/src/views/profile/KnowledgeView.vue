<template>
  <div class="space-y-6">
    <!-- 顶部：知识库 ID 输入 -->
    <header class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div class="min-w-0 flex-1 sm:max-w-xs">
        <mdui-text-field
          variant="outlined"
          label="知识库 ID"
          placeholder="如 1"
          :value="kbInput"
          :disabled="loading"
          @input="kbInput = $event.target.value"
          @keydown.enter.prevent="loadDocuments"
        >
          <mdui-icon slot="icon" name="folder--outlined"></mdui-icon>
        </mdui-text-field>
      </div>
      <p class="page-subtitle !mt-0 hidden flex-1 sm:block">
        管理该知识库的文档，上传后会解析、切片并写入向量库。
      </p>
      <mdui-button
        icon="refresh"
        :loading="loading"
        :disabled="!kbId"
        class="self-start sm:self-auto"
        @click="loadDocuments"
      >
        刷新
      </mdui-button>
    </header>

    <!-- 未选择知识库 -->
    <section v-if="!kbId" class="m3-card flex flex-col items-center gap-3 px-5 py-16 text-on-surface-variant">
      <mdui-icon name="folder_open" class="text-5xl opacity-50"></mdui-icon>
      <span class="text-sm">输入知识库 ID 后加载文档</span>
      <span class="text-xs opacity-60">知识库 ID 可在 Agent 配置页的「关联知识库」里查看</span>
    </section>

    <div v-else class="grid grid-cols-1 items-start gap-6 lg:grid-cols-[360px_1fr]">
      <!-- 上传区 -->
      <section class="m3-card p-4 lg:sticky lg:top-0">
        <div class="mb-3 text-xs font-medium text-primary">上传文档</div>

        <div
          class="flex flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed px-4 py-10 text-center transition-colors"
          :class="
            dragging
              ? 'border-primary bg-primary-container/40'
              : 'border-outline-variant/60 hover:border-primary/60'
          "
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
        >
          <mdui-icon name="cloud_upload--outlined" class="text-4xl text-primary"></mdui-icon>
          <span class="text-sm text-on-surface">拖拽文件到此处</span>
          <span class="text-xs text-on-surface-variant">或</span>
          <mdui-button variant="tonal" :disabled="uploading" @click="pickFile">选择文件</mdui-button>
          <span class="mt-1 text-xs text-on-surface-variant">支持 pdf / docx / md / txt，单文件 ≤ 20MB</span>
          <input
            ref="fileRef"
            type="file"
            class="hidden"
            accept=".pdf,.docx,.md,.txt"
            @change="onPick"
          />
        </div>

        <!-- 上传进度 -->
        <div v-if="uploading" class="mt-4">
          <div class="mb-1 flex items-center justify-between text-xs text-on-surface-variant">
            <span class="min-w-0 truncate">{{ uploadingName }}</span>
            <span class="shrink-0 tabular-nums">{{ progress < 100 ? progress + "%" : "解析中…" }}</span>
          </div>
          <mdui-linear-progress
            :value="progress / 100"
            :indeterminate="progress >= 100"
          ></mdui-linear-progress>
          <p class="mt-2 text-xs text-on-surface-variant">
            解析 + 切片 + 向量化在后端同步完成，文件较大时需要等待一会。
          </p>
        </div>
      </section>

      <!-- 文档列表 -->
      <section class="m3-card overflow-hidden">
        <div class="flex items-center justify-between px-5 pt-4">
          <h2 class="text-sm font-medium text-on-surface-variant">
            文档列表
            <span v-if="total" class="ml-1 opacity-60">({{ total }})</span>
          </h2>
          <mdui-button-icon icon="refresh" title="刷新" :disabled="loading" @click="loadDocuments"></mdui-button-icon>
        </div>

        <div v-if="loading" class="space-y-2 p-5">
          <div v-for="i in 3" :key="i" class="h-16 animate-pulse rounded-2xl bg-surface-container-highest" />
        </div>

        <div v-else-if="!documents.length" class="flex flex-col items-center gap-2 px-5 py-14 text-on-surface-variant">
          <mdui-icon name="description--outlined" class="text-4xl opacity-60"></mdui-icon>
          <span class="text-sm">还没有文档，先上传一个吧</span>
        </div>

        <ul v-else class="divide-y divide-outline-variant/30">
          <li v-for="d in documents" :key="d.id" class="group flex items-start gap-3 px-5 py-3">
            <span
              class="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl"
              :class="statusTone(d.parseStatus).icon"
            >
              <mdui-icon :name="fileIcon(d.fileType)" class="text-[20px]"></mdui-icon>
            </span>

            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2">
                <span class="min-w-0 flex-1 truncate text-sm text-on-surface" :title="d.docName">
                  {{ d.docName }}
                </span>
                <span
                  class="shrink-0 rounded-full px-2 py-0.5 text-[11px]"
                  :class="statusTone(d.parseStatus).badge"
                >
                  {{ statusLabel(d) }}
                </span>
              </div>

              <div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-on-surface-variant">
                <span class="uppercase">{{ d.fileType }}</span>
                <span>{{ formatSize(d.fileSize) }}</span>
                <span v-if="d.parseStatus === 2">{{ d.chunkCount }} 个片段</span>
                <span>{{ fmtTime(d.createdAt) }}</span>
              </div>

              <!-- 解析失败：失败原因不是接口错误，单独展示 -->
              <p
                v-if="d.parseStatus === 3 && d.failReason"
                class="mt-1.5 rounded-lg bg-error-container px-2 py-1 text-xs text-on-error-container"
              >
                {{ d.failReason }}
              </p>
            </div>

            <mdui-button-icon
              icon="delete--outlined"
              title="删除文档"
              class="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
              @click="removeDocument(d)"
            ></mdui-button-icon>
          </li>
        </ul>

        <div v-if="documents.length && total > documents.length" class="flex justify-center px-5 py-3">
          <mdui-button variant="text" :loading="loadingMore" @click="loadMore">加载更多</mdui-button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { confirm } from "mdui";
import {
  knowledgeApi,
  ALLOWED_EXTENSIONS,
  MAX_FILE_SIZE,
  PARSE_STATUS,
  type DocumentResponse,
} from "../../api/knowledge";
import { toast } from "../../composables/useToast";

const KB_KEY = "knowledgeLastKbId";
const PAGE = 20;

const kbInput = ref(localStorage.getItem(KB_KEY) || "");
const kbId = ref<number | null>(null);

const documents = ref<DocumentResponse[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const loadingMore = ref(false);

const fileRef = ref<HTMLInputElement | null>(null);
const dragging = ref(false);
const uploading = ref(false);
const uploadingName = ref("");
const progress = ref(0);

const statusLabel = (d: DocumentResponse) => {
  switch (d.parseStatus) {
    case PARSE_STATUS.PENDING:
      return "待解析";
    case PARSE_STATUS.PARSING:
      return "解析中";
    case PARSE_STATUS.DONE:
      return "已完成";
    case PARSE_STATUS.FAILED:
      return "失败";
    default:
      return "未知";
  }
};

const statusTone = (s: number) => {
  switch (s) {
    case PARSE_STATUS.DONE:
      // 完成用 primary（主题强调色），避免 tertiary 在某些种子色下偏红被误读为失败
      return { icon: "bg-primary-container text-on-primary-container", badge: "bg-primary-container text-on-primary-container" };
    case PARSE_STATUS.FAILED:
      return { icon: "bg-error-container text-on-error-container", badge: "bg-error-container text-on-error-container" };
    case PARSE_STATUS.PARSING:
      return { icon: "bg-secondary-container text-on-secondary-container", badge: "bg-secondary-container text-on-secondary-container" };
    default:
      return { icon: "bg-surface-container-highest text-on-surface-variant", badge: "bg-surface-container-highest text-on-surface-variant" };
  }
};

const fileIcon = (type: string) => {
  const t = (type || "").toLowerCase();
  if (t === "pdf") return "picture_as_pdf--outlined";
  if (t === "docx" || t === "doc") return "description--outlined";
  if (t === "md") return "code--outlined";
  return "article--outlined";
};

const formatSize = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
};

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

/* ---------------- 加载 ---------------- */

/** 请求序号，用于丢弃过期响应 */
let loadToken = 0;

const loadDocuments = async (reset = true) => {
  const id = Number(kbInput.value.trim());
  if (!id || !Number.isInteger(id) || id <= 0) {
    toast.error("知识库 ID 无效", "请输入正整数");
    return;
  }

  if (reset) {
    loading.value = true;
    page.value = 1;
  } else {
    loadingMore.value = true;
  }

  const token = ++loadToken;
  try {
    const res = await knowledgeApi.list(id, { pageNum: page.value, pageSize: PAGE });
    if (token !== loadToken) return;
    kbId.value = id;
    localStorage.setItem(KB_KEY, String(id));
    documents.value = reset ? res.list : [...documents.value, ...res.list];
    total.value = res.total;
  } catch (e) {
    if (token !== loadToken) return;
    // 列表失败通常是 ID 不存在，清掉已加载的旧数据避免误导
    if (reset) {
      kbId.value = null;
      documents.value = [];
      total.value = 0;
    }
    toast.error("加载文档失败", (e as Error).message);
  } finally {
    if (token === loadToken) {
      loading.value = false;
      loadingMore.value = false;
    }
  }
};

const loadMore = async () => {
  if (loadingMore.value || documents.value.length >= total.value) return;
  page.value += 1;
  await loadDocuments(false);
};

/* ---------------- 上传 ---------------- */

const pickFile = () => fileRef.value?.click();

const onPick = (e: Event) => {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) upload(file);
  // 允许重复选择同一个文件（同名覆盖场景）
  input.value = "";
};

const onDrop = (e: DragEvent) => {
  dragging.value = false;
  const file = e.dataTransfer?.files?.[0];
  if (file) upload(file);
};

/** 前端先挡一遍，避免白等一次长达数分钟的同步解析 */
const validate = (file: File): string | null => {
  const ext = file.name.split(".").pop()?.toLowerCase() || "";
  if (!ALLOWED_EXTENSIONS.includes(ext)) {
    return `不支持的文件类型 .${ext}，仅支持 ${ALLOWED_EXTENSIONS.join(" / ")}`;
  }
  if (file.size === 0) return "文件为空";
  if (file.size > MAX_FILE_SIZE) {
    return `文件过大（${formatSize(file.size)}），上限 20MB`;
  }
  return null;
};

const upload = async (file: File) => {
  if (!kbId.value) {
    toast.error("请先加载知识库");
    return;
  }
  if (uploading.value) return;

  const err = validate(file);
  if (err) {
    toast.error("无法上传", err);
    return;
  }

  uploading.value = true;
  uploadingName.value = file.name;
  progress.value = 0;

  try {
    const doc = await knowledgeApi.upload(kbId.value, file, (p) => (progress.value = p));

    // 解析失败时 code 仍是 0，必须看 parseStatus
    if (doc.parseStatus === PARSE_STATUS.FAILED) {
      toast.error("解析失败", doc.failReason || doc.docName);
    } else if (doc.parseStatus === PARSE_STATUS.DONE) {
      toast.success("上传成功", `已入库 ${doc.chunkCount} 个片段`);
    } else {
      // 新版是异步解析（Java 投队列 / Python 消费），上传后一般停在"待解析"或"解析中"，
      // 所以这里不能只提示一次，要轮询到终态
      toast.info("已提交", "文档正在解析，完成后会自动刷新");
      pollParseStatus(doc.id);
    }

    await loadDocuments();
  } catch (e) {
    toast.error("上传失败", (e as Error).message);
  } finally {
    uploading.value = false;
    uploadingName.value = "";
    progress.value = 0;
  }
};

/** 轮询刚上传的文档直到进入终态（完成 / 失败），最多等 3 分钟 */
let pollTimer: ReturnType<typeof setTimeout> | null = null;

const pollParseStatus = (docId: number, attempt = 0) => {
  if (pollTimer) clearTimeout(pollTimer);
  const MAX_ATTEMPTS = 60; // 60 * 3s = 3 分钟
  const id = kbId.value;
  if (!id) return;

  const tick = async () => {
    if (attempt >= MAX_ATTEMPTS) {
      toast.info("解析超时", "文档仍在处理中，可稍后手动刷新查看");
      return;
    }
    try {
      const res = await knowledgeApi.list(id, { pageNum: 1, pageSize: PAGE });
      if (kbId.value !== id) return; // 期间切换了知识库
      const latest = res.list.find((d) => d.id === docId);
      if (latest) {
        documents.value = res.list;
        total.value = res.total;
        if (latest.parseStatus === PARSE_STATUS.DONE) {
          toast.success("解析完成", `${latest.docName}：${latest.chunkCount} 个片段`);
          return;
        }
        if (latest.parseStatus === PARSE_STATUS.FAILED) {
          toast.error("解析失败", latest.failReason || latest.docName);
          return;
        }
      }
    } catch {
      // 轮询期间的网络抖动忽略，下一轮再试
    }
    pollTimer = setTimeout(tick, 3000);
    attempt++;
  };

  pollTimer = setTimeout(tick, 2000);
};

/* ---------------- 删除 ---------------- */

const removeDocument = async (d: DocumentResponse) => {
  if (!kbId.value) return;
  try {
    await confirm({
      headline: "删除文档",
      description: `确定删除「${d.docName}」吗？该文档的片段与向量会一并清除，之后不再被检索命中。`,
      confirmText: "删除",
      cancelText: "取消",
      icon: "delete--outlined",
    });
  } catch {
    return; // 用户取消
  }

  try {
    await knowledgeApi.remove(kbId.value, d.id);
    toast.success("已删除", d.docName);
    // 删掉当前页最后一条时回退一页，避免出现空页
    if (documents.value.length === 1 && page.value > 1) page.value -= 1;
    await loadDocuments();
  } catch (e) {
    toast.error("删除失败", (e as Error).message);
  }
};

const canLoad = computed(() => !!kbInput.value.trim());

onMounted(() => {
  if (canLoad.value) loadDocuments();
});

onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer);
});
</script>
