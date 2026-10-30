import pymysql
from pymysql.cursors import DictCursor
from rag_service.config import settings

def _write_conn():
    return pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_write_user,
        password=settings.mysql_write_password,
        database=settings.mysql_db,
        charset="utf8mb4",
        cursorclass=DictCursor
    )

def insert_chunks(rows:list[dict])->None:
    """rows:tenant_id kb_id document_id chunk_index content char_count milvus_pk"""
    if not rows: return
    sql=("INSERT INTO document_chunk (tenant_id,kb_id,document_id,chunk_index,content,char_count,milvus_pk) VALUES (%s,%s,%s,%s,%s,%s,%s)")
    conn=_write_conn()
    try:
        with conn.cursor() as cur:
            cur.executemany(sql,[(r["tenant_id"],r["kb_id"],r["document_id"],r["chunk_index"],r["content"],r["char_count"],r["milvus_pk"])for r in rows])
        conn.commit()
    finally:
        conn.close()


def delete_chunks(document_id:int,tenant_id:int)->None:
    conn=_write_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM document_chunk WHERE document_id=%s AND tenant_id=%s",[document_id,tenant_id])
        conn.commit()
    finally:
        conn.close()

def update_milvus_pks(pairs:list[tuple[int,int]])->None:
    """pairs:[(chunk_id,milvus_pk)]"""
    if not pairs:
        return
    conn=_write_conn()
    try:
        with conn.cursor() as cur:
            cur.executemany("UPDATE document_chunk SET milvus_pk = %s WHERE id = %s",[(pk,cid)for cid,pk in pairs])
        conn.commit()
    finally:
        conn.close()