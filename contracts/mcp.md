# MCP Server 契约（新增，只增不改）

> 状态：**新增**　｜　本文件定义 MCP（Model Context Protocol）端点的对外发布接口。
> Claude / Cursor 等外部工具通过本接口消费 shilian 知识库。
> 按 contracts/README.md 规则：只增不改。

---

## 1. 端点模型

每个 MCP 端点是一个对外发布的知识库接口，绑定：
- **独立 Bearer token**：一端点一密钥，互不影响，不可跨端点使用
- **kb_ids 白名单**：端点只能检索授权的知识库
- **固定权限主体**：principal 由端点配置决定，**不由调用方传入**
- **capability 最小化**：默认只开放 `search`，可选 `generate`

```json
{
  "endpoint_id": "finance-search",
  "token": "mcp_sk_finance_xxx",
  "kb_scope": ["kb_finance"],
  "principal": {
    "user_id": "mcp_finance_search",
    "dept": ["融资部"],
    "max_sensitivity": "L2",
    "projects": []
  },
  "capabilities": ["search"]
}
```

### 1.1 配置方式

通过环境变量 `MCP_ENDPOINTS`（JSON 数组）配置。未配置时不挂载任何路由（默认关闭）。

### 1.2 硬约束

1. **端点 token 不能跨端点使用**：A 端点的 token 访问 B 端点返回 403
2. **principal 由配置注入**：调用方无法覆盖 dept / max_sensitivity / projects
3. **kb_scope 由配置决定**：调用方无法扩大检索范围
4. **capability 最小化**：未配置的 capability 访问返回 403
5. **权限过滤在检索请求里完成**：端点的 principal 四元组随检索请求下推给 retrieval-proxy（红线 1）
6. **模型调用经 gateway**：generate 能力的调用走网关密级路由、脱敏、出账（红线 3/4）

---

## 2. 端点

### 2.1 列出工具 `GET /mcp/:endpoint_id/tools`

**鉴权**：`Authorization: Bearer <token>`

**返回**：
```json
{
  "tools": [
    {
      "name": "search",
      "description": "在授权知识库中检索文档片段",
      "input_schema": {
        "type": "object",
        "properties": {
          "query": {"type": "string"},
          "top_k": {"type": "integer", "default": 8, "minimum": 1, "maximum": 20},
          "mode": {"type": "string", "enum": ["probe", "full"], "default": "full"}
        },
        "required": ["query"]
      }
    }
  ]
}
```

capability 包含 `generate` 时额外返回 generate 工具。

### 2.2 检索 `POST /mcp/:endpoint_id/search`

**鉴权**：`Authorization: Bearer <token>`

**请求**：
```json
{
  "query": "最近三年研发费用占营收比例",
  "top_k": 8,
  "score_threshold": 0.35,
  "mode": "full"
}
```

**返回**：与 `retrieval.md` 的 `SearchResponse` 一致（`hits[]` + `total` + `scope_desc`）。

**行为**：
- 把端点配置的 principal 与 kb_scope 注入检索请求，调用 retrieval-proxy `POST /retrieval/search`
- 越权文档不在返回中（由 retrieval-proxy 保证）
- 检索上游错误只透出状态码，不透出文档内容（502）

### 2.3 生成 `POST /mcp/:endpoint_id/generate`

**鉴权**：`Authorization: Bearer <token>`

**请求**：
```json
{
  "query": "审计发现有哪些",
  "preferred_model": "deepseek-v3-enterprise"
}
```

**返回**：与 `gateway.md` 的 `InvokeResponse` 一致（`text` + `model` + `channel` + `usage` + `switched_reason`）。

**行为**：
- 把端点配置的 principal 与 max_sensitivity 注入网关调用 `POST /v1/invoke`
- 密级路由、脱敏、出账全部走既有链路
- 通道切换带 `switched_reason`（红线 4）

---

## 3. 消费方

| 消费方 | 用途 |
| --- | --- |
| Claude Desktop | 通过 MCP 协议检索企业知识库 |
| Cursor | IDE 内嵌入企业知识检索 |
| 其他 MCP 客户端 | 按标准 MCP 协议消费 |

---

## 4. 环境变量

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `MCP_ENDPOINTS` | 端点配置 JSON 数组 | （空，不挂载） |
| `MCP_RETRIEVAL_URL` | retrieval-proxy 地址 | `http://retrieval-proxy:8000` |
