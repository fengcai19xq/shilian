"""把 shilian 权限四元组编码为 WeKnora 的三个过滤槽。

## 为什么需要这一层

WeKnora `POST /api/v1/knowledge-search` 只提供三个过滤字段，且它们之间是 AND、
字段内部是 IN（OR）语义（PostgreSQL 引擎实现为 `knowledge_base_id`/`knowledge_id`/
`tag_id` IN 过滤，AND 语义；见 docs/03-features/05-retrieval-engines）：

    knowledge_base_ids : []string   -> KB id IN (...)
    knowledge_ids      : []string   -> 文件 id IN (...)
    tag_ids            : []string   -> 标签 id IN (...)

shilian 的权限四元组有 dept / sensitivity / project 三个需要 AND 的检索维度
（roles 不参与检索过滤，只供 decide 层用）。三个维度、三个槽，看似刚好，
实际有两个陷阱：

### 陷阱 1：`knowledge_ids` 不能用来承载 project

官方文档明确："检索范围是 `knowledge_base_id(s)` 与 `knowledge_ids` 的并集"。
用它承载 project 授权文件列表，会把这些文件**所在 KB 的其余文件一并放行**，
等于绕开密级隔离。因此 `knowledge_ids` 在本方案中**永久保留不用**，
只有"仅在指定文件里搜"的业务场景才允许使用，且此时不得同时传 KB。

### 陷阱 2：单个 KB 只能承载一个 AND 维度，dept+sensitivity 需要复合编码

若 KB 只编码 sensitivity（L1..L4 四个库），dept 只能落到 tag 槽；
而 tag 槽还要承载 project，两个维度挤进同一个 IN 列表会变成 OR——
"融资部 或 PRJ-2026-CITIC"会放过"风控部但同项目"的文档。

解法：**让 KB 同时编码 sensitivity 与 dept**，一次 IN 解决两个 AND 维度；
tag 槽专门留给 project。KB 数量 = 密级(4) × 部门(N)，单企业 N≈5~10 时约 20~40 个，
可接受；多租户时按 tenant 再加一层命名空间。

### 附带发现：project 为空时的语义差异

RAGFlow 版 `_is_allowed`（services/retrieval-proxy/src/retrieval_proxy/service.py）对
`chunk.project is None` 返回 True，即**未标注项目的文档所有人可见**。
WeKnora 的 tag IN 过滤没有"NULL 也放行"的语义，未打标签的文档会被直接过滤掉。
为对齐既有行为，给未标注项目的文档打哨兵标签 `project::__none__`，
并在用户持有项目授权时一并纳入 tag_ids。

## 约束（违反即视为安全缺陷）

- tag 槽是 project 维度的**唯一占用者**；业务标签不得参与权限检索过滤，
  否则 IN 的 OR 语义会污染权限判定。业务标签可用于展示，但不能进 `tag_ids`。
- 密级与部门一律走 KB，不走 tag：KB 是粗粒度边界，配合 API Key 的
  `knowledge_base_ids` 白名单可在平台层再兜一道。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# 密级由低到高，顺序即包含关系，与 contracts/retrieval.md 保持一致
from .models import SENSITIVITY_ORDER  # 与检索侧共用同一份密级序


# 未标注项目的哨兵标签值。语义等价于 RAGFlow 版的 `project is None -> 放行`
PROJECT_NONE = "__none__"

TAG_PREFIX_PROJECT = "project::"

# KB 名与标签名的合法字符：ASCII 小写字母数字、连字符、下划线、双冒号分隔符。
# 部门名是中文，必须先 slug 化再进 KB 名。
_SLUG_UNSAFE = re.compile(r"[^a-z0-9_-]+")


class EncodingError(ValueError):
    """编码不可逆或输入非法。宁可拒绝也不放行。"""


def expand_sensitivity(max_sensitivity: str) -> list[str]:
    """把密级上限展开为可见密级集合，例如 L3 -> [L1, L2, L3]。

    与 services/retrieval-proxy/src/retrieval_proxy/permissions.py 同名函数语义一致，
    spike 独立实现以避免在验证阶段改动生产模块。
    """
    if max_sensitivity not in SENSITIVITY_ORDER:
        raise EncodingError(f"未知密级：{max_sensitivity}")
    upper = SENSITIVITY_ORDER.index(max_sensitivity)
    return list(SENSITIVITY_ORDER[: upper + 1])


def slug(value: str) -> str:
    """把部门名等中文标识 slug 化为 ASCII 安全的 KB/标签片段。

    中文直接按 unicode 码点转 hex，保证可逆且无碰撞；
    已经是 ASCII 安全串的输入原样保留，便于人工阅读。
    """
    if not value:
        raise EncodingError("空值不能作为标识片段")
    if _SLUG_UNSAFE.search(value.lower()) or not value.isascii():
        return "".join(f"{ord(ch):x}" for ch in value)
    return value.lower()


def kb_name(tenant: str, sensitivity: str, dept: str) -> str:
    """KB 命名：`sl-{tenant}-{sensitivity}-{dept-slug}`。

    sensitivity 与 dept 复合进 KB 名，使单个 `knowledge_base_ids` IN 过滤
    同时表达两个 AND 维度。
    """
    if sensitivity not in SENSITIVITY_ORDER:
        raise EncodingError(f"未知密级：{sensitivity}")
    return f"sl-{slug(tenant)}-{sensitivity}-{slug(dept)}"


def parse_kb_name(name: str) -> tuple[str, str, str]:
    """反解 KB 名，用于校验编码可逆（spike 断言用）。"""
    parts = name.split("-")
    if len(parts) < 4 or parts[0] != "sl":
        raise EncodingError(f"非法 KB 名：{name}")
    tenant = parts[1]
    sensitivity = parts[2]
    dept = "-".join(parts[3:])
    if sensitivity not in SENSITIVITY_ORDER:
        raise EncodingError(f"KB 名中的密级非法：{name}")
    return tenant, sensitivity, dept


_PROJECT_TAG_FIXED = "p"  # 可逆编码前缀标记


def encode_project(value: str) -> str:
    """项目值 → tag 片段，采用定长 %04x 编码，保证可逆。

    KB 名里的部门用 slug()（小写、不可逆）够用，检索侧只需匹配；
    但 project 标签要从 tag 名反解回原值参与兜底校验，
    若小写化会让 `PRJ-DEMO-A` 变成 `prj-demo-a`、与文档元数据不相等，
    → 已授权文档被静默丢弃（最危险的功能性漏料）。故一律 p + 每字符 4 位 hex。
    """
    if not value:
        raise EncodingError("空值不能作为项目标签片段")
    return _PROJECT_TAG_FIXED + "".join(f"{ord(ch):04x}" for ch in value)


def decode_project(encoded: str) -> str:
    """encode_project 的逆运算。无法识别时按原样返回，避免抛错打断检索。"""
    if not encoded.startswith(_PROJECT_TAG_FIXED) or len(encoded) == 1:
        return encoded
    body = encoded[1:]
    if len(body) % 4 != 0:
        return encoded
    try:
        return "".join(chr(int(body[i : i + 4], 16)) for i in range(0, len(body), 4))
    except ValueError:
        return encoded


def project_tag(project: str | None) -> str:
    """项目标签名。未标注项目返回哨兵标签（哨兵不参与可逆编码，保持可读）。"""
    value = project.strip() if project else ""
    return f"{TAG_PREFIX_PROJECT}{PROJECT_NONE if not value else encode_project(value)}"


def parse_project_tag(tag_name: str) -> str | None:
    """从标签名反解项目原值。

    - 非 project:: 前缀 → None（调用方跳过该标签）
    - 哨兵标签 → ""（表示该文档未标注项目，检索侧按 None 处理并与 RAGFlow 对齐）
    - 编码标签 → 原始项目值
    """
    if not tag_name.startswith(TAG_PREFIX_PROJECT):
        return None
    value = tag_name[len(TAG_PREFIX_PROJECT) :]
    if value == PROJECT_NONE:
        return ""
    return decode_project(value)


@dataclass(frozen=True)
class VisibleScope:
    """一次检索请求允许触达的范围。"""

    knowledge_base_ids: list[str]
    tag_names: list[str]
    # knowledge_ids 恒为空：见模块 docstring 陷阱 1
    knowledge_ids: list[str]
    has_any_access: bool


def visible_kbs(tenant: str, depts: list[str], max_sensitivity: str) -> list[str]:
    """用户可见的全部 KB：部门 × 可见密级 的笛卡尔积。"""
    if not depts:
        return []
    return [
        kb_name(tenant, sensitivity, dept)
        for sensitivity in expand_sensitivity(max_sensitivity)
        for dept in depts
    ]


def visible_project_tags(projects: list[str]) -> list[str]:
    """用户可见的项目标签集合。

    - projects 为空 -> 返回空列表 -> 不传 tag_ids -> 该 KB 内全部文档可见（不限项目）
    - projects 非空 -> 授权项目 + 哨兵标签（未标注项目的文档沿用 RAGFlow 版语义放行）
    """
    if not projects:
        return []
    tags = [project_tag(p) for p in projects]
    none_tag = project_tag(None)
    if none_tag not in tags:
        tags.append(none_tag)
    return tags


def build_scope(
    tenant: str,
    depts: list[str],
    max_sensitivity: str,
    projects: list[str] | None = None,
) -> VisibleScope:
    """构造一次检索的可见范围。

    完全无权（无部门授权）时返回 has_any_access=False，调用方必须直接返回空结果、
    不发检索请求，与 retrieval-proxy 现有红线行为一致。
    """
    kbs = visible_kbs(tenant, depts, max_sensitivity)
    if not kbs:
        return VisibleScope(
            knowledge_base_ids=[], tag_names=[], knowledge_ids=[], has_any_access=False
        )
    return VisibleScope(
        knowledge_base_ids=kbs,
        tag_names=visible_project_tags(projects or []),
        knowledge_ids=[],
        has_any_access=True,
    )


def kb_provisioning_plan(tenant: str, depts: list[str]) -> list[str]:
    """租户开通时需要创建的 KB 全量清单（4 密级 × 部门）。"""
    return [
        kb_name(tenant, sensitivity, dept)
        for dept in depts
        for sensitivity in SENSITIVITY_ORDER
    ]
