package com.lethouse.conversation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageResponse {
    private Long messageId;
    private String role;
    private String content;
    private Integer tokenInput;
    private Integer tokenOutput;
    private LocalDateTime createdAt;
    private JsonNode citations;
}
