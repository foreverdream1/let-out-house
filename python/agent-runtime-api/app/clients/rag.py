import httpx

from app.config import settings

async def retrieve(kb_ids:list[int],query:str,top_k:int,tenant_id:str,trace_id:str)->list[dict]:
    """调用rag_service检索，失败后由调用方决定降级策略，这里只管抛出异常"""
    if not kb_ids:
        return []
    async with httpx.AsyncClient(timeout=settings.rag_timeout,trust_env=False) as client:
        r=await client.post(
            f"{settings.rag_service_base_url}/api/rag/search",
            headers={"X-Tenant-Id":tenant_id,"X-Trace-Id":trace_id},
            json={"kbIds":kb_ids,"query":query,"topK":top_k},
        )
    body=r.json()
    if body.get("code")!=0:
        raise RuntimeError(f"rag检索失败：{body.get('msg')}")
    return (body.get("data") or {}).get("chunks") or []