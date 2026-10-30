import asyncio
import time
import uuid

from fastapi import APIRouter,Header

from rag_service.clients.embedding import embed_texts
from rag_service.clients.milvus import get_client
from rag_service.config import settings
from rag_service.db import fetch_chunks,fetch_kb_collections
from rag_service.schemas import SearchRequest

router=APIRouter(prefix="/api/rag",tags=["rag"])

def _ok(data,trace_id):
    return {"code":0,"msg":"ok","data":data,"traceId":trace_id}

def _fail(code,msg,trace_id):
    return {"code":code,"msg":msg,"data":None,"traceId":trace_id}


@router.post("/search")
async def search(
        req:SearchRequest,
        x_tenant_id:str|None=Header(default=None),
        x_trace_id:str|None=Header(default=None)
):
    trace_id=x_trace_id or uuid.uuid4().hex
    if not x_tenant_id or not x_tenant_id.isdigit():
        return _fail(400,"缺少或非法的X-Tenant-Id",trace_id)
    tenant_id=int(x_tenant_id)
    top_k=min(req.topK,settings.max_top_k)
    min_score = req.minScore if req.minScore is not None else settings.min_score
    t0=time.perf_counter()

    try:
        vec=(await  embed_texts([req.query]))[0]

        collection=await asyncio.to_thread(fetch_kb_collections,tenant_id,req.kbIds)
        if not collection:
            return _fail(404,"知识库不存在或已停用",trace_id)

        client=get_client()
        merged=[]
        for kb_id,coll in collection.items():
            hits=client.search(
                collection_name=coll,
                data=[vec],
                limit=top_k*2,
                filter=f"kb_id == {kb_id} and tenant_id == {tenant_id}",
                output_fields=["chunk_id"],
                search_params={"metric_type":"COSINE"},
            )
            for h in hits[0]:
                merged.append({"pk":h.id,"score":h.distance,"chunk_id":h["entity"]["chunk_id"]})

        merged.sort(key=lambda x:x["score"],reverse=True)
        merged = [m for m in merged if m["score"] >= min_score][:top_k]

        rows=await asyncio.to_thread(fetch_chunks,tenant_id,[m["pk"] for m in merged])

        chunks=[]
        for m in merged:
            row=rows.get(m["pk"])
            if not row:
                continue
            chunks.append({
                "chunkId": row["id"],
                "documentId": row["document_id"],
                "documentName": row.get("doc_name") or "",
                "chunkIndex": row["chunk_index"],
                "content": row["content"],
                "score": round(m["score"], 4),
            })

        return _ok({"chunks":chunks,"elapsedMs":int((time.perf_counter()-t0)*1000)},trace_id)

    except Exception as e:
        return _fail(500,str(e)[:200],trace_id)