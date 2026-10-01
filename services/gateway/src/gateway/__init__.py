"""航锂智能体统一模型网关。

对外入口：`GatewayService.invoke()` 与 FastAPI 的 `POST /v1/invoke`。
"""

from .schemas import (
    Channel,
    InvokeRequest,
    InvokeResponse,
    Principal,
    Purpose,
    Sensitivity,
    Usage,
)
from .service import GatewayService

__all__ = [
    "Channel",
    "GatewayService",
    "InvokeRequest",
    "InvokeResponse",
    "Principal",
    "Purpose",
    "Sensitivity",
    "Usage",
]
