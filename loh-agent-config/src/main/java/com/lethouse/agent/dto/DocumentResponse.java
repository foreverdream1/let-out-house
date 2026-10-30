package com.lethouse.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentResponse {
    private Long id;
    private String docName;
    private String fileType;
    private Long fileSize;
    private Integer chunkCount;
    private Integer parseStatus;
    private String failReason;
    private LocalDateTime createdAt;

}
