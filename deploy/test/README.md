# 机房测试环境（单机 docker compose）

目标：PC 工作台 → BFF（会话 → principal）→ decide 路由 → retrieval-proxy 权限前置检索（RAGFlow / WeKnora 双后端）→ gateway 调模型；ingest 上传解析入库。
IM 入口由 WeKnora 内置企微渠道承载，不再单独开发。

## 前置

- Ubuntu 22.04+/24.04，Docker ≥ 24 + compose plugin，`vm.max_map_count ≥ 262144`
- 无法直连 Docker Hub 时 `/etc/docker/daemon.json` 配国内 registry-mirrors；RAGFlow 下载模型走 `HF_ENDPOINT=https://hf-mirror.com`
- 无 GPU 时 RAGFlow 用 `DEVICE=cpu`，解析/向量化慢 5~10 倍，测试够用

## 1. RAGFlow（官方 compose，CPU）

```bash
git clone --depth 1 --branch v1.0.0-rc1 https://github.com/infiniflow/ragflow.git
cd ragflow/docker
printf '\nHF_ENDPOINT=https://hf-mirror.com\nDEVICE=cpu\nMEM_LIMIT=16g\nTEI_MODEL=BAAI/bge-m3\nTEI_BASE_URL=http://tei:80\n' >> .env   # COMPOSE_PROFILES 追加 tei-cpu；TEI_BASE_URL 必填，否则 ragflow-go 默认连 localhost:6380 导致 add chunk 500
docker compose -f docker-compose.yml up -d
# 首次启动后：浏览器打开 http://<host>/ 注册管理员 → 右上角头像 → API → 生成 API Key
```

RAGFlow API 在容器 `ragflow-cpu:9380`，网络名 `docker_ragflow`（目录名_ragflow，可用 RAGFLOW_NETWORK 覆盖），本目录 compose 以 external 网络接入。

## 2. 仓库服务

```bash
cd deploy/test
cp .env.example .env   # 填 RAGFLOW_API_KEY、模型密钥
docker compose build
docker compose up -d
./smoke.sh             # 健康检查 + BFF 注入会话后的检索链路
```

PC 工作台：`http://<host>:8088`。nginx 只暴露 `/api/*` 给 **BFF**；decide / retrieval / gateway / ingest / matcher / packager / wecom-sync 不直接对浏览器开放。BFF 负责用会话换取 principal，客户端传入的 `principal`、`X-User-Id` 一律丢弃。

服务清单：`retrieval-proxy`、`decide`、`gateway`、`ingest`、`matcher`、`packager`、`wecom-sync`、`bff`、`web`。matcher 与 packager 由 BFF 代理（`/api/matcher/*`、`/api/matcher/checklists/{id}/package`）；浏览器不能直接提交 manifest，资料包必须由 matcher 生成的 manifest 驱动。

## 3. 测试数据

- 只用脱敏文档；L4 文档只登记不推送 RAGFlow（ingest 已实现）。原始文件落 `deploy/test/filestore/`，与 packager 共享；成册产物落 `deploy/test/packages/`
- 通过 `POST /api/ingest/documents`（multipart：file + dept + sensitivity [+ project]）上传；该路由仅对 `BFF_ADMIN_ROLES` 中的角色开放，普通用户 403。ingest 会把 `dept/sensitivity/project` 写入 RAGFlow 文档 `meta_fields`，retrieval-proxy 按 principal 生成 `metadata_condition` 随检索请求下发
- 测试环境用 `BFF_DEV_MODE=true` + `BFF_DEV_PRINCIPAL` 在服务端固定一个会话主体（示例带 `it_admin` 仅为允许 `/api/ingest` 上传）；生产置 false 并接入 wecom-sync / OIDC。权限矩阵由 BFF 单测与现场脚本覆盖，不再依赖前端构建期变量。
- `wecom-sync` 的管理端点（`/identity/sync`、`/identity/grants` 等）默认要求 `X-Admin-Token`；未配置 `IDENTITY_ADMIN_TOKEN` 时这些端点 403，`/identity/me` 不受影响。

## 与正式环境差异

全容器单机、无 HA、MinIO 代 OSS、无专线；上云只换 `.env` 与存储后端。

检索审计落 `deploy/test/audit/retrieval.jsonl`，网关预算账本落 `deploy/test/usage/gateway.sqlite`；两者都是单机文件，正式环境应换审计库 / PostgreSQL（或等价共享存储）。
