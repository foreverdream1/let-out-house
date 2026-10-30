from pydantic import  BaseModel,Field

class SearchRequest(BaseModel):
    kbIds:list[int]=Field(...,min_length=1,description="知识库ID列表")
    query:str=Field(...,min_length=2,max_length=2000)
    topK:int=Field(default=5,ge=1,le=20)
    minScore:float|None=Field(default=None,ge=0.0,le=1.0,description="相似度下限，不传用配置默认值")

class ChunkHit(BaseModel):
    chunkId:int
    documentId:int
    content:str
    score:float

class IngestRequest(BaseModel):
    docId:int
    kbId:int
    tenantId:int
    filePath:str
    fileType:str
    docName:str=""