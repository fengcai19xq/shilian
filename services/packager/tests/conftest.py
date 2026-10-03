"""测试夹具：用 reportlab 现造脱敏 PDF，存储用本地临时目录。"""

from __future__ import annotations

import io
from pathlib import Path

import pytest
from reportlab.pdfgen import canvas

from packager import LocalStorage


def make_pdf(pages: int, tag: str) -> bytes:
    """生成 pages 页的 PDF，每页正文为 `{tag} page {n}`，便于断言顺序。"""
    buf = io.BytesIO()
    c = canvas.Canvas(buf, invariant=1)
    for i in range(1, pages + 1):
        c.drawString(100, 700, f"{tag} page {i}")
        c.showPage()
    c.save()
    return buf.getvalue()


@pytest.fixture
def storage(tmp_path: Path) -> LocalStorage:
    st = LocalStorage(tmp_path / "mnt", out_dir=tmp_path / "out")
    docs = st.root / "docs"
    docs.mkdir()
    (docs / "license.pdf").write_bytes(make_pdf(3, "LICENSE"))
    (docs / "audit.pdf").write_bytes(make_pdf(5, "AUDIT"))
    (docs / "audit_2.pdf").write_bytes(make_pdf(4, "AUDIT2"))
    (docs / "bank.pdf").write_bytes(make_pdf(4, "BANK"))
    (docs / "note.txt").write_bytes(b"not a pdf")
    return st


@pytest.fixture
def manifest() -> dict:
    """与契约示例同构的 manifest，内容全部为虚构数据。"""
    return {
        "package_id": "pkg_test_v1",
        "title": "示例银行授信资料包",
        "watermark": "仅供示例银行授信审查使用 · 2026-03-12",
        "outputs": ["zip", "merged_pdf", "checklist_xlsx"],
        "entries": [
            {
                "no": "1",
                "std_name": "营业执照",
                "files": [{"uri": "oss://docs/license.pdf", "pages": None, "order": 1}],
                "status": "matched",
                "note": "",
            },
            {
                "no": "3.1",
                "std_name": "最近三年审计报告",
                "period": "2021-2023",
                "files": [
                    {"uri": "oss://docs/audit_2.pdf", "pages": [1, 2], "order": 2},
                    {"uri": "oss://docs/audit.pdf", "pages": None, "order": 1},
                ],
                "status": "matched",
                "note": "",
            },
            {
                "no": "6.3",
                "std_name": "最新版公司章程",
                "files": [],
                "status": "missing",
                "note": "库内仅 2022 版，需最新备案版本",
                "owner_dept": "法务部",
            },
            {
                "no": "7",
                "std_name": "银行流水",
                "files": [{"uri": "oss://docs/bank.pdf", "pages": [2, 3], "order": 1}],
                "status": "pending",
                "note": "待财务确认口径",
            },
            {
                "no": "8",
                "std_name": "纳税证明",
                "files": [{"uri": "oss://docs/should_not_be_read.pdf", "order": 1}],
                "status": "missing",
                "note": "",
            },
        ],
    }
