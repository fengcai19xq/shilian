# retrieval-proxy —— 权限过滤与检索转发

对应任务：T04 文档上传流水线 T06 治理审计 T16
接口契约：`contracts/retrieval.md`（只读）

把权限四元组翻译为 RAGFlow metadata_condition；检索前过滤，不做后过滤。

## 如何本地跑

环境：Python 3.11（代码兼容 3.10）。

```bash
cd services/retrieval-proxy
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

python -m pytest          # 单元测试，全绿
ruff check .              # lint
```

起服务（`POST /retrieval/search`）：

```bash
python -m uvicorn retrieval_proxy.app:app --reload --port 8010
```

服务启动后需要先注入依赖再对外提供检索，未注入时调用接口会报 `尚未注入 RAGFlow 客户端`：

```python
from retrieval_proxy.app import set_dependencies
from retrieval_proxy.audit import InMemoryAuditSink
from retrieval_proxy.ragflow import HttpRagflowClient

set_dependencies(HttpRagflowClient(base_url=..., api_key=...), InMemoryAuditSink())
```

RAGFlow 地址与密钥一律读环境变量，不写进代码。本地只想验证逻辑时，用 `tests/conftest.py` 里的
`FakeRagflowClient` 注入即可，无需真实 RAGFlow 实例。

## 实现说明

- `permissions.py`：权限四元组 -> `metadata_condition`（dept in… / sensitivity in 密级上限展开集合 /
  project in…），密级展开遵循 L3 可见 L1、L2、L3
- `service.py`：条件随检索请求一起下发（检索前过滤）；返回体再做一次兜底校验，
  即使下游忽略条件也不会泄露越权文档名；`mode=probe` 时 top_k 压到 3、不 rerank、只返回最高分
- 无部门授权或 `kb_scope` 为空时不发检索请求，直接返回 `total: 0`，不返回任何可推断存在性的信息
- `audit.py`：每次检索写一条审计事件（who / query / scope / hit_doc_ids / ts），写出为可注入接口

## 开发约定

- 只读写本目录，跨模块需求走契约，不直接改其它模块代码
- 对外只暴露契约里定义的接口，内部结构自由
- 单元测试与本模块同目录，`pytest` 可独立跑通
- 不在代码里硬编码模型密钥与数据库口令，一律读环境变量
