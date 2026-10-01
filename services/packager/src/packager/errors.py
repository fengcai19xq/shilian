"""执行器对外抛出的错误类型。"""

from __future__ import annotations


class PackagingError(ValueError):
    """manifest 或源文件不满足成册条件（源文件不是 PDF、页码越界等）。"""
