from pathlib import Path

def parse_file(path:str,file_type:str)->str:
    p=Path(path)
    if not p.exists():
        raise FileNotFoundError(f"文件不存在:{path}")
    t=file_type.lower()
    if t=="pdf":
        from pypdf import PdfReader
        return "\n\n".join((pg.extract_text() or "") for pg in PdfReader(str(p)).pages)
    if t=="docx":
        from docx import Document as Docu
        return "\n\n".join(par.text for par in Docu(str(p)).paragraphs)
    if t in ("md","txt"):
        return p.read_text(encoding="utf-8",errors="ignore")
    raise ValueError(f"不支持的文件格式：{file_type}")