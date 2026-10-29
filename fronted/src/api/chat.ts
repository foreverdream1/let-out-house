/**
 * SSE 流式对话客户端。
 *
 * 不能用 EventSource：它不支持 POST 和自定义 Header。
 * 也不能用 axios：它会把整个响应体缓冲完再交付，拿不到增量。
 * 所以这里用 fetch + ReadableStream 手动解析。
 *
 * 事件协议（后端 ChatStreamServiceImpl 会原样透传上游字节）：
 *   data: {"type":"chunk","content":"单字符","traceId":"..."}\n\n
 *   data: {"type":"end","fullContent":"...","tokenUsage":{"input":n,"output":n}}\n\n
 *   data: {"type":"error","content":"...","traceId":"..."}\n\n
 *
 * 注意两个容易踩的点：
 * 1. 首包可能不是 SSE 而是 JSON（503 AI服务暂不可用 / 404 会话不存在），
 *    这时 HTTP 状态码依然是 200，必须靠 Content-Type 判断。
 * 2. 多字节 UTF-8 字符可能被拆到两个 chunk 里，必须用 { stream: true } 解码。
 */

export interface ChatChunkEvent {
  type: "chunk";
  content: string;
  traceId?: string;
}

export interface ChatEndEvent {
  type: "end";
  fullContent?: string;
  tokenUsage?: { input: number; output: number };
  traceId?: string;
}

export interface ChatErrorEvent {
  type: "error";
  content: string;
  traceId?: string;
}

export type ChatEvent = ChatChunkEvent | ChatEndEvent | ChatErrorEvent;

export interface StreamChatOptions {
  conversationId: number;
  query: string;
  signal?: AbortSignal;
  onEvent: (e: ChatEvent) => void;
}

/** 业务/网关异常，带后端 traceId 便于排查 */
export class ChatStreamError extends Error {
  traceId?: string;
  constructor(message: string, traceId?: string) {
    super(message);
    this.name = "ChatStreamError";
    this.traceId = traceId;
  }
}

const logoutAndRedirect = () => {
  localStorage.removeItem("token");
  localStorage.removeItem("isLogin");
  if (location.pathname !== "/login") location.href = "/login";
};

export async function streamChat(opts: StreamChatOptions): Promise<void> {
  const token = localStorage.getItem("token");

  const resp = await fetch(`/api/conversation/${opts.conversationId}/stream`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ query: opts.query, stream: true }),
    signal: opts.signal,
  });

  // 网关层拦截：没带 token / token 失效
  if (resp.status === 401) {
    logoutAndRedirect();
    throw new ChatStreamError("登录已过期，请重新登录");
  }

  const contentType = resp.headers.get("content-type") || "";

  // 后端业务失败时是 JSON（HTTP 仍是 200），成功才是 event-stream
  if (!contentType.includes("text/event-stream")) {
    let msg = `请求失败（HTTP ${resp.status}）`;
    let traceId: string | undefined;
    try {
      const body = await resp.json();
      msg = body?.msg || msg;
      traceId = body?.traceId;
    } catch {
      // 响应体不是 JSON，保留默认文案
    }
    throw new ChatStreamError(msg, traceId);
  }

  if (!resp.body) throw new ChatStreamError("浏览器不支持流式响应");

  const reader = resp.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // 事件以空行分隔；\r\n\r\n 用于兼容个别代理改写换行符
      let sep: number;
      while ((sep = findSeparator(buffer)) !== -1) {
        const raw = buffer.slice(0, sep);
        buffer = buffer.slice(sep + separatorLength(buffer, sep));
        const payload = parseEvent(raw);
        if (payload) opts.onEvent(payload);
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/** 返回事件分隔符的下标，没有则 -1 */
function findSeparator(buf: string): number {
  const lf = buf.indexOf("\n\n");
  const crlf = buf.indexOf("\r\n\r\n");
  if (lf === -1) return crlf;
  if (crlf === -1) return lf;
  return Math.min(lf, crlf);
}

function separatorLength(buf: string, idx: number): number {
  return buf.startsWith("\r\n\r\n", idx) ? 4 : 2;
}

/** 一个事件块可能含多行 data:（SSE 规范里要拼接），这里兼容处理 */
function parseEvent(raw: string): ChatEvent | null {
  const dataLines = raw
    .split(/\r?\n/)
    .filter((l) => l.startsWith("data:"))
    .map((l) => l.slice(5).trimStart());

  if (!dataLines.length) return null;

  try {
    return JSON.parse(dataLines.join("\n")) as ChatEvent;
  } catch {
    // 心跳 / 注释行等无法解析的内容直接跳过
    return null;
  }
}
