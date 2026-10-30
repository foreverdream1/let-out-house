package com.lethouse.conversation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lethouse.common.context.TenantContext;
import com.lethouse.common.exception.BusinessException;
import com.lethouse.common.result.PageResult;
import com.lethouse.conversation.dto.ConversationCreateRequest;
import com.lethouse.conversation.dto.ConversationResponse;
import com.lethouse.conversation.dto.MessageResponse;
import com.lethouse.conversation.entity.AgentRef;
import com.lethouse.conversation.entity.Conversation;
import com.lethouse.conversation.entity.Message;
import com.lethouse.conversation.mapper.AgentRefMapper;
import com.lethouse.conversation.mapper.ConversationMapper;
import com.lethouse.conversation.mapper.MessageMapper;
import com.lethouse.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {


    private final AgentRefMapper agentRefMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Long create(ConversationCreateRequest request) {
        //校验Agent存在其启用
        AgentRef agent = agentRefMapper.selectById(request.getAgentId());
        if (agent==null||!Integer.valueOf(1).equals(agent.getStatus())) {
            throw new BusinessException(400,"Agent不存在或已停用");
        }
        //插入对话
        Conversation conversation = new Conversation();
        conversation.setUserId(TenantContext.getUserId());
        conversation.setAgentId(request.getAgentId());
        conversation.setTitle(StringUtils.hasText(request.getTitle())?request.getTitle():"新对话");
        conversation.setMessageCount(0);
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    @Override
    public PageResult<ConversationResponse> list(int pageNum, int pageSize) {
         pageSize = Math.min(pageSize, 100);
        Page<Conversation> page = new Page<>(pageNum, pageSize);
        Page<Conversation> result = conversationMapper.selectPage(page, new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, TenantContext.getUserId())
                .orderByDesc(Conversation::getId));
        return PageResult.of(result.convert(this::toResponse));
    }

    @Override
    public PageResult<MessageResponse> history(Long conversationId, int pageNum, int pageSize) {
        Long owned = conversationMapper.selectCount(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, TenantContext.getUserId()));
        if (owned==0) {
            throw new BusinessException(404,"会话不存在");
        }
         pageSize = Math.min(pageSize, 100);
        Page<Message> page = new Page<>(pageNum, pageSize);
        Page<Message> result = messageMapper.selectPage(page, new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getId));
        return PageResult.of(result.convert(this::toResponse));
    }

    @Override
    public void delete(Long conversationId) {
        Long owned = conversationMapper.selectCount(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, TenantContext.getUserId()));
        if (owned==0) {
            throw new BusinessException(404,"会话不存在");
        }
        conversationMapper.deleteById(conversationId);
    }

    private ConversationResponse toResponse(Conversation e){
        ConversationResponse r = new ConversationResponse();
        r.setTitle(e.getTitle());
        r.setMessageCount(e.getMessageCount());
        r.setAgentId(e.getAgentId());
        r.setCreatedAt(e.getCreatedAt());
        r.setLastMessageAt(e.getLastMessageAt());
        r.setConversationId(e.getId());
        return r;
    }

    private MessageResponse toResponse(Message e){
        MessageResponse r = new MessageResponse();
        r.setContent(e.getContent());
        r.setRole(e.getRole());
        r.setCreatedAt(e.getCreatedAt());
        r.setMessageId(e.getId());
        r.setTokenInput(e.getTokenInput());
        r.setTokenOutput(e.getTokenOutput());
        if(StringUtils.hasText(e.getCitations())){
            try {
                r.setCitations(objectMapper.readTree(e.getCitations()));
            }catch (JsonProcessingException ex){
                log.warn("citations解析失败：messageId={}",e.getId());
            }
        }
        return r;
    }
}
