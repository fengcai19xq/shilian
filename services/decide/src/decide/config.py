"""模块配置。密钥与地址一律读环境变量，不硬编码。"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

# 默认企业实体词表（脱敏示例词）。生产环境用 DECIDE_ENTITY_VOCAB_FILE 指向可配置词表。
DEFAULT_ENTITY_VOCAB: tuple[str, ...] = (
    "航锂",
    "航锂新能源",
    "融资部",
    "财务共享",
    "数智化中心",
    "研发中心",
    "差旅报销制度",
    "用章管理办法",
    "供应商准入制度",
)

# 结构化实体的正则：产品型号（如 HL-280A）、项目号（如 PRJ-2026-CITIC）、合同号。
DEFAULT_ENTITY_PATTERNS: tuple[str, ...] = (
    r"\bHL-\d{2,4}[A-Z]?\b",
    r"\bPRJ-\d{4}-[A-Z0-9]+\b",
    r"\bHT-\d{4}-\d{3,}\b",
)


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return float(raw)


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return int(raw)


def _load_vocab_file(path: str | None) -> list[str]:
    """词表文件：一行一个词，`#` 开头为注释。"""
    if not path:
        return []
    with open(path, encoding="utf-8") as fp:
        return [line.strip() for line in fp if line.strip() and not line.strip().startswith("#")]


@dataclass(frozen=True)
class Settings:
    # 探测检索：contracts/gateway.md 要求 mode=probe、top_k=3、不 rerank
    probe_top_k: int = 3
    probe_score_threshold: float = 0.55
    # 判定为「需要公司内部资料」的概率阈值
    need_internal_probability_threshold: float = 0.5
    # 判定实现：rule | jev | llm
    decider_backend: str = "rule"
    retrieval_base_url: str = "http://localhost:8001"
    gateway_base_url: str = "http://localhost:8002"
    http_timeout_seconds: float = 5.0
    entity_vocab: tuple[str, ...] = DEFAULT_ENTITY_VOCAB
    entity_patterns: tuple[str, ...] = DEFAULT_ENTITY_PATTERNS
    extra_config: dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_env(cls) -> Settings:
        vocab = tuple(DEFAULT_ENTITY_VOCAB) + tuple(
            _load_vocab_file(os.getenv("DECIDE_ENTITY_VOCAB_FILE"))
        )
        inline = os.getenv("DECIDE_ENTITY_VOCAB", "")
        if inline.strip():
            vocab = vocab + tuple(w.strip() for w in inline.split(",") if w.strip())
        return cls(
            probe_top_k=_env_int("DECIDE_PROBE_TOP_K", 3),
            probe_score_threshold=_env_float("DECIDE_PROBE_SCORE_THRESHOLD", 0.55),
            need_internal_probability_threshold=_env_float("DECIDE_NEED_INTERNAL_THRESHOLD", 0.5),
            decider_backend=os.getenv("DECIDE_BACKEND", "rule"),
            retrieval_base_url=os.getenv("DECIDE_RETRIEVAL_BASE_URL", "http://localhost:8001"),
            gateway_base_url=os.getenv("DECIDE_GATEWAY_BASE_URL", "http://localhost:8002"),
            http_timeout_seconds=_env_float("DECIDE_HTTP_TIMEOUT", 5.0),
            entity_vocab=tuple(dict.fromkeys(vocab)),
        )
