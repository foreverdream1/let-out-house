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

export interface MessageResponse {
  messageId: number;
  role: MessageRole;
  content: string;
  tokenInput: number | null;
  tokenOutput: number | null;
  createdAt: string;
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
