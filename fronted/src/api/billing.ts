import http from "./http";
import type { PageResult } from "./agent";

/** 计费账单（只读，租户由网关从 token 解出，前端不传） */

export interface UsageSummary {
  totalInputTokens: number;
  totalOutputTokens: number;
  /** 单位：元，6 位小数 */
  totalCost: number;
  /** 计费次数 = 成功回答条数，免费模型也会计入 */
  callCount: number;
}

export interface UsageDetail {
  id: number;
  tenantId: number;
  userId: number;
  agentId: number;
  conversationId: number;
  messageId: number;
  modelName: string;
  inputTokens: number;
  outputTokens: number;
  inputCost: number;
  outputCost: number;
  totalCost: number;
  requestId: string | null;
  createdAt: string;
}

/** 后端要求 yyyy-MM-dd HH:mm:ss，左闭右开 */
export const formatBillingTime = (d: Date) => {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};

export const billingApi = {
  summary: (p: { from?: string; to?: string } = {}) =>
    http.get<never, UsageSummary>("/api/billing/summary", { params: p }),

  detail: (p: { pageNum?: number; pageSize?: number } = {}) =>
    http.get<never, PageResult<UsageDetail>>("/api/billing/detail", { params: p }),
};
