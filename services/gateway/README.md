# gateway —— 模型网关 / 企微 / NL2SQL

对应任务：T05 T09 T12 T15
接口契约：`contracts/gateway.md`（只读）

模型密钥托管、按密级路由、用量计费与超额降级；企微收发与派单；只读视图查数。

## 开发约定

- 只读写本目录，跨模块需求走契约，不直接改其它模块代码
- 对外只暴露契约里定义的接口，内部结构自由
- 单元测试与本模块同目录，`pytest` 可独立跑通
- 不在代码里硬编码模型密钥与数据库口令，一律读环境变量

## T09 统一模型网关（已实现）

```text
src/gateway/
  schemas.py          请求/返回体（与 contracts/gateway.md 一致）
  config.py           加载 conf/*.yaml：密级策略、模型目录、预算、脱敏词表
  router.py           密级路由 + preferred_model 降级 + switched_reason
  masking.py          L3 实体脱敏与还原
  usage.py            按 dept+purpose+model 记账，80% 告警 / 100% 降级
  clients/            统一客户端接口、OpenAI 兼容实现、测试用 fake
  service.py          编排：路由 → 脱敏 → 调用 → 还原 → 记账
  api.py              FastAPI：POST /v1/invoke、GET /healthz
  conf/routing.yaml   路由表（可替换）
  conf/masking.yaml   脱敏词表（可替换）
```

## 如何本地跑

需要 Python 3.11。

```bash
cd services/gateway
python3.11 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'

python -m pytest          # 单元测试（全部用 fake 客户端，不发真实请求）
ruff check . && ruff format --check .
```

启动服务（需要另装 `uvicorn`）：

```bash
# 密钥只从环境变量读，变量名见 conf/routing.yaml 的 api_key_env
export GATEWAY_DEEPSEEK_ENTERPRISE_API_KEY=...   # 示例，按需配置
uvicorn gateway.api:app --reload --port 8009

curl -s localhost:8009/v1/invoke -H 'content-type: application/json' -d '{
  "purpose": "generate", "sensitivity": "L3", "preferred_model": "deepseek-v3",
  "principal": {"user_id": "u_10231", "dept": ["融资部"]},
  "payload": {"prompt": "示例客户甲的报价是多少"}
}'
```

替换配置：构造 `GatewayService(routing_config=load_routing_config(path), masking_config=load_masking_config(path))`，
或在部署时用 `app.dependency_overrides[get_service]` 注入。
