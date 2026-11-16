# 测试环境部署方案

> 适用：53 号机房测试环境（Ubuntu 22.04+，Docker ≥ 24）
> 代码版本：`shilian-weknora` 仓库 `c4a380b` 提交（2026-11-16）

---

## 一、架构总览

```
浏览器 :8088
    │
    ▼
  web (nginx 静态前端)
    │
    ▼
  bff (Python, 会话→principal 注入)
    │
    ├─→ wecom-sync (Java, OIDC 身份验签)
    ├─→ decide (Python, 判定路由)
    ├─→ retrieval-proxy (Python, 权限前置检索)
    │       └─→ RAGFlow 或 WeKnora (外部)
    ├─→ gateway (Python, 模型调用 + 脱敏 + 审计 + 水印 + 拒答)
    ├─→ ingest (Java, 文档上传解析入库)
    ├─→ matcher (Java, 清单匹配 + 成册)
    └─→ packager (Java, PDF 打包)
```

**服务清单（9 个容器 + 外部 RAGFlow）**

| 服务 | 语言 | 端口 | 作用 |
|------|------|------|------|
| `web` | nginx | 8088→80 | 浏览器唯一入口 |
| `bff` | Python | 内部 | 会话→principal、权限剥离、下游代理 |
| `wecom-sync` | Java | 内部 | OIDC 验签、principal 返回 |
| `decide` | Python | 内部 | 判定路由（rule/llm） |
| `retrieval-proxy` | Python | 内部 | 权限前置检索（RAGFlow/WeKnora 双后端） |
| `gateway` | Python | 内部 | 模型调用、脱敏、审计、水印、拒答 |
| `ingest` | Java | 内部 | 文档上传解析入库 |
| `matcher` | Java | 内部 | 清单匹配 + 成册 |
| `packager` | Java | 内部 | PDF 打包 |
| `RAGFlow` | Go | 9380 | 外部检索引擎（官方 compose 独立部署） |

---

## 二、前置条件

### 2.1 宿主机要求

| 项目 | 要求 |
|------|------|
| OS | Ubuntu 22.04+ / 24.04 |
| Docker | ≥ 24 + compose plugin |
| 内存 | ≥ 32GB（RAGFlow CPU 模式占 16GB） |
| 磁盘 | ≥ 100GB（模型文件 + 文档 + 产物） |
| 内核参数 | `vm.max_map_count ≥ 262144`（RAGFlow Elasticsearch 需要） |

```bash
# 设置内核参数（永久生效）
echo 'vm.max_map_count=262144' | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

### 2.2 国内镜像加速（无法直连 Docker Hub 时）

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://docker.mirrors.ustc.edu.cn"]
}
EOF
sudo systemctl restart docker
```

### 2.3 本轮代码同步到测试服务器

```bash
# 在开发机执行：把最新代码推到测试服务器
cd /Users/qianxu/Documents/git/github/shilian
git push origin main                          # 如果有远端

# 或者直接 rsync 到 53 服务器
rsync -avz --exclude='.git' --exclude='__pycache__' --exclude='target' \
  ./ root@53:/opt/shilian/
```

---

## 三、部署步骤

### 步骤 1：拉起 RAGFlow（首次或已部署可跳过）

```bash
ssh root@53
cd /opt

# 克隆 RAGFlow（CPU 模式）
git clone --depth 1 --branch v1.0.0-rc1 https://github.com/infiniflow/ragflow.git
cd ragflow/docker

# 追加国内加速 + CPU 模式配置
cat >> .env <<'EOF'
HF_ENDPOINT=https://hf-mirror.com
DEVICE=cpu
MEM_LIMIT=16g
TEI_MODEL=BAAI/bge-m3
TEI_BASE_URL=http://tei:80
EOF

# 拉起 RAGFlow（首次会下载模型，约 20-40 分钟）
docker compose -f docker-compose.yml up -d

# 等待 RAGFlow 就绪（检查健康）
docker compose -f docker-compose.yml ps    # 所有服务应为 healthy/running

# 浏览器打开 http://53:80 注册管理员账号
# 右上角头像 → API → 生成 API Key → 记录下来
```

### 步骤 2：配置环境变量

