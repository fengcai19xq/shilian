# Lite → 标准 K8s 迁移指南

## 1. Lite 形态架构概览

```
单机 Docker Compose（docker-compose.lite.yml）
├── retrieval-proxy  ─┐
├── decide            │  全部内存/文件存储，无 Redis / 无 PostgreSQL
├── gateway           │  用量账本 → SQLite（lite-data/usage/gateway.sqlite）
├── ingest            │  登记快照 → JSON（lite-data/ingest/documents.json）
├── matcher           │  任务快照 → JSON（lite-data/matcher/store.json）
├── packager          │  产物 → 本地卷（lite-data/packages/）
├── wecom-sync        │  用户/部门/授权 → 内存（ConcurrentHashMap）
├── bff               │  签名上下文 → Cookie + 内存
└── web               │  nginx 静态前端
                      └── 检索/解析底座：WeKnora（外置）
```

Lite 形态的所有持久化数据落在单个 `./lite-data/` 目录，备份只需复制该目录。

## 2. 需要迁移的数据

| 数据 | Lite 存储 | K8s 目标存储 | 迁移方式 |
|------|----------|-------------|---------|
| 网关用量账本 | `lite-data/usage/gateway.sqlite` | PostgreSQL（共享） | `sqlite3 .dump` → `psql` 导入 |
| 文档登记 | `lite-data/ingest/documents.json` | PostgreSQL 或 etcd | JSON → SQL INSERT（脚本转换） |
| 原始文件 | `lite-data/filestore/` | 对象存储（OSS / MinIO） | `mc cp` 或 `ossutil cp` 批量上传 |
| 成册产物 | `lite-data/packages/` | 对象存储 | 同上 |
| 匹配任务快照 | `lite-data/matcher/store.json` | PostgreSQL | JSON → SQL INSERT |
| 检索审计 | `lite-data/audit/retrieval.jsonl` | 审计日志库（ELK / Loki） | `Filebeat` 采集 |
| WeKnora 知识库 | WeKnora 内部 | WeKnora K8s 部署或外置 | 数据库 dump + 对象存储迁移 |

## 3. 迁移步骤

### 3.1 停服与备份

```bash
# 在 Lite 机器上执行
cd deploy/test
docker compose -f docker-compose.lite.yml down

# 备份全部数据
tar czf shilian-lite-backup-$(date +%Y%m%d).tar.gz lite-data/
```

### 3.2 网关用量账本迁移（SQLite → PostgreSQL）

```bash
# 导出 schema + data
sqlite3 lite-data/usage/gateway.sqlite .dump > gateway_usage.sql

# 转换 SQLite 方言到 PostgreSQL（主要是去掉 sqlite_sequence、改 AUTOINCREMENT）
# 人工或脚本处理：
#   - 删除 BEGIN TRANSACTION / COMMIT（psql 用 \i 不需要）
#   - INTEGER PRIMARY KEY AUTOINCREMENT → BIGSERIAL（本表 PK 是组合键，无自增列，不需要改）
#   - ON CONFLICT ... DO UPDATE 语法兼容（PostgreSQL 9.5+ 原生支持）

# 导入 PostgreSQL
psql -h <pg-host> -U <pg-user> -d <pg-db> -f gateway_usage.sql
```

### 3.3 文档登记迁移（JSON → PostgreSQL）

```bash
# documents.json 是一个数组，每元素是一个文档登记记录
# 用 Python 脚本转换：
python3 -c "
import json, csv, sys
docs = json.load(open('lite-data/ingest/documents.json'))
# 输出 CSV 供 PostgreSQL COPY 导入
w = csv.DictWriter(sys.stdout, fieldnames=[
    'doc_id','title','file_name','file_type','size_bytes','dept',
    'sensitivity','project','period','scope','tenant',
    'copies','stamped','std_type','storage_uri','status','reason',
    'page_count','chunk_count','dataset_id','ragflow_doc_id',
    'uploaded_by','created_at','updated_at'
])
w.writeheader()
for d in docs:
    w.writerow(d)
" > ingest_documents.csv

# 导入 PostgreSQL
psql -h <pg-host> -d <pg-db> -c "
CREATE TABLE ingest_documents (
    doc_id TEXT PRIMARY KEY,
    title TEXT, file_name TEXT, file_type TEXT, size_bytes BIGINT,
    dept TEXT, sensitivity TEXT, project TEXT, period TEXT, scope TEXT,
    tenant TEXT, copies INT, stamped BOOLEAN, std_type TEXT, storage_uri TEXT,
    status TEXT, reason TEXT, page_count INT, chunk_count INT,
    dataset_id TEXT, ragflow_doc_id TEXT,
    uploaded_by TEXT, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ
);
"
psql -h <pg-host> -d <pg-db> -c "\COPY ingest_documents FROM 'ingest_documents.csv' WITH (FORMAT csv, HEADER true)"
```

