import asyncio
import uuid

from fastapi import APIRouter,Header
from pymilvus import DataType

from rag_service.clients.embedding import embed_texts
from rag_service.clients.milvus import get_client
from rag_service.config import settings
from rag_service.db import fetch_chunk_ids,fetch_doc_for_ingest
from rag_service.db_write import delete_chunks,insert_chunks,update_milvus_pks
from rag_service.ingest.parser import parse_file
from rag_service.ingest.splitter import split_text
from rag_service.schemas import IngestRequest

router=APIRouter(prefix="/api/rag",tags=["rag-ingest"])
def _ok(data,trace_id):
    return {"code":0,"msg":"ok","data":data,"traceId":trace_id}

def _fail(code,msg,trace_id):
    return {"code":code,"msg":msg,"data":None,"traceId":trace_id}

def ensure_collection(client,name:str)->None:
    if client.has_collection(name):
        return
    schema=client.create_schema(auto_id=True,enable_dynamic_field=False)
    schema.add_field("pk",DataType.INT64,is_primary=True,auto_id=True)
    schema.add_field("vector",DataType.FLOAT_VECTOR,dim=settings.embed_dim)
    schema.add_field("chunk_id",DataType.INT64)
    schema.add_field("kb_id",DataType.INT64)
    schema.add_field("tenant_id",DataType.INT64)
    idx=client.prepare_index_params()
    idx.add_index(field_name="vector",index_type="AUTOINDEX",metric_type="COSINE")
    client.create_collection(name,schema=schema,index_params=idx)

@router.post("/ingest")
async def ingest(req:IngestRequest,
                 x_tenant_id:str|None=Header(default=None),
                 x_trace_id:str|None=Header(default=None)
                 ):
    trace_id=x_trace_id or uuid.uuid4().hex
    if not x_tenant_id or not x_tenant_id.isdigit():
        return _fail(400,"缺少或非法的X-Tenant-Id",trace_id)
    tenant_id=int(x_tenant_id)
    if tenant_id!=req.tenantId:
        return _fail(403,"租户不匹配",trace_id)

    doc=await asyncio.to_thread(fetch_doc_for_ingest,req.docId,tenant_id)
    if not doc or doc["kb_id"]!= req.kbId:
        return _fail(404,"文档不存在",trace_id)

    # 解析+切片
    try:
        text=parse_file(req.filePath,req.fileType)
    except Exception as e:
        return _fail(400,f"解析失败：{str(e)[:150]}",trace_id)
    chunks=split_text(text,settings.chunk_size,settings.chunk_overlap)
    if not chunks:
        return _fail(400,"未解析到文本内容",trace_id)

    client=get_client()
    coll=doc["collection_name"]
    ensure_collection(client,coll)

    #清理旧数据
    old=await asyncio.to_thread(fetch_chunk_ids,req.docId,tenant_id)
    if old:
        ids=[r["id"]for r in old]
        try:
            client.delete(coll,filter=f"chunk_id in {ids}")
        except Exception as e:
            #向量本来就没有，所以能继续，不必因此中断
            print(f"[warn] 清理旧向量失败：{e}")
        await asyncio.to_thread(delete_chunks,req.docId,tenant_id)

    #分批向量化
    vecs:list[list[float]]=[]
    for i in range(0,len(chunks),settings.embed_batch):
        vecs.extend(await embed_texts(chunks[i:i+settings.embed_batch]))
    if len(vecs)!=len(chunks):
        return _fail(500,f"向量数量不匹配：{len(vecs)}!={len(chunks)}",trace_id)

    #先写MySQL拿chunk_id,再插入milvus带入chunk_id
    rows=[{"tenant_id":tenant_id,"kb_id":req.kbId,"document_id":req.docId,"chunk_index":i,"content":c,"char_count":len(c),"milvus_pk":0}for i,c in enumerate(chunks)]
    await asyncio.to_thread(insert_chunks,rows)

    saved=await asyncio.to_thread(fetch_chunk_ids,req.docId,tenant_id)
    if len(saved)!=len(chunks):
        return _fail(500,"分片落库数量不一致",trace_id)

    res=client.insert(coll,[{"vector":v,"chunk_id":row["id"],"kb_id":req.kbId,"tenant_id":tenant_id}for v,row in zip(vecs,saved)])
    client.flush(coll)
    await asyncio.to_thread(update_milvus_pks,list(zip([r["id"]for r in saved],res["ids"])))
    return _ok({"docId":req.docId,"chunkCount":len(chunks)},trace_id)

@router.delete("/document/{doc_id}")
async def delete_document(doc_id:int,x_tenant_id:str|None=Header(default=None),x_trace_id:str|None=Header(default=None)):
    trace_id=x_trace_id or uuid.uuid4().hex
    if not x_tenant_id or not x_tenant_id.isdigit():
        return _fail(400,"缺少或非法的X-Tenant-Id",trace_id)
    tenant_id=int(x_tenant_id)

    doc=await asyncio.to_thread(fetch_doc_for_ingest,doc_id,tenant_id)
    old=await asyncio.to_thread(fetch_chunk_ids,doc_id,tenant_id)
    if doc and old:
        client=get_client()
        try:
            client.delete(doc["collection_name"],filter=f"chunk_id in {[r['id']for r in old]}")
            client.flush(doc["collection_name"])
        except Exception as e:
            print(f"[warn]删除向量失败：{e}")
    await asyncio.to_thread(delete_chunks,doc_id,tenant_id)
    return _ok({"docId":doc_id,"deleted":len(old)},trace_id)