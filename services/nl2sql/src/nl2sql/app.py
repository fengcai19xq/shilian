"""NL2SQL FastAPI 应用。"""

from __future__ import annotations

import os
import sqlite3
from pathlib import Path

import yaml
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel

from .dictionary import Indicator, MetricDictionary
from .executor import UnsafeSQLError, execute_readonly, validate_sql
from .translator import KeywordTranslator, NL2SQLResult

_DEFAULT_DICT = Path(__file__).resolve().parent.parent.parent / "data" / "metrics.yaml"

SENSITIVITY_ORDER = {"L1": 1, "L2": 2, "L3": 3, "L4": 4}


class QueryRequest(BaseModel):
    """自然语言查数请求。"""

    question: str
    principal: dict  # {user_id, dept, roles, max_sensitivity, projects}


class IndicatorQueryRequest(BaseModel):
    """直接按指标 ID 查数请求。"""

    indicator_id: str
    params: dict[str, str]
    principal: dict


def _principal_sensitivity(principal: dict) -> str:
    return principal.get("max_sensitivity", "L1")


def _check_access(indicator: Indicator, principal: dict) -> None:
    user_level = SENSITIVITY_ORDER.get(_principal_sensitivity(principal), 0)
    required = SENSITIVITY_ORDER.get(indicator.sensitivity, 99)
    if user_level < required:
        raise HTTPException(
            status_code=403,
            detail=f"指标「{indicator.name}」要求密级 {indicator.sensitivity}，当前用户密级 {_principal_sensitivity(principal)}",
        )


def create_app() -> FastAPI:
    app = FastAPI(title="NL2SQL 经营查数", version="0.1.0")

    dict_path = Path(os.environ.get("NL2SQL_DICT_PATH", str(_DEFAULT_DICT)))
    db_path = os.environ.get("NL2SQL_DB_PATH", "")

    dictionary = MetricDictionary.from_yaml(dict_path)
    translator = KeywordTranslator(dictionary.list_all())

    @app.get("/health")
    def health() -> dict:
        return {"status": "ok", "indicators": len(dictionary.list_all())}

    @app.get("/indicators")
    def list_indicators() -> list[dict]:
        """列出全部指标定义。"""
        return [
            {
                "id": ind.id,
                "name": ind.name,
                "description": ind.description,
                "unit": ind.unit,
                "sensitivity": ind.sensitivity,
                "required_params": ind.required_params,
            }
            for ind in dictionary.list_all()
        ]

    @app.post("/query/nl")
    def query_nl(req: QueryRequest) -> dict:
        """自然语言 → 指标 → SQL → 执行。"""
        result: NL2SQLResult = translator.translate(req.question)
        if not result.matched:
            raise HTTPException(status_code=422, detail=result.reason)

        assert result.indicator is not None
        _check_access(result.indicator, req.principal)

        sql, params = result.indicator.render_sql(result.params)
        if not db_path:
            raise HTTPException(
                status_code=503,
                detail="NL2SQL_DB_PATH 未配置，无法执行查询",
            )
        try:
            rows = execute_readonly(sql, params, db_path)
        except UnsafeSQLError as e:
            raise HTTPException(status_code=400, detail=str(e))

        return {
            "indicator": result.indicator.id,
            "indicator_name": result.indicator.name,
            "unit": result.indicator.unit,
            "params": result.params,
            "rows": rows,
            "row_count": len(rows),
        }

    @app.post("/query/indicator")
    def query_indicator(req: IndicatorQueryRequest) -> dict:
        """按指标 ID 直接查数。"""
        indicator = dictionary.get(req.indicator_id)
        if indicator is None:
            raise HTTPException(status_code=404, detail=f"指标 {req.indicator_id} 不存在")

        _check_access(indicator, req.principal)

        sql, params = indicator.render_sql(req.params)
        if not db_path:
            raise HTTPException(
                status_code=503,
                detail="NL2SQL_DB_PATH 未配置，无法执行查询",
            )
        try:
            rows = execute_readonly(sql, params, db_path)
        except UnsafeSQLError as e:
            raise HTTPException(status_code=400, detail=str(e))

        return {
            "indicator": indicator.id,
            "indicator_name": indicator.name,
            "unit": indicator.unit,
            "params": req.params,
            "rows": rows,
            "row_count": len(rows),
        }

    @app.post("/validate-sql")
    def validate_sql_endpoint(body: dict) -> dict:
        """仅校验 SQL 安全天性，不执行。供 decide 路由层调用。"""
        sql = body.get("sql", "")
        try:
            validate_sql(sql)
            return {"safe": True}
        except UnsafeSQLError as e:
            return {"safe": False, "reason": str(e)}

    return app


app = create_app()
