from pydantic import BaseModel

class HistoryMessage(BaseModel):
    role:str
    content:str



class ChatRequest(BaseModel):
    conversationId:int
    agentId:int
    query:str
    stream:bool=True
    history:list[HistoryMessage]=[]
    systemPrompt:str|None=None
    modelName:str|None=None
    temperature:float|None=None
    maxToken:int|None=None
    topK:int|None=None
    ragEnable:bool|None=None
    kbIds:list[int]=[]