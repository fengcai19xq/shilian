"""清单解析、约束抽取、类型归一。"""

from __future__ import annotations

import io

from openpyxl import Workbook

from matcher.config import ParserConfig
from matcher.extract import extract_constraints, extract_periods
from matcher.models import UNKNOWN_TYPE, Scope
from matcher.parsing import ChecklistParser
from matcher.typedict import TypeDictionary


def _xlsx(rows: list[list]) -> bytes:
    wb = Workbook()
    ws = wb.active
    for row in rows:
        ws.append(row)
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def test_dictionary_has_15_to_20_types_with_aliases():
    d = TypeDictionary.load()
    assert 15 <= len(d._std_names) <= 20
    assert d.resolve("经审计财务报表").std_type == "AUDIT_REPORT"


def test_dictionary_miss_is_unknown_not_guess():
    hit = TypeDictionary.load().resolve("环评批复文件")
    assert hit.std_type == UNKNOWN_TYPE and hit.status == "unknown" and hit.std_name is None


def test_longest_alias_wins():
    d = TypeDictionary({"A": {"std_name": "章程", "aliases": ["章程"]},
                        "B": {"std_name": "章程修正案", "aliases": ["章程修正案"]}})
    assert d.resolve("最新章程修正案").std_type == "B"


def test_extract_contract_example():
    c = extract_constraints("最近三年审计报告（合并口径，加盖公章）", reference_year=2023)
    assert c.period == ["2021", "2022", "2023"]
    assert c.scope is Scope.CONSOLIDATED
    assert c.stamp_required is True
    assert c.copies is None


def test_extract_variants():
    assert extract_periods("2021-2023年财务报表") == ["2021", "2022", "2023"]
    assert extract_periods("2022年度纳税申报表") == ["2022"]
    assert extract_periods("营业执照") == []
    c = extract_constraints("母公司报表一式两份")
    assert c.scope is Scope.STANDALONE and c.copies == 2 and not c.stamp_required


def test_parse_excel_common_headers():
    content = _xlsx([
        ["XX银行授信资料清单"],
        ["编号", "资料名称", "要求"],
        ["3.1", "最近三年审计报告", "合并口径，加盖公章，3份"],
        ["6.3", "公司章程", None],
        [None, None, None],
        ["7.1", "环评批复文件", "复印件"],
    ])
    cl = ChecklistParser(ParserConfig(reference_year=2023)).parse(content, filename="清单.xlsx")
    assert [i.no for i in cl.items] == ["3.1", "6.3", "7.1"]
    first = cl.items[0]
    assert first.std_type == "AUDIT_REPORT" and first.type_status == "resolved"
    assert first.constraints.period == ["2021", "2022", "2023"]
    assert first.constraints.copies == 3 and first.constraints.stamp_required
    assert first.raw_text == "最近三年审计报告（合并口径，加盖公章，3份）"
    assert cl.items[1].std_type == "ARTICLES_OF_ASSOCIATION"
    assert cl.items[2].type_status == "unknown" and cl.items[2].std_type == UNKNOWN_TYPE


def test_parse_excel_configurable_headers():
    content = _xlsx([["Item", "Doc", "Remark"], ["1", "营业执照", "加盖公章"]])
    cfg = ParserConfig(column_aliases={"no": ["Item"], "name": ["Doc"], "requirement": ["Remark"]})
    cl = ChecklistParser(cfg).parse(content, filename="x.xlsx")
    assert len(cl.items) == 1
    assert cl.items[0].std_type == "BUSINESS_LICENSE"
    assert cl.items[0].constraints.stamp_required


def test_parse_excel_without_header_yields_empty():
    content = _xlsx([["a", "b"], ["1", "营业执照"]])
    assert ChecklistParser().parse(content, filename="x.xlsx").items == []


def test_parse_text():
    text = "3.1 最近三年审计报告（合并口径）\n\n6.3、公司章程\n营业执照副本\n"
    cl = ChecklistParser(ParserConfig(reference_year=2023)).parse(text)
    assert [i.no for i in cl.items] == ["3.1", "6.3", "1"]
    assert cl.items[2].std_type == "BUSINESS_LICENSE"
