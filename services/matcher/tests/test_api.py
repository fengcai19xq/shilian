"""接口层：上传、异步匹配、查进度、人工确认（含审计）、导出 manifest。"""

from __future__ import annotations

import io

import pytest
from fastapi.testclient import TestClient
from openpyxl import Workbook

from matcher.app import build_default_service, create_app
from matcher.models import Scope
from matcher.ports import FakeDecideProvider, StaticCandidateProvider
from matcher.service import MatcherService

from .conftest import make_doc

CHECKLIST_TEXT = (
    "3.1 2023年度审计报告（合并口径，加盖公章）\n"
    "4.2 2023年度财务报表（合并口径）\n"
    "6.3 公司章程\n"
    "7.1 环评批复文件\n"
)


@pytest.fixture
def svc(config) -> MatcherService:
    by_type = {
        "AUDIT_REPORT": [
            make_doc(doc_id="d_2031", copies=None),
            # 期间不符：2022 报表匹 2023 项，即便 decide 给满分也必须 rejected
            make_doc(doc_id="d_2022", uri="oss://docs/d_2022.pdf", period=["2022"]),
        ],
        "FINANCIAL_STATEMENT": [
            make_doc(doc_id="d_fs_single", std_type="FINANCIAL_STATEMENT",
                     uri="oss://docs/d_fs_single.pdf", scope=Scope.STANDALONE),
            make_doc(doc_id="d_fs", std_type="FINANCIAL_STATEMENT", uri="oss://docs/d_fs.pdf",
                     recall_score=0.6, form_score=0.6),
        ],
        "ARTICLES_OF_ASSOCIATION": [
            make_doc(doc_id="d_aoa", std_type="ARTICLES_OF_ASSOCIATION", period=["2022"],
                     recall_score=0.2, form_score=0.2),
        ],
    }
    decide = FakeDecideProvider({"d_2031": 0.95, "d_2022": 1.0, "d_fs_single": 1.0,
                                 "d_fs": 0.6, "d_aoa": 0.3})
    return MatcherService(StaticCandidateProvider(by_type=by_type), decide, config=config)


@pytest.fixture
def client(svc) -> TestClient:
    return TestClient(create_app(svc))


def _upload(client: TestClient) -> dict:
    r = client.post("/matcher/checklists", data={"text": CHECKLIST_TEXT, "title": "测试授信资料包"},
                    headers={"X-User-Id": "u_test"})
    assert r.status_code == 201, r.text
    return r.json()


def _run(client: TestClient, checklist_id: str) -> dict:
    r = client.post(f"/matcher/checklists/{checklist_id}/run")
    assert r.status_code == 202
    body = r.json()
    assert body["task_id"].startswith("tk_")
    r = client.get(f"/matcher/tasks/{body['task_id']}")
    assert r.status_code == 200
    return r.json()


def test_upload_parses_items(client):
    cl = _upload(client)
    assert [i["no"] for i in cl["items"]] == ["3.1", "4.2", "6.3", "7.1"]
    assert cl["items"][0]["constraints"] == {
        "period": ["2023"], "scope": "consolidated", "copies": None, "stamp_required": True,
    }
    assert cl["items"][3]["type_status"] == "unknown"


def test_upload_excel_file(client):
    wb = Workbook()
    wb.active.append(["序号", "材料名称", "具体要求"])
    wb.active.append(["1", "营业执照", "加盖公章"])
    buf = io.BytesIO()
    wb.save(buf)
    r = client.post("/matcher/checklists",
                    files={"file": ("清单.xlsx", buf.getvalue(), "application/octet-stream")})
    assert r.status_code == 201
    assert r.json()["items"][0]["std_type"] == "BUSINESS_LICENSE"


def test_upload_requires_content(client):
    assert client.post("/matcher/checklists", data={"title": "x"}).status_code == 400


def test_upload_bad_excel_returns_422(client):
    r = client.post("/matcher/checklists", files={"file": ("bad.xlsx", b"not-an-xlsx", "x")})
    assert r.status_code == 422


def test_run_and_task_progress(client):
    cl = _upload(client)
    task = _run(client, cl["checklist_id"])
    assert task["task"]["state"] == "succeeded"
    assert task["progress"] == 1.0
    by_no = {r["no"]: r for r in task["task"]["results"]}

    audit = by_no["3.1"]
    assert audit["status"] == "matched" and audit["bound_doc_ids"] == ["d_2031"]
    rejected = next(c for c in audit["candidates"] if c["doc_id"] == "d_2022")
    assert rejected["rejected"] and "period_mismatch" in rejected["reject_reasons"]

    fs = by_no["4.2"]
    assert fs["status"] == "pending" and fs["bound_doc_ids"] == []
    assert by_no["6.3"]["status"] == "missing"
    assert by_no["7.1"]["status"] == "missing"


