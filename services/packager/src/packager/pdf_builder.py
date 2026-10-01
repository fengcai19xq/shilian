"""合订 PDF：封面 + 目录（带页码）+ 正文 + 每页水印与页脚页码。

- 中文用 reportlab 内置 CID 字体 STSong-Light，不依赖系统字体文件，沙箱内可用
- reportlab 开 invariant 模式、pypdf 写固定元数据，保证同一 manifest 重放产物一致
"""

from __future__ import annotations

import io

from pypdf import PageObject, PdfWriter
from reportlab.lib.colors import Color
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.pdfgen import canvas

from .layout import TOC_ROWS_PER_PAGE, Layout, open_pdf, selected_indices
from .models import Manifest

FONT = "STSong-Light"
_FIXED_META = {"/Producer": "shilian-packager", "/Creator": "shilian-packager"}

pdfmetrics.registerFont(UnicodeCIDFont(FONT))


def _new_canvas(buf: io.BytesIO, title: str) -> canvas.Canvas:
    c = canvas.Canvas(buf, pagesize=A4, invariant=1)
    c.setTitle(title)
    c.setAuthor("shilian-packager")
    c.setCreator("shilian-packager")
    c.setProducer("shilian-packager")
    return c


def watermark_text(manifest: Manifest) -> str:
    """水印为空时退回资料包标题，保证每页都有水印。"""
    return manifest.watermark or manifest.title


def _render_front(manifest: Manifest, layout: Layout) -> bytes:
    """封面与目录页。"""
    buf = io.BytesIO()
    c = _new_canvas(buf, manifest.title)
    width, height = A4

    # 封面
    c.setFont(FONT, 26)
    c.drawCentredString(width / 2, height * 0.62, manifest.title)
    c.setFont(FONT, 12)
    c.drawCentredString(width / 2, height * 0.62 - 40, f"资料包编号：{manifest.package_id}")
    missing = sum(1 for e in manifest.entries if e.is_missing)
    c.drawCentredString(
        width / 2,
        height * 0.62 - 62,
        f"收录 {len(layout.spans)} 项 · 缺失 {missing} 项（详见核对表）",
    )
    c.drawCentredString(
        width / 2, height * 0.25, "本资料包须经人工终审并登记外发，系统不自动对外发送"
    )
    c.showPage()

    # 目录：每页固定行数，与 layout.toc_pages 的计算口径一致
    spans = layout.spans
    for page_idx in range(layout.toc_pages):
        c.setFont(FONT, 18)
        c.drawCentredString(width / 2, height - 72, "目  录" if page_idx == 0 else "目录（续）")
        c.setFont(FONT, 11)
        y = height - 110
        rows = spans[page_idx * TOC_ROWS_PER_PAGE : (page_idx + 1) * TOC_ROWS_PER_PAGE]
        for span in rows:
            c.drawString(60, y, f"{span.entry.no}  {span.entry.std_name}")
            c.drawRightString(width - 60, y, str(span.start))
            y -= 22
        c.showPage()

    c.save()
    return buf.getvalue()


def _render_overlays(text: str, sizes: list[tuple[float, float]]) -> bytes:
    """为每页生成同尺寸的水印 + 页脚页码叠加层，一次生成多页。"""
    buf = io.BytesIO()
    c = _new_canvas(buf, "overlay")
    total = len(sizes)
    for idx, (w, h) in enumerate(sizes, start=1):
        c.setPageSize((w, h))
        c.saveState()
        c.setFillColor(Color(0.6, 0.6, 0.6, alpha=0.25))
        c.setFont(FONT, max(14, min(w, h) / 22))
        c.translate(w / 2, h / 2)
        c.rotate(35)
        for dy in (-h / 3, 0, h / 3):
            c.drawCentredString(0, dy, text)
        c.restoreState()
        c.setFillColor(Color(0.3, 0.3, 0.3))
        c.setFont(FONT, 9)
        c.drawCentredString(w / 2, 20, f"- {idx} / {total} -")
        c.showPage()
    c.save()
    return buf.getvalue()


def build_merged_pdf(manifest: Manifest, layout: Layout, sources: dict[str, bytes]) -> bytes:
    writer = PdfWriter()
    for page in open_pdf("front", _render_front(manifest, layout)).pages:
        writer.add_page(page)
    for span in layout.spans:
        for ref in span.entry.ordered_files:
            reader = open_pdf(ref.uri, sources[ref.uri])
            for idx in selected_indices(ref.uri, reader, ref):
                writer.add_page(reader.pages[idx])

    pages: list[PageObject] = list(writer.pages)
    sizes = [(float(p.mediabox.width), float(p.mediabox.height)) for p in pages]
    overlays = open_pdf("overlay", _render_overlays(watermark_text(manifest), sizes)).pages
    for page, overlay in zip(pages, overlays):
        box = page.mediabox
        if box.left or box.bottom:
            overlay.add_transformation([1, 0, 0, 1, float(box.left), float(box.bottom)])
        page.merge_page(overlay, over=True)

    writer.add_metadata({**_FIXED_META, "/Title": manifest.title})
    buf = io.BytesIO()
    writer.write(buf)
    return buf.getvalue()
