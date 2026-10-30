from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # ---- Milvus ----
    # 106 服务器上的 standalone 实例
    milvus_uri: str = "http://106.14.90.100:19530"
    # standalone 默认无认证；将来开了 authorizationEnabled 再填
    milvus_user: str = ""
    milvus_password: str = ""

    # ---- Embedding（硅基流动，OpenAI 兼容）----
    embed_base_url: str = "https://api.siliconflow.cn/v1"
    embed_model: str = "BAAI/bge-m3"
    embed_api_key: str = ""
    embed_dim: int = 1024          # bge-m3 固定 1024，改了必须重建 collection
    embed_timeout: float = 30.0

    # ---- MySQL（只读回查正文）----
    mysql_host: str = "106.14.90.100"
    mysql_port: int = 3306
    mysql_user: str = "loh_ro"
    mysql_password: str = ""
    mysql_db: str = "let_out_house"

    # ---- MySQL 写账号（仅入库链路写 document_chunk 用）----
    mysql_write_user: str = "loh_app"
    mysql_write_password: str = ""

    # ---- 入库（切片）参数 ----
    chunk_size: int = 500          # 单块目标字符数
    chunk_overlap: int = 50        # 相邻块重叠字符数，防答案被切断
    embed_batch: int = 16          # 每次请求 embedding 的条数，控限流

    # ---- 检索默认参数 ----
    default_top_k: int = 5
    max_top_k: int = 20
    # Milvus 返回候选数 > topK，给上层 rerank 留空间
    search_ef: int = 64
    min_score:float=0.40

    @property
    def milvus_token(self) -> str:
        """MilvusClient 要的是 "user:password" 形式的 token，没配账号就返回空串（无认证模式）。"""
        if self.milvus_user and self.milvus_password:
            return f"{self.milvus_user}:{self.milvus_password}"
        return ""

    class Config:
        env_prefix = "LOH_"
        env_file = ".env"
        env_file_encoding = "utf-8"
        extra = "ignore"


settings = Settings()
