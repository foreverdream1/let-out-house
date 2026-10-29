from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    llm_api_key:str=""
    llm_base_url:str=""
    llm_model:str=""
    rag_service_base_url:str="http://127.0.0.1:8002"
    rag_timeout:float=15.0
    default_top_k:int=3

    class Config:
        env_prefix="LOH_"
        env_file=".env"
        env_file_encoding="utf-8"
        extra="ignore"


settings=Settings()