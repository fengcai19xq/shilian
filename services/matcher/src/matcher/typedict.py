"""资料类型词典：把清单里的自然语言资料名称归一到 std_type。

词典未命中时返回 unknown，**不做猜测**（猜错会把单体报表当合并报表发出去）。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml

from .config import DATA_DIR
from .models import UNKNOWN_TYPE

_NOISE = re.compile(r"[\s　()（）【】\[\]〈〉<>《》:：,，.。、/\\\-_*+]")


def _normalize(text: str) -> str:
    """去噪并统一大小写，便于别名匹配。"""
    return _NOISE.sub("", (text or "")).upper()


@dataclass(frozen=True)
class TypeHit:
    std_type: str
    std_name: str | None
    status: str  # resolved | unknown


class TypeDictionary:
    def __init__(self, types: dict[str, dict]):
        self._std_names: dict[str, str] = {}
        # 别名 -> std_type，按别名长度倒序匹配，避免「章程」吃掉「公司章程」
        self._alias_index: list[tuple[str, str]] = []
        for std_type, spec in (types or {}).items():
            std_name = (spec or {}).get("std_name", std_type)
            self._std_names[std_type] = std_name
            aliases = set((spec or {}).get("aliases") or [])
            aliases.add(std_name)
            for alias in aliases:
                key = _normalize(alias)
                if key:
                    self._alias_index.append((key, std_type))
        self._alias_index.sort(key=lambda kv: len(kv[0]), reverse=True)

    @classmethod
    def load(cls, path: Path | str | None = None) -> TypeDictionary:
        path = Path(path) if path else DATA_DIR / "types.yaml"
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        return cls(data.get("types") or {})

    def std_name(self, std_type: str) -> str | None:
        return self._std_names.get(std_type)

    def resolve(self, text: str) -> TypeHit:
        """在资料名称中查找最长命中的别名。"""
        key = _normalize(text)
        if not key:
            return TypeHit(UNKNOWN_TYPE, None, "unknown")
        for alias, std_type in self._alias_index:
            if alias in key:
                return TypeHit(std_type, self._std_names.get(std_type), "resolved")
        return TypeHit(UNKNOWN_TYPE, None, "unknown")


@lru_cache(maxsize=4)
def default_dictionary(path: str | None = None) -> TypeDictionary:
    return TypeDictionary.load(path)
