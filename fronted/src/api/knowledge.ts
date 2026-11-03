import http from "./http";
import type { PageResult } from "./agent";

/**
 * 知识库文档管理。
 *
 * 后端没有「知识库列表」接口，kbId 需要外部提供（Agent 配置里关联的那个）。
 */

export interface DocumentResponse {
  id: number;
  docName: string;
  fileType: string;
  /** 字节数，展示时自己格式化 */
  fileSize: number;
  chunkCount: number;
  /** 0 待解析 / 1 解析中 / 2 已完成 / 3 失败 */
  parseStatus: number;
  /** 仅 parseStatus=3 时有值 */
  failReason: string | null;
  createdAt: string;
}

export const PARSE_STATUS = {
  PENDING: 0,
  PARSING: 1,
  DONE: 2,
  FAILED: 3,
} as const;

export const ALLOWED_EXTENSIONS = ["pdf", "docx", "md", "txt"];
export const MAX_FILE_SIZE = 20 * 1024 * 1024;

export const knowledgeApi = {
  /**
   * 上传后后端会同步做「解析 + 切片 + 向量化」，耗时可能远超默认 15s，
   * 所以这里单独把超时放大到 5 分钟。
   * 注意不要手动设置 Content-Type —— 浏览器要自己带 multipart boundary。
   */
  upload: (kbId: number, file: File, onProgress?: (percent: number) => void) => {
    const fd = new FormData();
    fd.append("file", file);
    return http.post<never, DocumentResponse>(
      `/api/knowledge/${kbId}/documents`,
      fd,
      {
        timeout: 300000,
        onUploadProgress: (e) => {
          if (onProgress && e.total) {
            onProgress(Math.round((e.loaded / e.total) * 100));
          }
        },
      },
    );
  },

  list: (kbId: number, p: { pageNum?: number; pageSize?: number } = {}) =>
    http.get<never, PageResult<DocumentResponse>>(
      `/api/knowledge/${kbId}/documents`,
      { params: p },
    ),

  remove: (kbId: number, docId: number) =>
    http.delete<never, void>(`/api/knowledge/${kbId}/documents/${docId}`),
};
