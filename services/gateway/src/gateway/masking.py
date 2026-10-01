"""L3 实体脱敏与还原。

约束：脱敏必须在请求体离开本进程之前完成（模型客户端只能拿到占位符），
模型返回后再按映射表还原，映射表只留在内存里，不随请求外发。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from .config import MaskingConfig

PLACEHOLDER_TEMPLATE = "[[{kind}_{index}]]"


@dataclass
class MaskingResult:
    """脱敏结果：脱敏后的载荷 + 占位符到原文的映射。"""

    payload: Any
    mapping: dict[str, str] = field(default_factory=dict)


class EntityMasker:
    """按可配置词表与正则做实体替换，替换是可逆的（同一原文复用同一占位符）。"""

    def __init__(self, config: MaskingConfig) -> None:
        self._config = config
        self._patterns = [(name, re.compile(regex)) for name, regex in config.patterns]
        # 长词优先，避免「航锂储能」被「航锂」类短词先吃掉
        self._literals: list[tuple[str, str]] = sorted(
            ((kind, word) for kind, words in config.literals.items() for word in words),
            key=lambda kv: len(kv[1]),
            reverse=True,
        )

    def mask(self, payload: Any) -> MaskingResult:
        mapping: dict[str, str] = {}
        reverse: dict[str, str] = {}
        counters: dict[str, int] = {}
        masked = self._walk(payload, mapping, reverse, counters)
        return MaskingResult(payload=masked, mapping=mapping)

    def restore(self, payload: Any, mapping: dict[str, str]) -> Any:
        """把占位符换回原文；无映射时原样返回。"""
        if not mapping:
            return payload
        if isinstance(payload, str):
            text = payload
            for placeholder, original in mapping.items():
                text = text.replace(placeholder, original)
            return text
        if isinstance(payload, dict):
            return {k: self.restore(v, mapping) for k, v in payload.items()}
        if isinstance(payload, list):
            return [self.restore(v, mapping) for v in payload]
        return payload

    def _walk(
        self,
        node: Any,
        mapping: dict[str, str],
        reverse: dict[str, str],
        counters: dict[str, int],
    ) -> Any:
        if isinstance(node, str):
            return self._mask_text(node, mapping, reverse, counters)
        if isinstance(node, dict):
            return {k: self._walk(v, mapping, reverse, counters) for k, v in node.items()}
        if isinstance(node, list):
            return [self._walk(v, mapping, reverse, counters) for v in node]
        return node

    def _mask_text(
        self,
        text: str,
        mapping: dict[str, str],
        reverse: dict[str, str],
        counters: dict[str, int],
    ) -> str:
        for kind, word in self._literals:
            if word and word in text:
                text = text.replace(word, self._placeholder(kind, word, mapping, reverse, counters))
        for kind, pattern in self._patterns:
            text = pattern.sub(
                lambda m, kind=kind: self._placeholder(kind, m.group(0), mapping, reverse, counters),
                text,
            )
        return text

    @staticmethod
    def _placeholder(
        kind: str,
        original: str,
        mapping: dict[str, str],
        reverse: dict[str, str],
        counters: dict[str, int],
    ) -> str:
        if original in reverse:
            return reverse[original]
        counters[kind] = counters.get(kind, 0) + 1
        placeholder = PLACEHOLDER_TEMPLATE.format(kind=kind.upper(), index=counters[kind])
        mapping[placeholder] = original
        reverse[original] = placeholder
        return placeholder
