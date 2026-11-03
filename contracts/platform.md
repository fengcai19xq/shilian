# platform —— 多租户平台契约（新增）

> 状态：**待评审**　｜　本文件定义多租户模型、隔离等级与三个 SPI 接口。
> 按 contracts/README.md 规则：只增不改，新增可选字段无需协商。

---

## 1. 租户模型

### 1.1 租户身份

每个 API 请求必须携带租户维度。`tenant_id` 是第一维权限谓词，与 `retrieval.md` 的权限四元组同源。

```json
{
  "tenant_id": "t_acme",
  "user_id": "u_10231",
  "dept": ["融资部", "财务共享"],
  "roles": ["financing_staff"],
  "max_sensitivity": "L3",
  "projects": ["PRJ-2026-CITIC"]
}
```

`principal` 结构在 `retrieval.md` 基础上新增 `tenant_id` 为第一字段。由网关按会话态注入，不由前端传入。

### 1.2 隔离等级

| 等级 | 说明 | 适用场景 |
| --- | --- | --- |
| `shared` | 共享数据库、行级租户谓词过滤 | 中小租户，默认 |
| `schema` | 独立 schema，同库实例 | 中型租户，数据合规要求中等 |
| `dedicated` | 独立数据库实例 | 大型租户 / L4 数据不出 VPC |

WeKnora 侧对应：`shared` → 同一空间（`X-Tenant-ID` 切换）；`schema` → 独立空间；`dedicated` → 独立 WeKnora 实例。

### 1.3 硬约束

1. **任何接口不接受无 `tenant_id` 的请求**（健康检查除外）
2. **任何 SQL / 检索请求必须带租户谓词**，与红线 1「权限过滤在检索之前完成」同源
3. 跨租户访问必须显式授权（`can_access_all_tenants`），且写审计

---

## 2. RetrievalBackend SPI

检索后端可插拔接口。WeKnora 和 RAGFlow 各一个 adapter。

### 2.1 接口定义

```python
class RetrievalBackend(Protocol):
    """检索后端 SPI。实现方负责把权限条件随请求下发给引擎。"""

    def search(self, query: SearchQuery) -> SearchResponse:
        """全文检索（召回 → rerank → 截断）。"""
        ...

    def probe(self, query: SearchQuery) -> ProbeResult:
        """路由探测检索：top_k=3，不 rerank，只返回最高分。成本约为 full 的 1/5。"""
        ...
```

### 2.2 SearchQuery

```json
{
  "query": "最近三年研发费用占营收比例",
  "principal": { "...": "§1.1 权限主体" },
  "kb_scope": ["kb_finance", "kb_audit"],
  "top_k": 8,
  "score_threshold": 0.35,
  "mode": "probe | full"
}
```

与 `retrieval.md` 的 `SearchRequest` 字段一致。`principal` 新增 `tenant_id`。

### 2.3 SearchResponse

与 `retrieval.md` 返回体一致。adapter 负责把后端原始结果映射为 `hits[]`。

### 2.4 硬约束

- **过滤条件必须随检索请求一起下发给引擎**，禁止先取结果再过滤（红线 1）
- `knowledge_ids` 永久排除在权限过滤之外（并集语义会绕开密级隔离，见 spike 结论）
- 越权文档不得出现在任何字段中，包括 `doc_name` 与错误信息
- 每次检索写审计事件：who / query / scope / hit_doc_ids / ts / **tenant_id**

### 2.5 已知 adapter

| adapter | 后端 | 过滤方式 | 状态 |
| --- | --- | --- | --- |
| `RagflowAdapter` | RAGFlow `/api/v1/retrieval` | `metadata_condition`（dept + sensitivity + project） | 已实现，保留作对照 |
| `WeKnoraAdapter` | WeKnora `POST /api/v1/knowledge-search` | `knowledge_base_ids`（编码 tenant+密级+dept）+ `tag_ids`（编码 project） | 待实现（WK08） |

---

## 3. DocReader SPI

文档解析后端可插拔接口。

```python
class DocReader(Protocol):
    """文档解析 SPI。输入文件，返回切块与元数据。"""

    def parse(self, request: ParseRequest) -> ParseResult:
        """解析文档，返回切块列表。不调模型、不写库。"""
        ...
```

### 3.1 ParseRequest

```json
{
  "file_uri": "oss://docs/d_2031.pdf",
  "file_type": "pdf",
  "metadata": {
    "dept": "财务共享",
    "sensitivity": "L3",
    "project": "PRJ-2026-CITIC",
    "period": "2023",
    "scope": "合并"
  },
  "chunk_size": 512,
  "chunk_overlap": 50
}
```

### 3.2 ParseResult

```json
{
  "chunks": [
    {
      "chunk_id": "d_2031_c0001",
      "page": 37,
      "text": "……研发费用 ……",
      "metadata": { "...": "与 ParseRequest.metadata 同结构" }
    }
  ],
  "page_count": 120,
  "status": "success"
}
```

### 3.3 已知 adapter

| adapter | 后端 | 格式支持 | 状态 |
| --- | --- | --- | --- |
| `RagflowDocReader` | RAGFlow 解析 API | docx / pdf / xlsx / pptx | 已实现 |
| `WeKnoraDocReader` | WeKnora docreader gRPC `:50051` | 25+ 格式 | 待实现（WK10） |

---

## 4. ChannelAdapter SPI

入口渠道可插拔接口。WeKnora 内置 9 家 IM，shilian 企微同步降级为单向同步器。

```python
class ChannelAdapter(Protocol):
    """渠道适配器。负责身份同步与消息转发。"""

    def sync_directory(self) -> list[DirectoryEntry]:
        """从外部 IM 同步组织架构到平台空间成员。"""
        ...

    def send_message(self, request: MessageRequest) -> str:
        """向指定渠道用户发送消息。返回 message_id。"""
        ...
```

### 4.1 已知 adapter

| adapter | 渠道 | 状态 |
| --- | --- | --- |
| `WeComSyncAdapter` | 企微组织架构 → WeKnora 空间 | 待实现（WK14） |
| `WeKnoraIMChannel` | WeKnora 内置企微 / 飞书 / 钉钉 | 上游已实现，配置即用 |

---

## 5. 计量与配额

### 5.1 出账维度

在 `gateway.md` 现有 `dept + purpose + model` 基础上新增 `tenant_id`：

```
出账键 = tenant_id + dept + purpose + model
```

### 5.2 配额

| 维度 | 默认上限 | 超限行为 |
| --- | --- | --- |
| tenant 月度 token 费用 | ¥3,000 | 80% 告警，100% 降级到低成本模型 |
| tenant 并发检索 | 10 | 429 限流 |
| tenant 存储空间 | 10 GB | 拒绝上传 |

租户级配额可由平台管理员覆盖。

---

## 补充（只增不改，2026-11）

- `tenant_id` 为必填字段，缺省时网关返回 400（健康检查与系统初始化接口除外）
- `RetrievalBackend` adapter 选择由 `RETRIEVE_DRIVER` 环境变量控制，默认 `weknora`，可选 `ragflow`（对照跑分用）
- `DocReader` adapter 选择由 `DOCREADER_DRIVER` 环境变量控制，默认 `weknora`
- L4 数据不出 VPC：`dedicated` 隔离等级的租户，所有后端（WeKnora + 模型 + 向量库）必须在 VPC 内自托管
