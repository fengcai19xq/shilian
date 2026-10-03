# matcher —— 清单解析与三态匹配

对应任务：T10
接口契约：`contracts/matching.md`（只读）

清单结构化 → 资料类型归一 → 候选召回 → 三态打分 → 人工确认。日期/份数/金额用代码算。

## 开发约定

- 只读写本目录，跨模块需求走契约，不直接改其它模块代码
- 对外只暴露契约里定义的接口，内部结构自由
- 单元测试与本模块同目录，`pytest` 可独立跑通
- 不在代码里硬编码模型密钥与数据库口令，一律读环境变量

## 如何本地跑

需要 Python 3.11+，在本目录下独立安装与测试，不依赖其它模块：

```bash
cd services/matcher
python3.11 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'

python -m pytest          # 单元测试（全部使用 fake，不访问真实系统）
ruff check src tests      # lint
uvicorn matcher.app:app --reload --port 8010   # 本地起服务，文档见 /docs
```

本地起的默认服务未接入召回与 decide（`NullCandidateProvider` / `RejectAllDecideProvider`），
所有清单项都会落 `missing`，不会产生假阳性。接入真实依赖时用 `create_app(MatcherService(candidates=..., decide=...))` 注入。

### 目录

```text
data/types.yaml       资料类型词典（std_type / 标准名称 / 别名），未命中标 unknown
data/columns.yaml     Excel 列头识别配置（编号 / 资料名称 / 要求 的各种写法）
src/matcher/
  parsing.py          Excel（openpyxl）/ 文本清单解析
  extract.py          期间 / 口径 / 份数 / 盖章 抽取（正则 + 代码，不调模型）
  constraints.py      硬约束校验，任一不通过即 rejected
  scoring.py          final_score = 0.35×recall + 0.50×p + 0.15×form，三态判定
  ports.py            召回 / decide 可注入接口与 fake
  service.py          编排：上传、异步匹配、人工确认、manifest 导出、审计
  app.py              FastAPI 接口
```

### 配置

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `MATCHER_MATCHED_THRESHOLD` | 0.85 | ≥ 该值为 matched |
| `MATCHER_PENDING_THRESHOLD` | 0.50 | ≥ 该值为 pending，否则 missing |
| `MATCHER_W_RECALL` / `MATCHER_W_DECIDE` / `MATCHER_W_FORM` | 0.35 / 0.50 / 0.15 | 打分权重 |

通过 `ScoringConfig.from_env()` 读取；调参方向以降低假阳性为准（宁可调高 matched 阈值）。
