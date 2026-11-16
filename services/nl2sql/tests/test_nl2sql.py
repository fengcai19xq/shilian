"""NL2SQL 服务测试。"""

import os
import sqlite3
import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from nl2sql.app import create_app
from nl2sql.dictionary import MetricDictionary, Indicator
from nl2sql.executor import UnsafeSQLError, validate_sql, execute_readonly
from nl2sql.translator import KeywordTranslator

_DICT_PATH = Path(__file__).resolve().parent.parent / "data" / "metrics.yaml"


# ── 指标口径字典 ──


class TestMetricDictionary:
    def test_loads_20_indicators(self):
        d = MetricDictionary.from_yaml(_DICT_PATH)
        assert len(d.list_all()) == 20

    def test_get_by_id(self):
        d = MetricDictionary.from_yaml(_DICT_PATH)
        ind = d.get("revenue_total")
        assert ind is not None
        assert ind.name == "营业收入"
        assert ind.sensitivity == "L2"

    def test_get_missing_returns_none(self):
        d = MetricDictionary.from_yaml(_DICT_PATH)
        assert d.get("nonexistent") is None

    def test_can_access_l2_user_access_l2_indicator(self):
        d = MetricDictionary.from_yaml(_DICT_PATH)
        ind = d.get("revenue_total")  # L2
        assert d.can_access(ind, "L2") is True

    def test_cannot_access_l1_user_to_l3_indicator(self):
        d = MetricDictionary.from_yaml(_DICT_PATH)
        ind = d.get("total_assets")  # L3
        assert d.can_access(ind, "L1") is False

    def test_l4_user_accesses_all(self):
        d = MetricDictionary.from_yaml(_DICT_PATH)
        ind = d.get("total_assets")  # L3
        assert d.can_access(ind, "L4") is True

    def test_render_sql_replaces_view_placeholder(self):
        d = MetricDictionary.from_yaml(_DICT_PATH)
        ind = d.get("revenue_total")
        sql, params = ind.render_sql({"period": "2024"})
        assert "v_finance_summary" in sql
        assert params == {"period": "2024"}

    def test_render_sql_missing_param_raises(self):
        d = MetricDictionary.from_yaml(_DICT_PATH)
        ind = d.get("revenue_total")
        with pytest.raises(ValueError, match="缺少必需参数"):
            ind.render_sql({})


# ── SQL 安全校验 ──


class TestSQLValidation:
    def test_select_passes(self):
        validate_sql("SELECT * FROM v_finance_summary WHERE period = :period")

    def test_insert_blocked(self):
        with pytest.raises(UnsafeSQLError):
            validate_sql("INSERT INTO foo VALUES (1)")

    def test_update_blocked(self):
        with pytest.raises(UnsafeSQLError):
            validate_sql("UPDATE foo SET x = 1")

    def test_delete_blocked(self):
        with pytest.raises(UnsafeSQLError):
            validate_sql("DELETE FROM foo")

    def test_drop_blocked(self):
        with pytest.raises(UnsafeSQLError):
            validate_sql("DROP TABLE foo")

    def test_create_blocked(self):
        with pytest.raises(UnsafeSQLError):
            validate_sql("CREATE TABLE foo (id INT)")

    def test_multi_statement_blocked(self):
        with pytest.raises(UnsafeSQLError, match="多条"):
            validate_sql("SELECT 1; SELECT 2")

    def test_non_select_blocked(self):
        with pytest.raises(UnsafeSQLError, match="SELECT"):
            validate_sql("EXPLAIN SELECT * FROM foo")


# ── 只读执行 ──