### 3.4 原始文件迁移（本地卷 → 对象存储）

```bash
# MinIO 客户端
mc alias set lite ./lite-data/filestore
mc alias set k8s https://<oss-endpoint> <access-key> <secret-key>
mc cp --recursive lite/ k8s/shilian-docs/

# 或用 ossutil（阿里云 OSS）
ossutil cp -r lite-data/filestore/ oss://shilian-docs/
```

迁移完成后，更新 ingest 配置：
```
INGEST_FILE_STORE_PATH → 改为 OSS 挂载路径或 packager 配 PACKAGER_STORAGE_TYPE=oss
```

### 3.5 wecom-sync 用户数据迁移

Lite 形态下 wecom-sync 用内存仓库（`InMemoryUserRepository`），重启即丢。
K8s 形态接入真实 DB 时，提供自己的 `UserRepository` Bean 替代默认实现。

迁移方式：
1. 从企微管理后台重新同步（推荐）—— `POST /identity/sync` 带 `X-Admin-Token`
2. 或从 Lite 内存导出（需通过 `/identity/users` API 导出 JSON，再导入 K8s DB）

### 3.6 WeKnora 知识库迁移

WeKnora 的数据迁移取决于其部署形态：
- **同机 Docker → K8s**：dump WeKnora 的 PostgreSQL + 复制对象存储
- **保留现有 WeKnora**：K8s 中的 shilian 服务只需改 `WEKNORA_BASE_URL` 指向现有实例，无需迁移

## 4. K8s 部署配置要点

### 4.1 服务拆分

Lite 的 8 个容器直接映射为 8 个 K8s Deployment + Service：

| 服务 | 副本数 | 类型 |
|------|--------|------|
| retrieval-proxy | 2 | ClusterIP |
| decide | 2 | ClusterIP |
| gateway | 2 | ClusterIP |
| ingest | 1 | ClusterIP |
| matcher | 1 | ClusterIP |
| packager | 1 | ClusterIP |
| wecom-sync | 2 | ClusterIP |
| bff | 2 | ClusterIP（或 Ingress） |
| web | 2 | Ingress |

### 4.2 共享存储替换

| Lite | K8s |
|------|-----|
| `lite-data/usage/gateway.sqlite` | PostgreSQL（`GATEWAY_USAGE_DB_PATH` → 改为 PostgreSQL 连接串，需开发 `PgUsageLedger`） |
| `lite-data/ingest/documents.json` | PostgreSQL 或 etcd |
| `lite-data/filestore/` | 对象存储（OSS / MinIO） |
| `lite-data/matcher/store.json` | PostgreSQL |
| `lite-data/audit/retrieval.jsonl` | 日志聚合（ELK / Loki） |

### 4.3 配置注入

K8s ConfigMap + Secret 替代 `.env`：

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: shilian-config
data:
  RETRIEVE_DRIVER: weknora
  INGEST_SINK: weknora
  WEKNORA_BASE_URL: http://weknora-svc:8080
  BFF_KB_SCOPE: kb_finance,kb_audit
  OIDC_ENABLED: "true"
---
apiVersion: v1
kind: Secret
metadata:
  name: shilian-secrets
type: Opaque
stringData:
  WEKNORA_API_KEY: <key>
  GATEWAY_DEEPSEEK_API_KEY: <key>
  OIDC_PUBLIC_KEY_PEM: |
    -----BEGIN PUBLIC KEY-----
    ...
    -----END PUBLIC KEY-----
```

## 5. 迁移验证清单

- [ ] 网关用量账本数据行数一致（`SELECT COUNT(*) FROM usage`）
- [ ] 文档登记条数一致（`SELECT COUNT(*) FROM ingest_documents`）
- [ ] 原始文件可按 `storage_uri` 访问（`oss://shilian-docs/docs/{doc_id}/{file}`）
- [ ] 检索权限矩阵回归（越权 `total=0`）
- [ ] OIDC 登录 → BFF principal 注入 → 问答链路端到端通
- [ ] smoke.sh 在 K8s 环境跑通（端口改为 Ingress 地址）
