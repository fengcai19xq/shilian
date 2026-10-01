"""产物命名规则，集中一处便于与契约对齐。

- zip 目录：`01_营业执照/`（编号首段补零到两位）
- zip 文件：`编号_标准名称_期间.pdf`（无期间时省略该段；同一条目多份时追加序号）
"""

from __future__ import annotations

import re

from .models import Entry, FileRef

_ILLEGAL = re.compile(r'[\\/:*?"<>|\r\n\t]+')


def sanitize(text: str) -> str:
    """清掉文件名里的非法字符与首尾空白，空串回退为下划线。"""
    cleaned = _ILLEGAL.sub("_", text).strip().strip(".")
    cleaned = re.sub(r"\s+", " ", cleaned)
    return cleaned or "_"


def pad_no(no: str) -> str:
    """编号首段补零到两位：`1` → `01`，`3.1` → `03.1`，非数字编号原样返回。"""
    head, sep, rest = no.partition(".")
    if head.isdigit():
        head = head.zfill(2)
    return f"{head}{sep}{rest}"


def dir_name(entry: Entry) -> str:
    """zip 内的条目目录名。"""
    return f"{pad_no(sanitize(entry.no))}_{sanitize(entry.std_name)}"


def _suffix(uri: str) -> str:
    tail = uri.rsplit("/", 1)[-1]
    if "." in tail:
        return "." + tail.rsplit(".", 1)[-1].lower()
    return ".pdf"


def file_name(entry: Entry, ref: FileRef, index: int = 0, total: int = 1) -> str:
    """zip 内的文件名：`编号_标准名称_期间.pdf`。"""
    parts = [pad_no(sanitize(entry.no)), sanitize(entry.std_name)]
    period = ref.period or entry.period
    if period:
        parts.append(sanitize(period))
    if total > 1:
        parts.append(str(index + 1))
    return "_".join(parts) + _suffix(ref.uri)
