"""合订本排版计划：算出每个条目在合订 PDF 里的起止页码。

目录页码、核对表页码列都以这里为准，保证两边一致。
页码以合订本物理页计，封面为第 1 页。
"""

from __future__ import annotations

import io
import math
from dataclasses import dataclass, field

from pypdf import PdfReader, PdfWriter
from pypdf.errors import PdfReadError

from .errors import PackagingError
from .models import Entry, FileRef, Manifest

COVER_PAGES = 1
TOC_ROWS_PER_PAGE = 28


@dataclass(frozen=True)
class EntrySpan:
    """单个条目在合订本里的页码区间（闭区间）。"""

    entry: Entry
    start: int
    end: int

    @property
    def label(self) -> str:
        return str(self.start) if self.start == self.end else f"{self.start}-{self.end}"


@dataclass
class Layout:
    toc_pages: int
    spans: list[EntrySpan] = field(default_factory=list)

    @property
    def body_start(self) -> int:
        return COVER_PAGES + self.toc_pages + 1

    @property
    def total_pages(self) -> int:
        return self.spans[-1].end if self.spans else COVER_PAGES + self.toc_pages

    def span_of(self, entry_no: str) -> EntrySpan | None:
        for span in self.spans:
            if span.entry.no == entry_no:
                return span
        return None


def open_pdf(uri: str, data: bytes) -> PdfReader:
    try:
        reader = PdfReader(io.BytesIO(data))
        _ = len(reader.pages)
    except (PdfReadError, ValueError, OSError) as exc:
        raise PackagingError(f"源文件不是可解析的 PDF：{uri}") from exc
    return reader


def selected_indices(uri: str, reader: PdfReader, ref: FileRef) -> list[int]:
    """返回要收录的 0 起算页下标；pages 为 None 时整篇收录。"""
    count = len(reader.pages)
    if ref.pages is None:
        return list(range(count))
    over = [p for p in ref.pages if p > count]
    if over:
        raise PackagingError(f"页码越界：{uri} 共 {count} 页，请求 {over}")
    return [p - 1 for p in ref.pages]


def subset_pdf(uri: str, data: bytes, ref: FileRef) -> bytes:
    """按 pages 截取源 PDF；整篇收录时原样返回，不重新编码。"""
    if ref.pages is None:
        return data
    reader = open_pdf(uri, data)
    writer = PdfWriter()
    for idx in selected_indices(uri, reader, ref):
        writer.add_page(reader.pages[idx])
    buf = io.BytesIO()
    writer.write(buf)
    return buf.getvalue()


def plan_layout(manifest: Manifest, sources: dict[str, bytes]) -> Layout:
    packable = [e for e in manifest.entries if e.packable]
    toc_pages = max(1, math.ceil(len(packable) / TOC_ROWS_PER_PAGE))
    layout = Layout(toc_pages=toc_pages)
    cursor = layout.body_start
    for entry in packable:
        count = 0
        for ref in entry.ordered_files:
            reader = open_pdf(ref.uri, sources[ref.uri])
            count += len(selected_indices(ref.uri, reader, ref))
        if count == 0:
            continue
        layout.spans.append(EntrySpan(entry=entry, start=cursor, end=cursor + count - 1))
        cursor += count
    return layout
