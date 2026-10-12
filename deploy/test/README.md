# 机房测试环境（单机 docker compose）

目标：PC 工作台 → decide 路由 → retrieval-proxy 权限前置检索（RAGFlow）→ gateway 调模型；ingest 上传解析入库。
不含 Dify / IM 入口 / WeKnora（首期范围外）。

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
./smoke.sh             # 健康检查 + 人事部越权检索必须 total=0
```

PC 工作台：`http://<host>:8088`。nginx 代理规则与 `web/vite.config.ts` 一致（`/api/decide`、`/api/retrieval`、`/api/gateway`、`/api/ingest`）。

## 3. 测试数据

- 只用脱敏文档；L4 文档只登记不推送 RAGFlow（ingest 已实现）
- 通过 `POST /api/ingest/documents`（multipart：file + dept + sensitivity [+ project]）上传，ingest 会把 `dept/sensitivity/project` 写入 RAGFlow 文档 `meta_fields`，retrieval-proxy 按 principal 生成 `metadata_condition` 随检索请求下发
- 建议最小账号集：融资部员工 / 人事员工 / 无授权者，前端 `.env` 用 `VITE_DEV_PRINCIPAL` 模拟

## 与正式环境差异

全容器单机、无 HA、MinIO 代 OSS、无专线；上云只换 `.env` 与存储后端。
