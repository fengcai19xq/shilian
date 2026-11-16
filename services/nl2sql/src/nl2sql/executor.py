"""SQL 安全校验与执行。

只允许 SELECT 查询，禁止任何写操作或 DDL。
"""

from __future__ import annotations

import re
import sqlite3
from typing import Any

# 禁止的关键词（SQL 注入 / 写操作 / DDL）
_FORBIDDEN_KEYWORDS = re.compile(
    r"\b("
    r"INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|REPLACE|"
    r"MERGE|GRANT|REVOKE|ATTACH|DETACH|PRAGMA|VACUUM|"
    r"LOAD|RESTORE|BACKUP|RESTORE"
    r")\b",
    re.IGNORECASE,
)

_MAX_ROWS = 1000


class UnsafeSQLError(ValueError):
    """SQL 包含不允许的操作。"""


def validate_sql(sql: str) -> None:
    """检查 SQL 是否只读。

    红线：
    1. 必须以 SELECT 开头
    2. 不允许出现写操作 / DDL 关键词
    3. 不允许多语句（分号后跟内容）
    """
    stripped = sql.strip()
    if not stripped.upper().startswith("SELECT"):
        raise UnsafeSQLError("只允许 SELECT 查询")
    if _FORBIDDEN_KEYWORDS.search(stripped):
        raise UnsafeSQLError("SQL 包含禁止的操作关键词")
    # 分号后跟非空白 → 多语句
    after_semicolon = stripped.split(";", 1)
    if len(after_semicolon) == 2 and after_semicolon[1].strip():
        raise UnsafeSQLError("不允许执行多条 SQL 语句")


def execute_readonly(
    sql: str,
    params: dict[str, Any],
    db_path: str,
    max_rows: int = _MAX_ROWS,
) -> list[dict[str, Any]]:
    """在只读模式下执行 SQL 并返回结果。

    Args:
        sql: 已通过 validate_sql 的 SELECT 语句（:param 占位符）。
        params: 绑定参数。
        db_path: SQLite 数据库文件路径。
        max_rows: 结果行数上限。

    Returns:
        结果行列表（每行是 dict）。
    """
    validate_sql(sql)
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        conn.row_factory = sqlite3.Row
        cursor = conn.execute(sql, params)
        rows: list[dict[str, Any]] = []
        for i, row in enumerate(cursor):
            if i >= max_rows:
                break
            rows.append(dict(row))
        return rows
    finally:
        conn.close()
