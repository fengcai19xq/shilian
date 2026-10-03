"""航锂智能体 —— 成册执行器（纯函数，沙箱内运行），契约见 contracts/packaging.md。"""

from .errors import PackagingError
from .executor import artifact_filename, build_artifacts, execute
from .models import Artifact, Entry, EntryStatus, FileRef, Manifest, OutputKind, PackageResult
from .storage import LocalStorage, ObjectStorage, Storage, StorageError

__all__ = [
    "Artifact",
    "Entry",
    "EntryStatus",
    "FileRef",
    "LocalStorage",
    "Manifest",
    "ObjectStorage",
    "OutputKind",
    "PackageResult",
    "PackagingError",
    "Storage",
    "StorageError",
    "artifact_filename",
    "build_artifacts",
    "execute",
]
