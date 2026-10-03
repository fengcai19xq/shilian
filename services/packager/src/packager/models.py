"""manifest 数据模型，字段与 contracts/packaging.md 一致。

只增不改：契约里没有的字段一律作为可选字段追加，缺省值保证老 manifest 仍可解析。
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator


class EntryStatus(str, Enum):
    """清单项三态，语义见 contracts/matching.md。"""

    MATCHED = "matched"
    PENDING = "pending"
    MISSING = "missing"


class OutputKind(str, Enum):
    """契约里定义的三种产物。"""

    ZIP = "zip"
    MERGED_PDF = "merged_pdf"
    CHECKLIST_XLSX = "checklist_xlsx"


class FileRef(BaseModel):
    """清单项下挂的单个文件引用。"""

    model_config = ConfigDict(extra="ignore")

    uri: str
    # None 表示整篇收录；给了列表则只取这些页（1 起算）
    pages: list[int] | None = None
    order: int = 1
    # 可选：单文件粒度的期间，优先级高于 entry.period
    period: str | None = None

    @field_validator("pages")
    @classmethod
    def _check_pages(cls, pages: list[int] | None) -> list[int] | None:
        if pages is not None and any(p < 1 for p in pages):
            raise ValueError("pages 为 1 起算的页码，不能小于 1")
        return pages


class Entry(BaseModel):
    """清单项。"""

    model_config = ConfigDict(extra="ignore")

    no: str
    std_name: str
    files: list[FileRef] = Field(default_factory=list)
    status: EntryStatus = EntryStatus.MATCHED
    note: str = ""
    # 可选：期间（用于文件命名）与缺件责任部门（核对表用）
    period: str | None = None
    owner_dept: str | None = None

    @property
    def ordered_files(self) -> list[FileRef]:
        """按 order 稳定排序；order 相同的保持 manifest 里的先后顺序。"""
        return [f for _, f in sorted(enumerate(self.files), key=lambda p: (p[1].order, p[0]))]

    @property
    def is_missing(self) -> bool:
        return self.status is EntryStatus.MISSING

    @property
    def packable(self) -> bool:
        """是否入册：missing 不入册；没有文件的条目同样不入册。"""
        return not self.is_missing and bool(self.files)


class Manifest(BaseModel):
    """成册执行器的唯一输入。"""

    model_config = ConfigDict(extra="ignore")

    package_id: str
    title: str
    watermark: str = ""
    outputs: list[OutputKind] = Field(default_factory=lambda: list(OutputKind))
    entries: list[Entry] = Field(default_factory=list)


class Artifact(BaseModel):
    """单个产物的落地结果。"""

    kind: OutputKind
    filename: str
    uri: str
    size: int


class PackageResult(BaseModel):
    """执行结果。签名 URL 由调用方按 storage 实现自行换取。"""

    package_id: str
    artifacts: list[Artifact] = Field(default_factory=list)
    missing_entries: list[str] = Field(default_factory=list)
    # 红线：资料包生成后仍需人工终审并登记外发，系统不自动对外发送
    requires_manual_review: bool = True

    def artifact(self, kind: OutputKind) -> Artifact | None:
        for a in self.artifacts:
            if a.kind is kind:
                return a
        return None
