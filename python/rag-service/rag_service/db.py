import pymysql
from pymysql.cursors import DictCursor
from rag_service.config import settings

def _connect():
    return pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_db,
        charset="utf8mb4",
        cursorclass=DictCursor,
        connect_timeout=5,
        read_timeout=10
    )

def fetch_chunks(tenant_id:int,milvus_pks:list[int])->dict[int,dict]:
    """milvus_pk->分片行。tenant_id条件区分租户身份"""
    if not milvus_pks:
        return {}
    ph=",".join(["%s"]*len(milvus_pks))
    sql = (
        "SELECT c.id, c.document_id, c.chunk_index, c.content, c.milvus_pk, d.doc_name "
        "FROM document_chunk c "
        "JOIN document d ON d.id = c.document_id AND d.deleted = 0 AND d.tenant_id = %s "
        f"WHERE c.tenant_id = %s AND c.status = 1 AND c.milvus_pk IN ({ph})"
    )
    conn = _connect()
    try:
        with conn.cursor() as cur:
            cur.execute(sql,[tenant_id,tenant_id,*milvus_pks])
            return {row["milvus_pk"]:row for row in cur.fetchall()}
    finally:
        conn.close()

def fetch_kb_collections(tenant_id:int,kb_ids:list[int])->dict[int,dict]:
    """kb_id->collection_name   注意knowledge——base有逻辑删除，要带deleted=0"""
    if not kb_ids:
        return {}
    ph=",".join(["%s"]*len(kb_ids))
    sql=(f"SELECT id ,collection_name FROM knowledge_base WHERE tenant_id=%s AND status=1 AND deleted=0 AND id in ({ph})")
    conn=_connect()
    try:
        with conn.cursor() as cur:
            cur.execute(sql,[tenant_id,*kb_ids])
            return {row["id"]:row["collection_name"] for row in cur.fetchall()}
    finally:
        conn.close()

def fetch_doc_for_ingest(doc_id:int,tenant_id:int)->dict|None:
    sql=("SELECT d.id,d.kb_id,d.doc_name,d.file_type,kb.collection_name FROM document d JOIN knowledge_base kb ON kb.id = d.kb_id AND kb.tenant_id = %s AND kb.deleted = 0 WHERE d.id = %s AND d.tenant_id = %s AND d.deleted = 0")
    conn=_connect()
    try:
        with conn.cursor() as cur:
            cur.execute(sql,[tenant_id,doc_id,tenant_id])
            return cur.fetchone()
    finally:
        conn.close()

def fetch_chunk_ids(document_id:int,tenant_id:int)->list[dict]:
    """返回[{id,chunk_index}],用于删除Milvus向量+回写milvus_pk"""
    sql="SELECT id,chunk_index FROM document_chunk WHERE document_id = %s AND tenant_id = %s ORDER BY chunk_index"
    conn=_connect()
    try:
        with conn.cursor() as cur:
            cur.execute(sql,[document_id,tenant_id])
            return list(cur.fetchall())
    finally:
        conn.close()