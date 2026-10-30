import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI,Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from rag_service.clients.milvus import close_client,get_client
from rag_service.routers import search, ingest


@asynccontextmanager
async def lifespan(app:FastAPI):
    get_client()        # 启动即建连，别等第一个请求才建（首次会慢 1-2 秒）
    yield
    close_client()

app=FastAPI(title="rag-service",version="0.1.0",lifespan=lifespan)
app.include_router(search.router)
app.include_router(ingest.router)

@app.exception_handler(RequestValidationError)
async def validation_handler(request:Request,exc:RequestValidationError):
    err=exc.errors()[0]
    field=".".join(str(x) for x in err.get("loc",[])[1:])
    return JSONResponse(status_code=200,content={
        "code":400,
        "msg":f"参数校验失败，{field} {err.get('msg','')}",
        "data":None,
        "traceId": request.headers.get("X-Trace-Id") or uuid.uuid4().hex,
    })

@app.get("/health")
def health():
    return {"status":"UP","service":"rag-service"}

