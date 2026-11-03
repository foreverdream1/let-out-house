import http from "./http";
import type { PageResult } from "./agent";

export interface ConversationCreateRequest {
  agentId: number;
  title?: string;
}

export interface ConversationResponse {
  conversationId: number;
  agentId: number;
  title: string;
  messageCount: number;
  lastMessageAt: string | null;
  createdAt: string;
}

export type MessageRole = "user" | "assistant" | "system";

/**
 * 回答引用的知识库片段。
 * 与 SSE end 事件的 citations 结构完全一致，两处复用同一个渲染组件。
 */
export interface Citation {
  /** 引用序号，与正文里的 [1] [2] 对应 */
  index: number;
  chunkId: number;
  documentId: number;
  documentName: string;
  /** 余弦相似度 0~1 */
  score: number;
  /** 片段正文，后端已截断到 120 字 */
  snippet: string;
}

export interface MessageResponse {
  messageId: number;
  role: MessageRole;
  content: string;
  tokenInput: number | null;
  tokenOutput: number | null;
  createdAt: string;
  /**
   * null  = 用户消息 / 未检索（RAG 关闭或老数据）→ 不渲染
   * []    = 检索了但没命中 → 提示"未检索到相关文档"
   * [...] = 命中 N 条，按相关度倒序
   */
  citations: Citation[] | null;
}

export const conversationApi = {
  create: (b: ConversationCreateRequest) =>
    http.post<never, number>("/api/conversation", b),
  list: (p: { pageNum?: number; pageSize?: number } = {}) =>
    http.get<never, PageResult<ConversationResponse>>("/api/conversation/list", {
      params: p,
    }),
  history: (id: number, p: { pageNum?: number; pageSize?: number } = {}) =>
    http.get<never, PageResult<MessageResponse>>(
      `/api/conversation/${id}/history`,
      { params: p },
    ),
  remove: (id: number) => http.delete<never, void>(`/api/conversation/${id}`),
};
