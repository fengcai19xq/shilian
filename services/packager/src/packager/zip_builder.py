"""zip 产物：按清单编号建目录，文件名 `编号_标准名称_期间.pdf`。

为保证重放一致，zip 内所有成员使用固定时间戳与固定压缩参数，成员顺序按 manifest 顺序。
"""

from __future__ import annotations

import io
import zipfile

from .layout import subset_pdf
from .models import Manifest
from .naming import dir_name, file_name

# zip 格式最早只能表示 1980-01-01，固定用它消除时间戳差异
_FIXED_DATE = (1980, 1, 1, 0, 0, 0)


def _add(zf: zipfile.ZipFile, arcname: str, data: bytes) -> None:
    info = zipfile.ZipInfo(arcname, date_time=_FIXED_DATE)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zf.writestr(info, data, compresslevel=6)


def zip_members(manifest: Manifest) -> dict[str, list[str]]:
    """条目编号 → zip 内路径列表；missing 与无文件条目不出现。"""
    members: dict[str, list[str]] = {}
    for entry in manifest.entries:
        if not entry.packable:
            continue
        folder = dir_name(entry)
        files = entry.ordered_files
        members[entry.no] = [
            f"{folder}/{file_name(entry, ref, idx, len(files))}" for idx, ref in enumerate(files)
        ]
    return members


def build_zip(manifest: Manifest, sources: dict[str, bytes]) -> bytes:
    members = zip_members(manifest)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for entry in manifest.entries:
            if entry.no not in members:
                continue
            for ref, arcname in zip(entry.ordered_files, members[entry.no]):
                _add(zf, arcname, subset_pdf(ref.uri, sources[ref.uri], ref))
    return buf.getvalue()
