#!/usr/bin/env python3
"""
OpenCode Android Remote - Word 文档一键转换生成器
将全量技术交接档案与对话记录转换为排版精美的标准 Microsoft Word (.docx) 文档。
"""

import os
import sys
import re
import docx
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import parse_xml
from docx.oxml.ns import nsdecls

def set_cell_background(cell, fill_hex):
    shading_elm = parse_xml(f'<w:shd {nsdecls("w")} w:fill="{fill_hex}"/>')
    cell._tc.get_or_add_tcPr().append(shading_elm)

def build_docx_from_markdown(md_path: str, docx_path: str):
    print(f"📖 正在读取 Markdown 交接文档: {md_path}")
    if not os.path.exists(md_path):
        print(f"❌ 错误: 找不到输入文件 {md_path}")
        return

    with open(md_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    doc = docx.Document()

    # 页面边距设置 (标准 A4 / 1 英寸)
    sections = doc.sections
    for section in sections:
        section.top_margin = Inches(1)
        section.bottom_margin = Inches(1)
        section.left_margin = Inches(1)
        section.right_margin = Inches(1)

    # 全局正文字体设置
    style = doc.styles['Normal']
    font = style.font
    font.name = 'Segoe UI'
    font.size = Pt(10.5)
    font.color.rgb = RGBColor(0x24, 0x29, 0x2F)

    in_code_block = False
    code_lines = []
    code_lang = ""

    in_table = False
    table_rows = []

    def flush_table():
        nonlocal in_table, table_rows
        if not table_rows:
            return
        
        # 过滤分割线
        clean_rows = []
        for r in table_rows:
            if all(re.match(r'^:?-+:?$', c.strip()) for c in r if c.strip()):
                continue
            clean_rows.append(r)

        if not clean_rows:
            table_rows = []
            in_table = False
            return

        cols_count = max(len(r) for r in clean_rows)
        tbl = doc.add_table(rows=len(clean_rows), cols=cols_count)
        tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
        tbl.style = 'Table Grid'

        for i, row_data in enumerate(clean_rows):
            is_header = (i == 0)
            for j in range(cols_count):
                cell = tbl.cell(i, j)
                text = row_data[j] if j < len(row_data) else ""
                cell.text = text.strip()
                p = cell.paragraphs[0]
                p.paragraph_format.space_before = Pt(4)
                p.paragraph_format.space_after = Pt(4)
                run = p.runs[0] if p.runs else p.add_run()
                run.font.size = Pt(9.5)
                if is_header:
                    run.bold = True
                    run.font.color.rgb = RGBColor(0x09, 0x69, 0xDA)
                    set_cell_background(cell, "F6F8FA")
                else:
                    if i % 2 == 1:
                        set_cell_background(cell, "FFFFFF")
                    else:
                        set_cell_background(cell, "FAFBFC")

        doc.add_paragraph()
        table_rows = []
        in_table = False

    def flush_code():
        nonlocal in_code_block, code_lines, code_lang
        if not code_lines:
            return
        code_text = "".join(code_lines)
        
        # 使用浅灰表格模拟语法高亮卡片
        table = doc.add_table(rows=1, cols=1)
        table.alignment = WD_TABLE_ALIGNMENT.CENTER
        cell = table.cell(0, 0)
        set_cell_background(cell, "F6F8FA")
        
        p = cell.paragraphs[0]
        p.paragraph_format.space_before = Pt(4)
        p.paragraph_format.space_after = Pt(4)
        run = p.add_run(code_text.rstrip())
        run.font.name = 'Consolas'
        run.font.size = Pt(9)
        run.font.color.rgb = RGBColor(0x1F, 0x23, 0x28)
        
        doc.add_paragraph()
        code_lines = []
        in_code_block = False

    for line in lines:
        stripped = line.strip()

        # 代码块起止解析
        if stripped.startswith("```"):
            if in_table:
                flush_table()
            if in_code_block:
                flush_code()
            else:
                in_code_block = True
                code_lang = stripped[3:].strip()
                code_lines = []
            continue

        if in_code_block:
            code_lines.append(line)
            continue

        # 表格行解析
        if stripped.startswith("|") and stripped.endswith("|"):
            cells = [c.strip() for c in stripped.strip("|").split("|")]
            table_rows.append(cells)
            in_table = True
            continue
        elif in_table:
            flush_table()

        # 标题解析
        if stripped.startswith("# "):
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(14)
            p.paragraph_format.space_after = Pt(8)
            run = p.add_run(stripped[2:])
            run.bold = True
            run.font.size = Pt(20)
            run.font.color.rgb = RGBColor(0x09, 0x69, 0xDA)
        elif stripped.startswith("## "):
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(12)
            p.paragraph_format.space_after = Pt(6)
            run = p.add_run(stripped[3:])
            run.bold = True
            run.font.size = Pt(15)
            run.font.color.rgb = RGBColor(0x1F, 0x23, 0x28)
        elif stripped.startswith("### "):
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(10)
            p.paragraph_format.space_after = Pt(4)
            run = p.add_run(stripped[4:])
            run.bold = True
            run.font.size = Pt(12.5)
            run.font.color.rgb = RGBColor(0x31, 0x37, 0x3D)
        elif stripped.startswith("#### "):
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(8)
            p.paragraph_format.space_after = Pt(2)
            run = p.add_run(stripped[5:])
            run.bold = True
            run.font.size = Pt(11)
            run.font.color.rgb = RGBColor(0x09, 0x69, 0xDA)
        elif stripped.startswith("- ") or stripped.startswith("* "):
            p = doc.add_paragraph(style='List Bullet')
            p.paragraph_format.space_before = Pt(1)
            p.paragraph_format.space_after = Pt(1)
            content = stripped[2:]
            parts = re.split(r'(\*\*.*?\*\*)', content)
            for part in parts:
                if part.startswith("**") and part.endswith("**"):
                    r = p.add_run(part[2:-2])
                    r.bold = True
                else:
                    p.add_run(part)
        elif re.match(r'^\d+\.\s', stripped):
            p = doc.add_paragraph(style='List Number')
            p.paragraph_format.space_before = Pt(1)
            p.paragraph_format.space_after = Pt(1)
            content = re.sub(r'^\d+\.\s', '', stripped)
            parts = re.split(r'(\*\*.*?\*\*)', content)
            for part in parts:
                if part.startswith("**") and part.endswith("**"):
                    r = p.add_run(part[2:-2])
                    r.bold = True
                else:
                    p.add_run(part)
        elif stripped == "---":
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(6)
            p.paragraph_format.space_after = Pt(6)
            run = p.add_run("―" * 45)
            run.font.color.rgb = RGBColor(0xD0, 0xD7, 0xDE)
        elif stripped:
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(2)
            p.paragraph_format.space_after = Pt(3)
            parts = re.split(r'(\*\*.*?\*\*)', stripped)
            for part in parts:
                if part.startswith("**") and part.endswith("**"):
                    r = p.add_run(part[2:-2])
                    r.bold = True
                else:
                    p.add_run(part)

    if in_table:
        flush_table()
    if in_code_block:
        flush_code()

    doc.save(docx_path)
    file_size_kb = os.path.getsize(docx_path) / 1024
    print("=" * 60)
    print(f"🎉 Word 文档生成完成: {docx_path}")
    print(f"📏 文档大小: {file_size_kb:.2f} KB")
    print("=" * 60)

if __name__ == "__main__":
    src_md = "OpenCode_Android_Remote_全量技术交接与对话档案_v1.5.md"
    out_docx = "OpenCode_Android_Remote_全量技术交接与对话档案_v1.5.docx"
    
    if len(sys.argv) > 1:
        src_md = sys.argv[1]
    if len(sys.argv) > 2:
        out_docx = sys.argv[2]
        
    build_docx_from_markdown(src_md, out_docx)
