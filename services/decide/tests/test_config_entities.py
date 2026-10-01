"""配置与实体识别。"""

from __future__ import annotations

from decide.config import Settings
from decide.entities import EntityMatcher


def test_entity_matcher_vocab_and_patterns():
    matcher = EntityMatcher(["航锂", "客户甲"], [r"\bHL-\d{3}[A-Z]?\b"])
    assert matcher.match("客户甲采购的 HL-280A 和 hl-300 价格") == ["客户甲", "HL-280A", "hl-300"]
    assert matcher.match("今天天气怎么样") == []


def test_settings_from_env(monkeypatch, tmp_path):
    vocab_file = tmp_path / "vocab.txt"
    vocab_file.write_text("# 注释\n客户乙\n\n示例项目部\n", encoding="utf-8")
    monkeypatch.setenv("DECIDE_ENTITY_VOCAB_FILE", str(vocab_file))
    monkeypatch.setenv("DECIDE_ENTITY_VOCAB", "客户丙, 客户丁")
    monkeypatch.setenv("DECIDE_PROBE_SCORE_THRESHOLD", "0.6")
    monkeypatch.setenv("DECIDE_BACKEND", "llm")
    s = Settings.from_env()
    assert s.probe_score_threshold == 0.6
    assert s.decider_backend == "llm"
    assert s.probe_top_k == 3
    for word in ["客户乙", "示例项目部", "客户丙", "客户丁", "航锂"]:
        assert word in s.entity_vocab
