package com.lethouse.agent.controller;

import com.lethouse.agent.dto.DocumentResponse;
import com.lethouse.agent.service.DocumentService;
import com.lethouse.common.result.PageResult;
import com.lethouse.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge/{kbId}/documents")
public class KnowledgeDocumentController {

    private final DocumentService documentService;

    @PostMapping
    public R<DocumentResponse> upload(@PathVariable Long kbId, @RequestPart("file")MultipartFile file){
        return R.ok(documentService.upload(kbId, file));
    }

    @GetMapping
    public R<PageResult<DocumentResponse>> list(@PathVariable Long kbId,
                                                @RequestParam(defaultValue = "1") int pageNum,
                                                @RequestParam(defaultValue = "10") int pageSize){
        return R.ok(documentService.list(kbId, pageNum, pageSize));
    }

    @DeleteMapping("/{docId}")
    public R<Void> delete(@PathVariable Long kbId,@PathVariable Long docId){
        documentService.delete(kbId, docId);
        return R.ok();
    }
}
