# weknora-integration —— WeKnora 对接契约（新增）

> 状态：**待评审**　｜　本文件定义 shilian 与 WeKnora 的端点映射、密级绑定规则与安全纪律。
> 关联：`platform.md`（SPI 定义）、`gateway.md`（密级路由）、`retrieval.md`（权限四元组）。

---

## 1. 端点职责表

WeKnora 对外暴露 `/api/v1` 前缀，认证方式三态：JWT / API Key / OIDC。shilian 侧使用的端点如下：

| shilian 用途 | WeKnora 端点 | 方法 | 认证 | 说明 |
| --- | --- | --- | --- | --- |
| 检索（probe + full） | `/api/v1/knowledge-search` | POST | API Key (`retrieve`) | tag_ids + kb_ids 过滤下推 |
| 文档上传 | `/api/v1/knowledge-bases/{kb_id}/knowledge/file` | POST (multipart) | API Key (`ingest`) | 支持 tag_ids 打标 |
| 文档解析 | docreader gRPC `:50051` | gRPC | `GRPC_AUTH_TOKEN` | 直连，切块与元数据由 shilian 掌控 |
| KB 管理 | `/api/v1/knowledge-bases` | GET/POST/DELETE | API Key (`manage_kbs`) | KB 命名按 §3 编码 |
| 标签管理 | `/api/v1/knowledge-bases/{kb_id}/tags` | GET/POST/DELETE | API Key (`manage_kbs`) | project 标签的创建与维护 |
| 模型配置 | `/api/v1/models` | GET/POST | API Key (`manage_models`) | 配 gateway 为自定义厂商 |
| KB 初始化 | `/api/v1/initialization/initialize/{kb_id}` | POST | API Key (`manage_kbs`) | 绑定 embedding + LLM |
| 空间成员 | `/api/v1/tenants/{id}/members` | GET/POST | API Key (`manage_members`) | 企微同步写入 |
| MCP Server | `/mcp/{endpoint_id}` | Streamable HTTP | 端点 Bearer | 知识库对外发布 |
| 问答（SSE） | `/api/v1/knowledge-chat/{session_id}` | POST (SSE) | API Key (`chat`) | 用户入口走 shilian web，不走此端点 |
| Agent 问答 | `/api/v1/agent-chat/{session_id}` | POST (SSE) | API Key (`chat`) | 同上 |

---

## 2. API Key 与密级绑定规则

### 2.1 核心原则

**一把模型接入密钥绑定一个 (tenant, dept, sensitivity)**。密级由密钥决定，不由请求体决定。

WeKnora 的模型配置是 KB 级的（每个知识库各自选模型），而 KB 已按密级切分 →「密级」这个 WeKnora 没有的概念被编码进它的模型配置维度。

### 2.2 密钥矩阵

| 密钥用途 | capability | 绑定维度 | 数量 |
| --- | --- | --- | --- |
| 检索 | `retrieve` | tenant + 密级 + dept | 每 (tenant, 密级, dept) 一把 |
| 入库 | `ingest` | tenant + dept | 每 (tenant, dept) 一把 |
| 问答 | `chat` | tenant + dept | 每 (tenant, dept) 一把 |
| KB 管理 | `manage_kbs` | tenant | 每租户一把 |
| 模型配置 | `manage_models` | tenant | 每租户一把 |
| 成员管理 | `manage_members` | tenant | 每租户一把 |

### 2.3 密级路由对齐

shilian gateway 以 `provider=generic`（自定义 OpenAI 兼容接口）身份被 WeKnora 调用。每个 KB 的模型配置 `base_url` 指向 gateway shim，`api_key` 编码 (tenant, dept, sensitivity)。

```
WeKnora KB(密级L3, 部门融资部) → model config base_url=http://gateway:8000/v1
                                → api_key=sk-l3-financing-xxxxx
                                → gateway 收到后反查: tenant=acme, dept=融资部, sensitivity=L3
                                → 按 gateway.md 密级路由表选通道
```

### 2.4 硬约束

- 请求体中携带 `sensitivity` 或 `principal` 字段**一律被忽略**（spike 测试 `test_body_cannot_override_sensitivity` 覆盖）
- 未授权的密钥调用返回 403，不下发到 gateway
- `switched_reason` 通过响应头 `X-Shilian-Switched-Reason` 透出（OpenAI 协议体无位置承载）
- 通道信息通过 `system_fingerprint` 字段透出（如 `shilian_enterprise_api`）

### 2.5 ⚠ 已知限制

红线 4（切换通道时前端显式提示）**无法在 WeKnora 原生 UI 满足**——OpenAI `chat.completions` 响应体没有承载 `switched_reason` 的位置。由此得出：**面向最终用户的问答入口必须是 shilian web，WeKnora 原生 UI 只作管理面**。

---

## 3. KB 命名编码

