package com.lethouse.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lethouse.agent.dto.DocumentResponse;
import com.lethouse.agent.entity.Document;
import com.lethouse.agent.entity.KnowledgeBase;
import com.lethouse.agent.mapper.DocumentMapper;
import com.lethouse.agent.mapper.KnowledgeBaseMapper;
import com.lethouse.agent.service.DocumentService;
import com.lethouse.common.context.TenantContext;
import com.lethouse.common.exception.BusinessException;
import com.lethouse.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    /** 与 spring.servlet.multipart.max-file-size 保持一致 */
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("pdf", "docx", "md", "txt");

    /** parse_status：0待解析 1解析中 2完成 3失败 */
    private static final int PARSE_DONE = 2;
    private static final int PARSE_FAILED = 3;

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RestClient ragRestClient;

    /** 文件落盘根目录（Java 与 rag-service 需同机共享） */
    @Value("${loh.rag.storage-dir}")
    private String storageDir;

    @Override
    public DocumentResponse upload(Long kbId, MultipartFile file) {
        // ① 校验知识库归属（租户拦截器自动带 tenant_id）
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || !Integer.valueOf(1).equals(kb.getStatus())) {
            throw new BusinessException(400, "知识库不存在或已停用");
        }

        // ② 校验文件：名、大小、类型
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        if (file.getSize() <= 0) {
            throw new BusinessException(400, "请上传一个非空文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "文件不能超过 20MB");
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0) {
            throw new BusinessException(400, "文件缺少扩展名");
        }
        String ext = originalName.substring(dot + 1).toLowerCase();
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BusinessException(400, "仅支持 pdf / docx / md / txt 格式");
        }

        Long tenantId = TenantContext.getTenantId();

        // ③ 同名幂等：同一知识库下同名文件视为覆盖，旧向量由 rag-service 按 document_id 清理
        Document existing = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getDocName, originalName)
                .last("limit 1"));
        boolean isNew = existing == null;

        // ④ 落盘：{storageDir}/{tenantId}/{kbId}/{uuid}.{ext}
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = Paths.get(storageDir, String.valueOf(tenantId), String.valueOf(kbId), fileName);
        String storageKey = tenantId + "/" + kbId + "/" + fileName;
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("文件保存失败：kbId={}, name={}", kbId, originalName, e);
            throw new BusinessException(500, "文件保存失败");
        }

        // ⑤ 落库（先占位 parse_status=0 待解析）
        Document doc;
        if (isNew) {
            doc = new Document();
            doc.setKbId(kbId);
            doc.setDocName(originalName);
            doc.setFileType(ext);
            doc.setFileSize(file.getSize());
            doc.setStorageKey(storageKey);
            doc.setChunkCount(0);
            doc.setParseStatus(0);
            doc.setStatus(1);
            documentMapper.insert(doc);
        } else {
            doc = existing;
            // 注意：updateById 会忽略 null，fail_reason 要用 UpdateWrapper 显式清空
            documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .eq(Document::getId, doc.getId())
                    .set(Document::getFileType, ext)
                    .set(Document::getFileSize, file.getSize())
                    .set(Document::getStorageKey, storageKey)
                    .set(Document::getChunkCount, 0)
                    .set(Document::getParseStatus, 0)
                    .set(Document::getFailReason, null));
        }

        // ⑥ 调 rag-service 解析入库
        int chunkCount = 0;
        String failReason = null;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("docId", doc.getId());
            body.put("kbId", kbId);
            body.put("tenantId", tenantId);
            body.put("filePath", target.toAbsolutePath().toString());
            body.put("fileType", ext);
            body.put("docName", originalName);

            Map<?, ?> resp = ragRestClient.post()
                    .uri("/api/rag/ingest")
                    .header("X-Tenant-Id", String.valueOf(tenantId))
                    .header("X-Trace-Id", String.valueOf(TenantContext.getRequestId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            Object code = resp == null ? null : resp.get("code");
            if (code instanceof Number n && n.intValue() == 0) {
                Object data = resp.get("data");
                Object cc = (data instanceof Map<?, ?> m) ? m.get("chunkCount") : null;
                chunkCount = (cc instanceof Number n2) ? n2.intValue() : 0;
            } else {
                failReason = "解析服务返回异常：" + (resp == null ? "空响应" : resp.get("msg"));
            }
        } catch (Exception e) {
            log.error("调用 rag-service 入库失败：docId={}", doc.getId(), e);
            failReason = "解析服务不可用：" + e.getMessage();
        }

        // ⑦ 回写状态：解析失败也不抛异常，用 parse_status 表达
        if (failReason == null && chunkCount > 0) {
            documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .eq(Document::getId, doc.getId())
                    .set(Document::getParseStatus, PARSE_DONE)
                    .set(Document::getChunkCount, chunkCount));
            if (isNew) {
                knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, kbId)
                        .setSql("doc_count = doc_count + 1"));
            }
            log.info("文档入库完成：docId={}, kbId={}, chunkCount={}", doc.getId(), kbId, chunkCount);
        } else {
            String reason = failReason != null ? failReason : "未解析到文本内容（可能是扫描件PDF或空文档）";
            documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                    .eq(Document::getId, doc.getId())
                    .set(Document::getParseStatus, PARSE_FAILED)
                    .set(Document::getFailReason, abbreviate(reason, 500)));
            log.warn("文档入库失败：docId={}, reason={}", doc.getId(), reason);
        }

        return toResponse(documentMapper.selectById(doc.getId()));
    }

    @Override
    public PageResult<DocumentResponse> list(Long kbId, int pageNum, int pageSize) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(404, "知识库不存在");
        }
        pageSize = Math.min(pageSize, 100);
        Page<Document> page = new Page<>(pageNum, pageSize);
        Page<Document> result = documentMapper.selectPage(page, new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .orderByDesc(Document::getId));
        return PageResult.of(result.convert(this::toResponse));
    }

    @Override
    public void delete(Long kbId, Long docId) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getKbId().equals(kbId)) {
            throw new BusinessException(404, "文档不存在");
        }
        // 先让 Python 清 Milvus 向量 + document_chunk 行（幂等，失败直接抛，不留半截状态）
        ragRestClient.delete()
                .uri("/api/rag/document/{docId}", docId)
                .header("X-Tenant-Id", String.valueOf(TenantContext.getTenantId()))
                .retrieve()
                .toBodilessEntity();

        // 再逻辑删文档行 + 回写计数
        documentMapper.deleteById(docId);
        knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .setSql("doc_count = GREATEST(doc_count - 1, 0)"));
    }

    private DocumentResponse toResponse(Document d) {
        DocumentResponse r = new DocumentResponse();
        r.setId(d.getId());
        r.setDocName(d.getDocName());
        r.setFileType(d.getFileType());
        r.setFileSize(d.getFileSize());
        r.setChunkCount(d.getChunkCount());
        r.setParseStatus(d.getParseStatus());
        r.setFailReason(d.getFailReason());
        r.setCreatedAt(d.getCreatedAt());
        return r;
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }
}