```bash
cd /opt/shilian/deploy/test
cp .env.example .env

# 编辑 .env，填写以下必填项：
vi .env
```

**必填项**：

```bash
# RAGFlow（步骤 1 中生成的 API Key）
RAGFLOW_API_KEY=ragflow-xxxxxxxxxxxxxxxx

# 模型密钥（至少配一个，否则网关返回 503）
# DeepSeek 公有云（L1 可用，最便宜）
GATEWAY_DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
# DeepSeek 企业版（L2/L3 可用，已签 DPA）
GATEWAY_DEEPSEEK_ENTERPRISE_API_KEY=sk-xxxxxxxxxxxxxxxx
# 通义千问企业版（L2/L3 可用，embed/rerank 也走这个）
GATEWAY_QWEN_ENTERPRISE_API_KEY=sk-xxxxxxxxxxxxxxxx

# 测试环境用服务端固定主体（生产置 false 并接入 OIDC）
BFF_DEV_MODE=true

# 管理端点令牌（/identity/sync 等管理接口需要）
IDENTITY_REQUIRE_ADMIN_TOKEN=true
IDENTITY_ADMIN_TOKEN=$(openssl rand -hex 16)
```

**可选项（WeKnora 后端）**：

```bash
# 如果用 WeKnora 替代 RAGFlow，配置以下项并改 RETRIEVE_DRIVER=weknora
RETRIEVE_DRIVER=weknora
INGEST_SINK=weknora
WEKNORA_BASE_URL=http://weknora:8080
WEKNORA_API_KEY=wkn-xxxxxxxxxxxxxxxx
WEKNORA_TENANT_ID=your-tenant-id
WEKNORA_EMBEDDING_MODEL=BAAI/bge-m3    # 必填，与 WeKnora 空间内 Embedding 模型名一致
```

### 步骤 3：构建并启动仓库服务

```bash
cd /opt/shilian/deploy/test

# 构建全部镜像（首次约 15-30 分钟，Maven 拉依赖占大头）
docker compose build

# 后台启动全部服务
docker compose up -d

# 查看启动状态
docker compose ps

# 期望输出：所有服务 STATUS = Up
#      Name                    Command               State          Ports
# ------------------------------------------------------------------------------------
# hangli-bff-1          python -m uvicorn ...        Up
# hangli-decide-1       python -m uvicorn ...        Up
# hangli-gateway-1      python -m uvicorn ...        Up
# hangli-ingest-1       java -jar /app/app.jar       Up
# hangli-matcher-1      java -jar /app/app.jar       Up
# hangli-packager-1     java -jar /app/app.jar       Up
# hangli-retrieval-proxy-1  python -m uvicorn ...    Up
# hangli-wecom-sync-1   java -jar /app/app.jar       Up
# hangli-web-1          nginx -g 'daemon off;'       Up   0.0.0.0:8088->80/tcp
```

### 步骤 4：冒烟验证

```bash
cd /opt/shilian/deploy/test

# 健康检查 + BFF 注入会话后的检索链路
./smoke.sh

# 期望输出：
#   [ok] bff
#   [ok] BFF 注入会话后检索可用（total=N）
```

### 步骤 5：浏览器验证

```
浏览器打开 http://53:8088

# 测试环境 BFF_DEV_MODE=true，服务端固定主体为：
#   user_id: u_fin, dept: 融资部, roles: it_admin, max_sensitivity: L2

# 验证清单：
# 1. 页面能加载（前端静态资源 OK）
# 2. 搜索框输入"授信"，能返回检索结果（检索链路 OK）
# 3. 问答框输入"介绍一下储能系统"，能返回模型回答（模型链路 OK）
# 4. 回答下方有来源引用（溯源链路 OK）
# 5. 上传一份脱敏 DOCX（/api/ingest/documents），能解析入库（入库链路 OK）
```

---

## 四、增量更新（代码变更后重新部署）

```bash
ssh root@53
cd /opt/shilian

# 1. 同步最新代码（从开发机 rsync 或 git pull）
git pull origin main
# 或 rsync 覆盖

# 2. 重新构建变更的服务（--no-cache 强制重建）
cd deploy/test

# 全量重建
docker compose build

# 或只重建变更的服务（更快）
docker compose build gateway bff         # 只重建 Python 服务
docker compose build matcher packager     # 只重建 Java 服务

# 3. 重启
docker compose up -d

# 4. 冒烟验证
./smoke.sh
```