@pytest.fixture
def tmp_db():
    """创建临时 SQLite 数据库并建只读视图。"""
    fd, path = tempfile.mkstemp(suffix=".sqlite")
    os.close(fd)
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE v_finance_summary (period TEXT, revenue REAL, cogs REAL, net_profit REAL, operating_profit REAL, rd_expense REAL, subsidy_income REAL);
        INSERT INTO v_finance_summary VALUES ('2024', 1000000000, 700000000, 80000000, 100000000, 50000000, 10000000);
        INSERT INTO v_finance_summary VALUES ('2023', 800000000, 560000000, 60000000, 75000000, 40000000, 8000000);
    """)
    conn.commit()
    conn.close()
    yield path
    os.unlink(path)


class TestExecuteReadonly:
    def test_execute_returns_rows(self, tmp_db):
        sql = "SELECT period, SUM(revenue) AS value FROM v_finance_summary WHERE period = :period GROUP BY period"
        rows = execute_readonly(sql, {"period": "2024"}, tmp_db)
        assert len(rows) == 1
        assert rows[0]["period"] == "2024"
        assert rows[0]["value"] == 1000000000

    def test_execute_blocks_unsafe(self, tmp_db):
        with pytest.raises(UnsafeSQLError):
            execute_readonly("DELETE FROM v_finance_summary", {}, tmp_db)

    def test_max_rows_limit(self, tmp_db):
        sql = "SELECT period FROM v_finance_summary"
        rows = execute_readonly(sql, {}, tmp_db, max_rows=1)
        assert len(rows) == 1


# ── NL2SQL 翻译器 ──


class TestTranslator:
    def setup_method(self):
        self.dict = MetricDictionary.from_yaml(_DICT_PATH)
        self.t = KeywordTranslator(self.dict.list_all())

    def test_matches_revenue_question(self):
        result = self.t.translate("2024年营业收入是多少")
        assert result.matched is True
        assert result.indicator.id == "revenue_total"
        assert result.params["period"] == "2024"

    def test_matches_net_profit(self):
        result = self.t.translate("2024年净利润")
        assert result.matched is True
        assert result.indicator.id == "net_profit"
        assert result.params["period"] == "2024"

    def test_no_period_returns_unmatched(self):
        result = self.t.translate("营业收入是多少")
        assert result.matched is False
        assert "期间" in result.reason

    def test_yoy_auto_derives_prev_period(self):
        result = self.t.translate("2024年营收同比增长率")
        assert result.matched is True
        assert result.indicator.id == "revenue_yoy_growth"
        assert result.params["period"] == "2024"
        assert result.params["prev_period"] == "2023"

    def test_quarterly_period(self):
        result = self.t.translate("2024-Q1营业收入")
        assert result.matched is True
        assert result.params["period"] == "2024-Q1"

    def test_no_match_returns_reason(self):
        result = self.t.translate("今天天气怎么样")
        assert result.matched is False
        assert "未匹配" in result.reason


# ── API 端点 ──


class TestAPI:
    def setup_method(self):
        os.environ["NL2SQL_DICT_PATH"] = str(_DICT_PATH)
        self.app = create_app()
        self.client = TestClient(self.app)

    def test_health(self):
        r = self.client.get("/health")
        assert r.status_code == 200
        assert r.json()["indicators"] == 20

    def test_list_indicators(self):
        r = self.client.get("/indicators")
        assert r.status_code == 200
        body = r.json()
        assert len(body) == 20
        assert body[0]["id"] == "revenue_total"

    def test_query_nl_without_db_returns_503(self):
        r = self.client.post("/query/nl", json={
            "question": "2024年营业收入",
            "principal": {"user_id": "u1", "dept": "融资部", "roles": [], "max_sensitivity": "L2", "projects": []},
        })
        assert r.status_code == 503

    def test_query_nl_insufficient_sensitivity_returns_403(self, tmp_db):
        os.environ["NL2SQL_DB_PATH"] = tmp_db
        self.app = create_app()
        self.client = TestClient(self.app)
        r = self.client.post("/query/nl", json={
            "question": "2024年总资产",
            "principal": {"user_id": "u1", "dept": "融资部", "roles": [], "max_sensitivity": "L1", "projects": []},
        })
        assert r.status_code == 403
        os.environ.pop("NL2SQL_DB_PATH", None)

    def test_query_nl_success(self, tmp_db):
        os.environ["NL2SQL_DB_PATH"] = tmp_db
        self.app = create_app()
        self.client = TestClient(self.app)
        r = self.client.post("/query/nl", json={
            "question": "2024年营业收入",
            "principal": {"user_id": "u1", "dept": "融资部", "roles": [], "max_sensitivity": "L2", "projects": []},
        })
        assert r.status_code == 200
        body = r.json()
        assert body["indicator"] == "revenue_total"
        assert body["row_count"] == 1
        assert body["rows"][0]["value"] == 1000000000
        os.environ.pop("NL2SQL_DB_PATH", None)

    def test_query_indicator_by_id(self, tmp_db):
        os.environ["NL2SQL_DB_PATH"] = tmp_db
        self.app = create_app()
        self.client = TestClient(self.app)
        r = self.client.post("/query/indicator", json={
            "indicator_id": "net_profit",
            "params": {"period": "2024"},
            "principal": {"user_id": "u1", "dept": "融资部", "roles": [], "max_sensitivity": "L2", "projects": []},
        })
        assert r.status_code == 200
        body = r.json()
        assert body["indicator"] == "net_profit"
        assert body["rows"][0]["value"] == 80000000
        os.environ.pop("NL2SQL_DB_PATH", None)

    def test_query_indicator_not_found(self):
        r = self.client.post("/query/indicator", json={
            "indicator_id": "nonexistent",
            "params": {"period": "2024"},
            "principal": {"user_id": "u1", "dept": "融资部", "roles": [], "max_sensitivity": "L2", "projects": []},
        })
        assert r.status_code == 404

    def test_validate_sql_endpoint(self):
        r = self.client.post("/validate-sql", json={"sql": "SELECT 1"})
        assert r.json()["safe"] is True

        r = self.client.post("/validate-sql", json={"sql": "DROP TABLE foo"})
        assert r.json()["safe"] is False
