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


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


DECIDER_BACKENDS: tuple[str, ...] = ("rule", "jev", "llm")


def parse_traffic_split(raw: str) -> tuple[tuple[str, float], ...]:
    """解析 `rule:80,jev:10,llm:10` 形式的流量配比，按占比归一化，未知后端或负数直接报错。"""
    if not raw or not raw.strip():
        return ()
    weights: dict[str, float] = {}
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        name, sep, value = item.partition(":")
        name = name.strip().lower()
        if not sep or name not in DECIDER_BACKENDS:
            raise ValueError(f"非法的 ab.traffic_split 项：{item!r}")
        weight = float(value)
        if weight < 0:
            raise ValueError(f"ab.traffic_split 占比不能为负：{item!r}")
        weights[name] = weights.get(name, 0.0) + weight
    total = sum(weights.values())
    if total <= 0:
        raise ValueError("ab.traffic_split 占比之和必须大于 0")
    return tuple((name, w / total) for name, w in weights.items() if w > 0)


def _parse_backends(raw: str) -> tuple[str, ...]:
    names = tuple(dict.fromkeys(n.strip().lower() for n in raw.split(",") if n.strip()))
    for name in names:
        if name not in DECIDER_BACKENDS:
            raise ValueError(f"非法的判定后端：{name!r}")
    return names


@dataclass(frozen=True)
class AbConfig:
    """decide() 灰度配置。

    - traffic_split：按 user_id 哈希稳定分桶，元素为 (后端, 占比)，占比已归一化；为空表示不分桶，
      全量走 Settings.decider_backend
    - shadow：影子后端列表（如 ("jev",)）；非空时主路径固定用 rule 返回，同时调用影子后端只记录对比
    - shadow_async：影子调用是否放后台线程（默认是，不增加响应延迟）
    - salt：分桶哈希盐，换盐即整体重新洗桶
    """

    traffic_split: tuple[tuple[str, float], ...] = ()
    shadow: tuple[str, ...] = ()
    shadow_async: bool = True
    salt: str = "decide-ab-v1"

    @property
    def enabled(self) -> bool:
        return bool(self.traffic_split or self.shadow)

    @classmethod
    def from_env(cls) -> AbConfig:
        return cls(
            traffic_split=parse_traffic_split(os.getenv("DECIDE_AB_TRAFFIC_SPLIT", "")),
            shadow=_parse_backends(os.getenv("DECIDE_AB_SHADOW", "")),
            shadow_async=_env_bool("DECIDE_AB_SHADOW_ASYNC", True),
            salt=os.getenv("DECIDE_AB_SALT", "decide-ab-v1"),
        )


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
    # jev / llm 单次判定超时（秒），超时或失败按 decider_fallback 降级
    decider_timeout_seconds: float = 2.0
    # 降级目标：rule（纯规则兜底，默认）；none 表示不降级、异常直接抛出（仅调试用）
    decider_fallback: str = "rule"
    # 调 gateway 判定时声明的密级（问题文本本身的密级，gateway 据此选合规通道）
    decider_sensitivity: str = "L2"
    ab: AbConfig = field(default_factory=AbConfig)
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
            decider_timeout_seconds=_env_float("DECIDE_DECIDER_TIMEOUT", 2.0),
            decider_fallback=os.getenv("DECIDE_DECIDER_FALLBACK", "rule"),
            decider_sensitivity=os.getenv("DECIDE_DECIDER_SENSITIVITY", "L2"),
            ab=AbConfig.from_env(),
            retrieval_base_url=os.getenv("DECIDE_RETRIEVAL_BASE_URL", "http://localhost:8001"),
            gateway_base_url=os.getenv("DECIDE_GATEWAY_BASE_URL", "http://localhost:8002"),
            http_timeout_seconds=_env_float("DECIDE_HTTP_TIMEOUT", 5.0),
            entity_vocab=tuple(dict.fromkeys(vocab)),
        )