def test_run_unknown_checklist_404(client):
    assert client.post("/matcher/checklists/cl_nope/run").status_code == 404
    assert client.get("/matcher/tasks/tk_nope").status_code == 404


def test_confirm_sets_status_and_audit(client, svc):
    cl = _upload(client)
    _run(client, cl["checklist_id"])
    item_id = cl["items"][1]["item_id"]  # 4.2，机器判为 pending

    r = client.post(f"/matcher/items/{item_id}/confirm",
                    json={"doc_ids": ["d_fs"], "note": "已核对为合并口径"},
                    headers={"X-User-Id": "u_reviewer"})
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "matched"
    assert body["bound_doc_ids"] == ["d_fs"]
    assert body["confirmed_by"] == "u_reviewer"

    events = [e for e in svc.store.list_audit(cl["checklist_id"]) if e.item_id == item_id]
    confirm = [e for e in events if e.action == "item_confirmed"]
    assert len(confirm) == 1
    assert confirm[0].operator == "u_reviewer"
    assert confirm[0].detail["from"] == "pending" and confirm[0].detail["to"] == "matched"
    assert confirm[0].detail["doc_ids"] == ["d_fs"]
    assert confirm[0].detail["note"] == "已核对为合并口径"
    # 机器打分的状态变更也写了审计，操作人为 system
    assert any(e.action == "item_status_changed" and e.operator == "system" for e in events)

    # 重新匹配不覆盖人工确认
    task = _run(client, cl["checklist_id"])
    again = next(x for x in task["task"]["results"] if x["item_id"] == item_id)
    assert again["status"] == "matched" and again["confirmed_by"] == "u_reviewer"


def test_confirm_empty_doc_ids_marks_missing(client, svc):
    cl = _upload(client)
    _run(client, cl["checklist_id"])
    item_id = cl["items"][0]["item_id"]
    r = client.post(f"/matcher/items/{item_id}/confirm", json={"doc_ids": [], "note": "原件遗失"},
                    headers={"X-User-Id": "u_reviewer"})
    assert r.json()["status"] == "missing" and r.json()["bound_doc_ids"] == []


def test_confirm_requires_operator_and_existing_item(client):
    cl = _upload(client)
    item_id = cl["items"][0]["item_id"]
    assert client.post(f"/matcher/items/{item_id}/confirm", json={"doc_ids": ["x"]}).status_code == 401
    r = client.post("/matcher/items/it_nope/confirm", json={"doc_ids": ["x"]},
                    headers={"X-User-Id": "u"})
    assert r.status_code == 404


def test_manifest_only_matched_have_files(client):
    cl = _upload(client)
    _run(client, cl["checklist_id"])
    r = client.get(f"/matcher/checklists/{cl['checklist_id']}/manifest")
    assert r.status_code == 200
    m = r.json()
    assert m["package_id"].startswith("pkg_") and m["package_id"].endswith("_v1")
    assert m["title"] == "测试授信资料包"
    assert m["outputs"] == ["zip", "merged_pdf", "checklist_xlsx"]
    entries = {e["no"]: e for e in m["entries"]}
    assert entries["3.1"] == {
        "no": "3.1", "std_name": "审计报告", "status": "matched", "note": "",
        "files": [{"uri": "oss://docs/d_2031.pdf", "pages": None, "order": 1}],
    }
    assert entries["4.2"]["status"] == "pending" and entries["4.2"]["files"] == []
    assert entries["6.3"]["status"] == "missing" and entries["6.3"]["files"] == []
    assert entries["7.1"]["std_name"] == "环评批复文件"


def test_manifest_before_run_all_missing(client):
    cl = _upload(client)
    m = client.get(f"/matcher/checklists/{cl['checklist_id']}/manifest").json()
    assert {e["status"] for e in m["entries"]} == {"missing"}
    assert client.get("/matcher/checklists/cl_nope/manifest").status_code == 404


def test_default_service_never_auto_matches():
    client = TestClient(create_app(build_default_service()))
    cl = _upload(client)
    task = _run(client, cl["checklist_id"])
    assert {r["status"] for r in task["task"]["results"]} == {"missing"}


def test_task_failure_is_recorded(config):
    class Boom:
        def recall(self, item):
            raise RuntimeError("检索不可用")

    svc = MatcherService(Boom(), FakeDecideProvider(), config=config)
    client = TestClient(create_app(svc))
    cl = _upload(client)
    task = _run(client, cl["checklist_id"])
    assert task["task"]["state"] == "failed"
    assert "RuntimeError" in task["task"]["error"]
