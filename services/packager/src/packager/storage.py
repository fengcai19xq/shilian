"""存储抽象：文件读取与产物写出都走这层，执行器本身不碰文件系统与网络。

- 测试用 `LocalStorage`（临时目录）
- 生产用对象存储实现，这里只留桩 `ObjectStorage`，签名 URL 由真实 SDK 提供
"""

from __future__ import annotations

from pathlib import Path
from typing import Protocol, runtime_checkable


class StorageError(RuntimeError):
    """存储层错误（读不到源文件、写出失败等）。"""


@runtime_checkable
class Storage(Protocol):
    """执行器依赖的最小存储接口。"""

    def read(self, uri: str) -> bytes:
        """读取源文件字节。读不到抛 StorageError。"""

    def write(self, name: str, data: bytes) -> str:
        """写出产物，返回产物 uri。"""

    def signed_url(self, uri: str, expires_in: int = 3600) -> str:
        """返回带时效的访问 URL。"""


class LocalStorage:
    """本地目录实现：源文件与产物都落在挂进沙箱的临时目录里。

    uri 支持两种写法：
    - 相对路径，如 `docs/d_2031.pdf`
    - 带 scheme 的对象存储风格 uri，如 `oss://docs/d_2031.pdf`（scheme 被剥掉后当相对路径用）
    """

    def __init__(self, root: str | Path, out_dir: str | Path | None = None) -> None:
        self.root = Path(root)
        self.out_dir = Path(out_dir) if out_dir is not None else self.root / "out"
        self.root.mkdir(parents=True, exist_ok=True)
        self.out_dir.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def _strip_scheme(uri: str) -> str:
        if "://" in uri:
            uri = uri.split("://", 1)[1]
        return uri.lstrip("/")

    def path_of(self, uri: str) -> Path:
        rel = self._strip_scheme(uri)
        path = (self.root / rel).resolve()
        # 沙箱内只允许读挂载目录，防目录穿越
        if not str(path).startswith(str(self.root.resolve())):
            raise StorageError(f"越界的 uri：{uri}")
        return path

    def read(self, uri: str) -> bytes:
        path = self.path_of(uri)
        try:
            return path.read_bytes()
        except OSError as exc:
            raise StorageError(f"读取失败：{uri}") from exc

    def write(self, name: str, data: bytes) -> str:
        path = self.out_dir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            path.write_bytes(data)
        except OSError as exc:
            raise StorageError(f"写出失败：{name}") from exc
        return str(path)

    def signed_url(self, uri: str, expires_in: int = 3600) -> str:
        return f"file://{self.path_of(uri)}?expires_in={expires_in}"


class ObjectStorage:
    """对象存储桩。

    生产环境在这里接 OSS/S3 SDK：凭据一律读环境变量，不在代码里硬编码。
    留桩是为了保证执行器只依赖 `Storage` 协议，不依赖具体实现。
    """

    def __init__(self, bucket: str, prefix: str = "") -> None:
        self.bucket = bucket
        self.prefix = prefix

    def read(self, uri: str) -> bytes:
        raise NotImplementedError("对象存储读取待接入 SDK")

    def write(self, name: str, data: bytes) -> str:
        raise NotImplementedError("对象存储写出待接入 SDK")

    def signed_url(self, uri: str, expires_in: int = 3600) -> str:
        raise NotImplementedError("签名 URL 待接入 SDK")
