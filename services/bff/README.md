# bff —— 浏览器唯一入口

对应整改项：权限主体信任边界收口（2026-11）。

浏览器不再直接调用 decide / retrieval-proxy / gateway / ingest，也不构造 `principal`
或 `X-User-Id`。所有 `/api/*` 请求先到 BFF。会话凭证支持 `Authorization: Bearer`、
会话 Cookie（默认取 `shilian_id_token` 转成 Bearer），以及测试环境的服务端固定主体：

```text
浏览器 ──/api/*──► BFF ──会话换 principal──► wecom-sync /identity/me
                    │
                    ├─ /retrieval/search  → retrieval-proxy（覆盖 principal + kb_scope）
                    ├─ /decide/route      → decide（覆盖 principal + kb_scope）
                    ├─ /gateway/v1/invoke → gateway（企业问答抬密级；通用问答不带 principal）
                    ├─ /ingest/*          → ingest（仅管理员角色；注入 X-User-Id / X-Tenant-Id）
                    └─ /matcher/*、/matcher/checklists/{id}/package → matcher / packager（principal 注入；manifest 由 matcher 生成）
```

## 清单/成册代理

- `POST /matcher/checklists`：上传清单，BFF 注入 `X-User-Id`；
- `POST /matcher/checklists/{id}/run`：BFF 用会话 principal 覆盖请求体，前端不能自报权限；
- `GET /matcher/tasks/{id}`、`GET /matcher/checklists/{id}`、`GET .../manifest`：透传；
- `POST /matcher/items/{id}/confirm`：注入操作人；
- `POST /matcher/checklists/{id}/package`：**只接受 matcher 生成的 manifest**，BFF 不接受浏览器自造 manifest，再转给 packager。

## 硬约束

1. 客户端请求体里的 `principal` 一律丢弃，只使用服务端解析出的主体；
2. 客户端 `X-User-Id` 默认不信任；仅 `BFF_TRUST_USER_HEADER=true` 的过渡部署才作为
   **身份入口**交给 wecom-sync 换权限，权限字段仍由身份服务决定；
3. `kb_scope` 由 `BFF_KB_SCOPE` 配置覆盖，浏览器不能自行扩大检索范围；
4. 企业问答生成必须已有服务端记录的检索上下文；`sensitivity` 只能抬到本次检索片段的
   最高密级，不能下调（防止绕过 L3 脱敏）；
5. 通用问答固定 L1、不携带 principal；
6. 下游错误只回状态码与服务名，不透传响应体。

## 运行

```bash
cd services/bff
python3 -m venv .venv && . .venv/bin/activate
pip install -e '.[dev]'
python -m pytest
uvicorn bff.app:app --port 8010
```

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `BFF_IDENTITY_URL` | `http://wecom-sync:8020` | 身份服务 |
| `BFF_RETRIEVAL_URL` | `http://retrieval-proxy:8000` | 检索服务 |
| `BFF_DECIDE_URL` | `http://decide:8000` | 路由服务 |
| `BFF_GATEWAY_URL` | `http://gateway:8000` | 模型网关 |
| `BFF_INGEST_URL` | `http://ingest:8030` | 入库服务 |
| `BFF_MATCHER_URL` | `http://matcher:8010` | 清单匹配服务 |
| `BFF_PACKAGER_URL` | `http://packager:8080` | 成册执行器 |
| `BFF_KB_SCOPE` | 空 | 逗号分隔；非空时覆盖客户端 `kb_scope` |
| `BFF_INTERNAL_TOKEN` | 空 | 透传给下游的 `X-Internal-Token`（供后续服务侧校验） |
| `BFF_DEV_MODE` | `false` | 仅本地/测试：使用服务端固定主体 |
| `BFF_DEV_PRINCIPAL` | 空 | `BFF_DEV_MODE=true` 时必填，JSON |
| `BFF_TRUST_USER_HEADER` | `false` | 过渡模式：信任外部注入的 `X-User-Id` 作为**身份入口** |
| `BFF_SESSION_COOKIE` | `shilian_id_token` | 会话 Cookie 名；存在时转成 `Authorization: Bearer` 交给身份服务 |
| `BFF_ADMIN_ROLES` | `admin,it_admin` | 允许访问 `/ingest/*` 管理代理的角色；普通用户 403 |
| `BFF_CONTEXT_SECRET` | 空 | 检索上下文签名密钥；多副本必须共享，置空则退回单实例内存上下文 |
| `BFF_CONTEXT_COOKIE_SECURE` | `false` | 签名上下文 Cookie 是否加 `Secure`（HTTPS 生产置 true） |
| `BFF_CONTEXT_TTL_SECONDS` | `900` | 检索上下文密级下限有效期 |

> `BFF_DEV_MODE=true` 等于跳过会话校验，只能用于测试环境；生产必须置 false，
> 并保证 BFF 是唯一可达入口。当前 wecom-sync 的 `/identity/principal/{id}`、
> `PUT /identity/grants/{id}` 等管理端点仍无鉴权，**不得**经 nginx 暴露。

> 检索上下文有两层：同实例内存 TTL 作为快速路径；配置 `BFF_CONTEXT_SECRET` 后
> 额外签发 HMAC 保护的 HttpOnly Cookie，多副本只要共享密钥即可校验，无需 Redis。
> 未配置密钥时仍只支持单实例；语义不变：密级下限只由服务端写入。

## 测试重点

`tests/test_app.py` 覆盖：无会话 401、客户端 principal 被覆盖、kb_scope 被服务端锁定、
企业生成缺上下文 409、密级不可下调、通用生成不带 principal、下游错误不回显。