### 3.1 编码方案

| 维度 | 承载方式 | 示例 |
| --- | --- | --- |
| tenant | WeKnora 空间（`X-Tenant-ID`）+ KB 名前缀 | 空间 `acme` |
| sensitivity + dept | **复合编码进 KB 名** | `sl-acme-L3-878d8d89` |
| project | tag（`project::` 前缀） | `project::prj-2026-citic` |
| 未标注 project | 哨兵 tag | `project::__none__` |
| roles | 不参与检索过滤 | 仅供 decide 层 |

### 3.2 编码规则

- KB 名格式：`sl-{tenant-slug}-{sensitivity}-{dept-slug}`
- 中文部门名按 unicode 码点 hex 化，保证可逆无碰撞
- KB 数量 = 密级(4) × 部门(N)，单企业 N≈5~10 → 20~40 个
- 编码实现见 `spike/weknora/kb_encoding.py`（纯函数，26 项测试通过）

### 3.3 可见范围推导

```
无 dept 授权          → has_any_access=False，不发请求，返回 total=0
有 dept，无 project   → kb_ids = dept × expand(密级)，不传 tag_ids
有 dept，有 project   → 同上 + tag_ids = 授权项目 + project::__none__
```

### 3.4 三个语义陷阱（spike 验证发现，已写入测试不变量）

1. **`knowledge_ids` 不能承载 project**——与 KB 取并集会绕开密级隔离。`knowledge_ids` 永久保留不用。
2. **tag 槽不能混用**——tag 槽内部是 IN(OR)，dept 与 project 共用会放过跨部门同项目文档。dept 走 KB 复合编码，tag 专属 project。
3. **project 为空的语义差异**——WeKnora tag IN 过滤没有「NULL 也放行」语义，未打标签文档会被静默过滤。解法：哨兵标签 `project::__none__`。

---

## 4. 部署隔离

### 4.1 数据库

WeKnora 的 ParadeDB（PostgreSQL 17 + BM25 + pgvector）与 shilian 业务库**分 database 或分 schema，禁止交叉**。WeKnora 启动时 `AUTO_MIGRATE` 只管自己的表。

### 4.2 Redis

Redis 分实例或分 DB index，避免 WeKnora 的 Asynq 任务队列与 shilian 业务缓存互相挤占。

### 4.3 网络

WeKnora `app:8080` 不暴露公网，仅内网可达。前端 `nginx:80` 通过 `/api/` 反向代理到 app。

---

## 5. 安全纪律

WeKnora 2026 年被披露过 SQL 注入至 RCE、MCP 命令注入、跨租户越权、KB 克隆越权、SSRF。融合后这些面就是 shilian 的面：

1. **锁版本** + 跟踪 advisory，升级走灰度
2. **生产 `DISABLE_REGISTRATION=true`** 或 `invite_register`，身份走 OIDC 对接企业 IdP
3. **开 `SSRF_DNS_WHITELIST_ONLY`**；MCP endpoint 默认关闭、按需开且独立 token
4. **API Key capability 最小化**：检索类 Key 不给 `ingest` / `manage_kbs`
5. **`8080` 不暴露公网**，仅内网可达
6. **L4 不出 VPC**：自建 WeKnora + 本地模型 + ParadeDB 全链路自托管天然满足
7. **上线前多租户红队测试**（WK16）
8. **默认零改动上游**；确需改动走「上游 PR + 本地 patch 队列」

---

## 6. nginx 配置纪律

### 6.1 SSE 与普通 API 分离

WeKnora 默认的 `api-proxy.conf` 对所有 `/api/` 请求关闭 `proxy_buffering`（为支持 SSE），会导致普通 POST 请求被 nginx 当作流式响应无限等待。

**必须将 `/api/` 拆为两个 location**：

- SSE 端点（`knowledge-chat` / `agent-chat` / `sessions/*/continue-stream`）：`proxy_buffering off` + `proxy_read_timeout 3600s`
- 普通 API：`proxy_buffering on` + `proxy_read_timeout 60s`

### 6.2 持久化

nginx 配置修改是直接改的容器内文件，容器重建后会丢失。**必须把修复后的配置写入 `docker-compose.override.yml` 挂载为 volume 持久化**。

---

## 补充（只增不改，2026-11）

- `verify_live.py`（spike 产物）作为 H1/H2/H3 现场验证的标准脚本，保留在 `spike/weknora/` 目录
- `kb_encoding.py`（spike 产物）在 WK08 完成后合并到生产代码 `services/retrieval-proxy/`，spike 独立实现退役
- gateway shim（`spike/weknora/gateway_shim.py`）在 WK06 完成后合并到 `services/gateway/`
- WeKnora 端口约定：app `8090`、frontend `8091`、postgres `5433`、redis `6381`、docreader `50051`（仅 compose 内网）
