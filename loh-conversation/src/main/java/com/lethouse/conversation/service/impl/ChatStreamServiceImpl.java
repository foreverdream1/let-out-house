package com.lethouse.conversation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lethouse.common.context.TenantContext;
import com.lethouse.common.exception.BusinessException;
import com.lethouse.conversation.dto.ChatStreamRequest;
import com.lethouse.conversation.entity.AgentKbRel;
import com.lethouse.conversation.entity.AgentRef;
import com.lethouse.conversation.entity.Conversation;
import com.lethouse.conversation.entity.Message;
import com.lethouse.conversation.mapper.AgentKbRelMapper;
import com.lethouse.conversation.mapper.AgentRefMapper;
import com.lethouse.conversation.mapper.ConversationMapper;
import com.lethouse.conversation.mapper.MessageMapper;
import com.lethouse.conversation.service.ChatStreamService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamServiceImpl implements ChatStreamService {


    private static final Integer MAX_WIDTH =20;
    private static final int MAX_HISTORY=20;


    private final HttpClient agentRuntimeClient;
    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;
    private final MessageMapper messageMapper;
    private final AgentRefMapper agentRefMapper;
    private final AgentKbRelMapper agentKbRelMapper;

    @Value("${loh.agent-runtime.base-url}")
    private String baseUrl;

    @Override
    public void stream(Long conversationId, ChatStreamRequest request, HttpServletResponse response){
        //校验会话归属
        Conversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getId, conversationId)
                        .eq(Conversation::getUserId, TenantContext.getUserId())
        );
        if (conversation==null) {
            throw new BusinessException(404,"会话不存在");
        }

        List<Message> recent = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getMsgStatus, 1)
                .orderByDesc(Message::getId)
                .last("LIMIT " + MAX_HISTORY));
        Collections.reverse(recent);

        //会话与Agent状态校验放在发请求之前
        if (!Integer.valueOf(1).equals(conversation.getStatus())) {
            throw new BusinessException(400,"会话已归档");
        }
        AgentRef agent = agentRefMapper.selectById(conversation.getAgentId());
        if (agent==null||!Integer.valueOf(1).equals(agent.getStatus())) {
            throw new BusinessException(400,"Agent不存在或已停用");
        }

        List<Long> kbIds = agentKbRelMapper.selectList(new LambdaQueryWrapper<AgentKbRel>()
                .eq(AgentKbRel::getAgentId, agent.getId())).stream().map(AgentKbRel::getKbId).toList();

        //先调上游，拿到成功响应
        HttpResponse<InputStream> upstream;
        try {
            upstream=agentRuntimeClient.send(buildRequest(conversation,request,agent,recent,kbIds),HttpResponse.BodyHandlers.ofInputStream());
        }catch (IOException e){
            Thread.currentThread().interrupt();
            throw  new BusinessException(503,"AI服务暂不可用");
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            throw new BusinessException(503,"AI服务暂不可用");
        }
        if (upstream.statusCode()!=200) {
            closeQuietly(upstream.body());
            throw  new BusinessException(502,"AI服务返回异常");
        }

        StringBuilder collected = new StringBuilder();
        String endContent=null;
        Integer tokenIn=null;
        Integer tokenOut=null;
        boolean interrupted=false;
        //上游 body 作为第一个 resource 交给 try-with-resources：
        //200 之后无论哪一步抛异常（插库失败、设头失败、客户端断开），都由编译器保证先关连接再抛
        try(InputStream upstreamBody = upstream.body();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(upstreamBody, StandardCharsets.UTF_8)
        )){
            //上游成功才落 user 消息，失败不留脏数据
            Message userMsg = new Message();
            userMsg.setConversationId(conversationId);
            userMsg.setRole("user");
            userMsg.setContent(request.getQuery());
            userMsg.setMsgStatus(1);
            userMsg.setTraceId(MDC.get("traceId"));
            messageMapper.insert(userMsg);

            //设置SSE响应头（必须在第一次写出之前）
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setHeader("Cache-Control","no-cache");
            response.setHeader("X-Accel-Buffering","no");

            ServletOutputStream out = response.getOutputStream();
            String line;
            while((line=reader.readLine())!=null){
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
                if (line.startsWith("data:")) {
                    try {
                        JsonNode node = objectMapper.readTree(line.substring(5).trim());
                        String type = node.path("type").asText();
                        if ("chunk".equals(type)) {
                            collected.append(node.path("content").asText(""));
                        }else if("end".equals(type)){
                            endContent=node.path("fullContent").asText("");
                            tokenIn=node.path("tokenUsage").path("input").asInt();
                            tokenOut=node.path("tokenUsage").path("output").asInt();
                        }
                    }catch (Exception e){
                        log.debug("SSE时间解析跳过：{}",line);
                    }
                }
            }
        } catch (IOException e) {
            //客户端主动断开（关闭页面/切换路由）走到这里，正常现象
            interrupted=true;
            log.debug("SSE流被中断：conversationId={}",conversationId);
        }finally {
            persistTurn(conversationId,conversation,request.getQuery(),endContent!=null?endContent:collected.toString(),tokenIn,tokenOut,interrupted);
        }
    }

    private HttpRequest buildRequest(Conversation conversation, ChatStreamRequest request, AgentRef agent,List<Message> recent,List<Long> kbIds){
        Map<String, Object> body = new HashMap<>();
        body.put("conversationId", conversation.getId());
        body.put("agentId", conversation.getAgentId());
        body.put("query", request.getQuery());
        body.put("stream", true);
        body.put("systemPrompt",agent.getSystemPrompt());
        body.put("modelName",agent.getModelName());
        body.put("temperature",agent.getTemperature());
        body.put("maxToken",agent.getMaxToken());
        body.put("topK",agent.getTopK());
        body.put("ragEnable",agent.getRagEnable());
        body.put("kbIds",kbIds);
        body.put("history",recent.stream().map(m->Map.of("role",m.getRole(),"content",m.getContent())).toList());
        log.debug("调用AgentRuntime：history={}条, model={}, ragEnable={}",
                recent.size(), agent.getModelName(), agent.getRagEnable());
        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl+"/api/agent/runtime/chat/stream"))
                    .header("Content-Type","application/json")
                    .header("X-Tenant-Id",String.valueOf(TenantContext.getTenantId()))
                    .header("X-Trace-Id", MDC.get("traceId"))
                    .header("X-Request-Id",TenantContext.getRequestId())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        }catch (JsonProcessingException e){
            throw  new BusinessException(500,"请求序列化失败");
        }
    }

    private void persistTurn(Long conversationId,Conversation conversation,String query,String answer,Integer tokenIn,Integer tokenOut,boolean interrupted){

        try {
            boolean hasAnswer = StringUtils.hasText(answer);

            if (hasAnswer) {
                Message message = new Message();
                message.setConversationId(conversationId);
                message.setRole("assistant");
                message.setContent(answer);
                message.setTokenInput(tokenIn);
                message.setTokenOutput(tokenOut);
                message.setMsgStatus(interrupted ? 0 : 1);
                message.setTraceId(MDC.get("traceId"));
                messageMapper.insert(message);
            }
            conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                    .eq(Conversation::getId, conversationId)
                    .setSql("message_count=message_count+" + (hasAnswer ? 2 : 1)+",last_message_at=CURRENT_TIMESTAMP(3)")
                    .set("新对话".equals(conversation.getTitle()), Conversation::getTitle, abbreviate(query, MAX_WIDTH)));
        }catch (Exception e){
            log.error("对话落库失败：conversationId={}",conversationId,e);
        }
    }

    private String abbreviate(String text,int maxWidth){
        if (text==null||text.length()<=maxWidth) {
            return text;
        }
        return text.substring(0,maxWidth)+"...";
    }

    /** 关闭上游响应流，异常不外抛（错误响应体本来就不需要读） */
    private void closeQuietly(InputStream in){
        if (in==null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            //关闭失败不影响错误返回
        }
    }
}
