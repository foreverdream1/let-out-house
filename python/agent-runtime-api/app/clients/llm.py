import json
from typing import AsyncIterator

import httpx
from app.config import settings

async def stream_chat(messages:list[dict],model:str,temperature:float|None=None,max_tokens:int|None=None)->AsyncIterator[dict]:
    """流式调用OpenAI兼容接口"""
    payload={"model":model,"messages":messages,"stream":True}
    if temperature is not None:
        payload["temperature"]=float(temperature)
    if max_tokens:
        payload["max_tokens"]=max_tokens
    headers={"Authorization":f"Bearer {settings.llm_api_key}","Content-Type":"application/json"}
    timeout=httpx.Timeout(connect=10.0,read=120.0,write=30.0,pool=10.0)
    async with httpx.AsyncClient(timeout=timeout,trust_env=False) as client:
        async with client.stream("POST",f"{settings.llm_base_url}/chat/completions",
                                 json=payload,headers=headers) as resp:
            if resp.status_code!=200:
                body=(await resp.aread()).decode("utf-8","ignore")[:300]
                raise RuntimeError(f"LLM调用失败{resp.status_code}:{body}")
            async for line in resp.aiter_lines():
                if not line or not line.startswith("data:"):
                    continue
                data=line[5:].strip()
                if data=="[DONE]":
                    break
                try:
                    chunk=json.loads(data)
                except json.JSONDecodeError :
                    continue
                if chunk.get("usage"):
                    yield {"type":"usage","usage":chunk["usage"]}
                for choice in chunk.get("choices") or []:
                    delta=choice.get("delta") or {}
                    if delta.get("content"):
                        yield {"type":"delta","content":delta["content"]}