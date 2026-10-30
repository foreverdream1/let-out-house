package com.lethouse.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String content;
    private String role;
    private Integer tokenInput;
    private Integer tokenOutput;
    private Integer msgStatus;
    private String traceId;
    private LocalDateTime createdAt;
    private String citations;
}