---

## 五、常用运维命令

### 5.1 日志查看

```bash
cd /opt/shilian/deploy/test

# 全部服务实时日志
docker compose logs -f

# 单个服务日志
docker compose logs -f gateway
docker compose logs -f bff
docker compose logs -f retrieval-proxy

# 最近 100 行
docker compose logs --tail 100 gateway
```

### 5.2 重启单个服务

```bash
docker compose restart gateway
docker compose restart bff
```

### 5.3 进入容器调试

```bash
docker compose exec gateway bash
docker compose exec bff bash
docker compose exec ingest sh
```

### 5.4 查看审计日志

```bash
# 检索审计（retrieval-proxy）
cat deploy/test/audit/retrieval.jsonl | python3 -m json.tool

# 网关审计（gateway，T16 新增）
cat deploy/test/audit/gateway.jsonl | python3 -m json.tool

# 网关用量账本（SQLite）
sqlite3 deploy/test/usage/gateway.sqlite "SELECT * FROM usage_records ORDER BY ts DESC LIMIT 20;"
```

### 5.5 清理重来

```bash
cd /opt/shilian/deploy/test

# 停止并删除容器（保留数据卷）
docker compose down

# 停止并删除容器 + 数据卷（完全重置）
docker compose down -v
rm -rf deploy/test/{audit,usage,ingest-data,filestore,matcher-data,packages,lite-data}

# 重新构建并启动
docker compose build && docker compose up -d
```

---

## 六、数据目录说明

| 容器路径 | 宿主路径 | 作用 |
|----------|----------|------|
| `/var/audit/retrieval.jsonl` | `deploy/test/audit/` | 检索审计 JSONL |
| `/var/audit/gateway.jsonl` | `deploy/test/audit/` | 网关审计 JSONL（T16） |
| `/var/usage/gateway.sqlite` | `deploy/test/usage/` | 网关用量账本 SQLite |
| `/var/ingest/documents.json` | `deploy/test/ingest-data/` | 文档登记快照 |
| `/var/filestore/` | `deploy/test/filestore/` | 原始文件存储 |
| `/var/matcher/store.json` | `deploy/test/matcher-data/` | 匹配任务快照 |
| `/var/packages/` | `deploy/test/packages/` | 成册 PDF 产物 |

---

## 七、Lite 单机形态（可选）

如果只需要单机最小部署（无 RAGFlow、用 WeKnora 内嵌向量库）：

```bash
cd /opt/shilian/deploy/test

# Lite 配置：WeKnora 双后端、OIDC 默认开启、全数据落 lite-data/ 单卷
cp lite.env.example .env.lite
vi .env.lite    # 填 WEKNORA_API_KEY、模型密钥

docker compose -f docker-compose.lite.yml --env-file .env.lite build
docker compose -f docker-compose.lite.yml --env-file .env.lite up -d
```

Lite → 标准 K8s 迁移指南见 `deploy/test/LITE-MIGRATION.md`。

---

## 八、排障速查

| 症状 | 排查 |
|------|------|
| `smoke.sh` 检索 total=-1 | 检查 RAGFlow 是否健康、`RAGFLOW_API_KEY` 是否正确 |
| 问答返回 503 | 检查 `.env` 中模型密钥是否有效（`docker compose logs gateway`） |
| 上传 403 | `BFF_DEV_MODE=true` 且主体带 `it_admin` 角色才能走 `/api/ingest` |
| 问答回答末尾有乱码 | 正常——T16 外发水印（零宽字符），肉眼不可见但终端可能显示 |
| `docker compose build` 失败 | Maven 拉不到依赖 → 检查网络 / `deploy/test/maven-settings.xml` 镜像源 |
| RAGFlow 容器 OOM | `MEM_LIMIT=16g` 调大到 `24g`，或加 `vm.swappiness=10` |
| L4 问答被拒答 | 正常——L4 只路由到 `vpc_self_hosted` 通道，未配 `GATEWAY_VPC_API_KEY` 时返回 503 |
