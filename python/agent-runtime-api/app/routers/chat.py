import asyncio
import json
import logging
import uuid

from fastapi import APIRouter, Header, FastAPI
from fastapi.responses import StreamingResponse

from app.clients.llm import stream_chat
from app.clients.rag import retrieve
from app.schemas import ChatRequest
from app.config import settings

log=logging.getLogger(__name__)

router=APIRouter(prefix="/api/agent/runtime",tags=["runtime"])

MOCK_REPLY="这是一段来自agent-runtime-api的模拟回答，用来验证SSE全链路是否打通"

def build_messages(req:ChatRequest,chunks:list[dict])->list[dict]:
    parts=[req.systemPrompt or "你是一个专业的企业知识库助手，回答简介准确。"]
    if chunks:
        refs="\n".join(f"[{i}]{c['content']}" for i,c in enumerate(chunks,1))
        parts.append(
            "以下是知识库检索到的参考资料，请优先依据他们回答："
            "资料中没有的信息要明确说明[知识库中未找到]，不要编造：\n"+refs
        )
    messages=[{"role":"system","content":"\n\n".join(parts)}]
    for h in req.history[-10:]:
        messages.append({"role":h.role,"content":h.content})
    messages.append({"role":"user","content":req.query})
    return messages

async def _mock_stream(req:ChatRequest,trace_id:str,rag_note:str,chunks:list[dict]):
    """没配 LLM key 时的兜底：保证链路可演示，不依赖外部服务。"""
    if chunks:
        reply=f"[{rag_note}|最高分{chunks[0]['score']}]\n根据知识库：{chunks[0]['content']}"
    else:
        reply=f"[{rag_note or '未启用检索'}]\n{MOCK_REPLY}"
    for ch in reply:
        yield f"data: {json.dumps({'type':'chunk','content':ch,'traceId':trace_id}, ensure_ascii=False)}\n\n"
        await asyncio.sleep(0.02)
    yield f"data: {json.dumps({'type':'end','fullContent':reply,'tokenUsage':{'input':len(req.query),'output':len(reply)},'traceId':trace_id}, ensure_ascii=False)}\n\n"

async def _event_stream(req: ChatRequest, trace_id: str, tenant_id: str):
    chunks, rag_note = [], ""
    if req.ragEnable and req.kbIds:
        try:
            chunks = await retrieve(req.kbIds, req.query, req.topK or settings.default_top_k,
                                    tenant_id, trace_id)
            rag_note = f"检索命中{len(chunks)}条片段"
        except Exception as e:
            log.warning("RAG 检索失败，降级: %s", e)
            rag_note = "检索不可用，已降级为普通回答"

    if not settings.llm_api_key:                    # 没配 key → 走原来的 mock
        async for ev in _mock_stream(req, trace_id, rag_note, chunks):
            yield ev
        return

    full, usage = [], None
    try:
        async for ev in stream_chat(build_messages(req, chunks),
                                    req.modelName or settings.llm_model,
                                    req.temperature, req.maxToken):
            if ev["type"] == "delta":
                full.append(ev["content"])
                payload = {"type": "chunk", "content": ev["content"], "traceId": trace_id}
                yield f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"
            elif ev["type"] == "usage":
                usage = ev["usage"]
    except Exception as e:
        log.exception("LLM 调用异常")
        yield f"data: {json.dumps({'type': 'error', 'content': '模型服务异常，请稍后重试', 'traceId': trace_id}, ensure_ascii=False)}\n\n"
        return

    end_payload = {
        "type": "end",
        "fullContent": "".join(full),
        "tokenUsage": {
            "input": (usage or {}).get("prompt_tokens", 0),
            "output": (usage or {}).get("completion_tokens", 0),
        },
        "traceId": trace_id,
    }
    yield f"data: {json.dumps(end_payload, ensure_ascii=False)}\n\n"







@router.post("/chat/stream")
async def chat_stream(
        req:ChatRequest,
        x_tenant_id:str|None=Header(default=None),
        x_trace_id:str|None=Header(default=None)
):
    trace_id=x_trace_id or uuid.uuid4().hex
    return StreamingResponse(
        _event_stream(req=req,trace_id=trace_id,tenant_id=x_tenant_id or ""),
        media_type="text/event-stream",
        headers={
            "Cache-Control":"no-cache",
            "X-Accel-Buffering":"no",
            "Connection":"keep-alive"
        }
    )