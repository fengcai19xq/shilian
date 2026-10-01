"""企业实体识别：公司名、产品型号、项目号、部门、制度名、客户名。词表与正则均可配置。"""

from __future__ import annotations

import re
from collections.abc import Iterable


class EntityMatcher:
    def __init__(self, vocab: Iterable[str] = (), patterns: Iterable[str] = ()) -> None:
        # 长词优先，避免「融资部」被更短的词覆盖导致展示不直观
        self._vocab = sorted({w.strip() for w in vocab if w.strip()}, key=len, reverse=True)
        self._patterns = [re.compile(p, re.IGNORECASE) for p in patterns]

    def match(self, text: str) -> list[str]:
        """返回命中的实体词，按出现顺序去重。"""
        hits: list[str] = []
        lowered = text.lower()
        for word in self._vocab:
            if word.lower() in lowered:
                hits.append(word)
        for pattern in self._patterns:
            for found in pattern.findall(text):
                value = found if isinstance(found, str) else found[0]
                if value not in hits:
                    hits.append(value)
        # 去重并保持稳定顺序
        return list(dict.fromkeys(hits))
