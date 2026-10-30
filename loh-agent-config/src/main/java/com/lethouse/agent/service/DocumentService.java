package com.lethouse.agent.service;

import com.lethouse.agent.dto.DocumentResponse;
import com.lethouse.common.result.PageResult;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
    DocumentResponse upload(Long kbId, MultipartFile file);

    PageResult<DocumentResponse> list(Long kbId, int pageNum, int pageSize);

    void delete(Long kbId,Long docId);
}
