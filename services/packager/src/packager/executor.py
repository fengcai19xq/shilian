"""成册执行器入口。

`build_artifacts` 是纯函数：manifest + 读文件函数 → {文件名: 字节}，不查库、不调模型、不做匹配判断。
`execute` 在此之上把产物写进 storage，返回产物 uri 列表。
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from typing import Any

from .layout import plan_layout
from .models import Artifact, Manifest, OutputKind, PackageResult
from .pdf_builder import build_merged_pdf
from .storage import Storage
from .xlsx_builder import build_checklist_xlsx
from .zip_builder import build_zip, zip_members

Reader = Callable[[str], bytes]


def artifact_filename(manifest: Manifest, kind: OutputKind) -> str:
    pid = manifest.package_id
    return {
        OutputKind.ZIP: f"{pid}/{pid}.zip",
        OutputKind.MERGED_PDF: f"{pid}/{pid}_合订本.pdf",
        OutputKind.CHECKLIST_XLSX: f"{pid}/{pid}_核对表.xlsx",
    }[kind]


def _load_sources(manifest: Manifest, read: Reader) -> dict[str, bytes]:
    """只读取入册条目的源文件，同一 uri 只读一次；missing 条目不触发任何读取。"""
    sources: dict[str, bytes] = {}
    for entry in manifest.entries:
        if not entry.packable:
            continue
        for ref in entry.ordered_files:
            if ref.uri not in sources:
                sources[ref.uri] = read(ref.uri)
    return sources


def _parse(manifest: Manifest | Mapping[str, Any]) -> Manifest:
    return manifest if isinstance(manifest, Manifest) else Manifest.model_validate(manifest)


def build_artifacts(
    manifest: Manifest | Mapping[str, Any], read: Reader
) -> dict[OutputKind, bytes]:
    """纯函数核心：按 manifest.outputs 顺序生成产物字节，重复的 output 只生成一次。"""
    m = _parse(manifest)
    sources = _load_sources(m, read)
    layout = plan_layout(m, sources)
    members = zip_members(m)

    builders: dict[OutputKind, Callable[[], bytes]] = {
        OutputKind.ZIP: lambda: build_zip(m, sources),
        OutputKind.MERGED_PDF: lambda: build_merged_pdf(m, layout, sources),
        OutputKind.CHECKLIST_XLSX: lambda: build_checklist_xlsx(m, layout, members),
    }
    out: dict[OutputKind, bytes] = {}
    for kind in m.outputs:
        if kind not in out:
            out[kind] = builders[kind]()
    return out


def execute(manifest: Manifest | Mapping[str, Any], storage: Storage) -> PackageResult:
    """执行成册并写出产物。产物仅落存储，不对外发送，须人工终审后登记外发。"""
    m = _parse(manifest)
    result = PackageResult(
        package_id=m.package_id,
        missing_entries=[e.no for e in m.entries if e.is_missing],
    )
    for kind, data in build_artifacts(m, storage.read).items():
        name = artifact_filename(m, kind)
        uri = storage.write(name, data)
        result.artifacts.append(Artifact(kind=kind, filename=name, uri=uri, size=len(data)))
    return result
