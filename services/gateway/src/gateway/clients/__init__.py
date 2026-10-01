"""模型客户端：统一接口 + OpenAI 兼容实现 + 测试用 fake。"""

from .base import ClientResult, ModelClient, ModelClientFactory
from .fake import FakeClientFactory, FakeModelClient
from .openai_compatible import OpenAICompatibleClient, OpenAICompatibleFactory

__all__ = [
    "ClientResult",
    "FakeClientFactory",
    "FakeModelClient",
    "ModelClient",
    "ModelClientFactory",
    "OpenAICompatibleClient",
    "OpenAICompatibleFactory",
]
