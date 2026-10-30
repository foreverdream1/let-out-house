

def split_text(text:str,size:int,overlap:int)->list[str]:
    """按段落居合道size字段，单端超长硬切，相邻块尾部重叠为overlap字符"""
    text=(text or "").strip()
    if not text:
        return []
    paras=[p.strip() for p in text.split("\n\n") if p.strip()]
    chunks,buf=[],""
    for para in paras:
        while len(para)>size:
            if buf:
                chunks.append(buf);buf=""
            chunks.append(para[:size])
            para=para[size-overlap:]
        if len(buf)+len(para)+2<=size:
            buf=f"{buf}\n\n{para}" if buf else para
        else:
            if buf:
                chunks.append(buf)
            buf=(chunks[-1][-overlap:]+"\n\n"+para) if (chunks and overlap)else para
    if buf:
        chunks.append(buf)
    return [c.strip() for c in chunks if c.strip()]