# ingest —— 文档上传、解析与密级标注流水线（T06）

JDK 17 + Spring Boot 3 + Maven，包 `com.shilian.ingest`，端口 `8030`（`INGEST_PORT`）。接口约定见 `contracts/ingest.md`。

## 运行

```bash
mvn -q test                 # 单元 + MockMvc 接口测试
mvn spring-boot:run         # 启动服务，默认内存存储 + 内存 Fake RAGFlow
```

## 结构

| 包 | 说明 |
| --- | --- |
| `web` | `POST /ingest/documents`、`GET /ingest/documents/{id}`、`GET /ingest/documents?dept=&status=` |
| `service` | 流水线编排与状态机：uploaded → parsing → indexed / failed；L4 → held_l4 |
| `parse` | docx（POI XWPF）、pdf（PDFBox）、xlsx（POI XSSF）、pptx（POI XSLF）文本抽取，保留页码 |
| `chunk` | 按字符滑动窗口切块（`ingest.chunk.size` / `ingest.chunk.overlap`），块不跨页 |
| `ragflow` | `RagflowClient` 接口 + `FakeRagflowClient` 内存实现 |
| `audit` | `AuditSink` 接口 + 内存实现，每次状态变化一条 |
| `store` | `DocumentRepository` 接口 + 内存实现 |

## 配置（均可用环境变量覆盖）

| 配置 | 环境变量 | 默认 |
| --- | --- | --- |
| `ingest.chunk.size` | `INGEST_CHUNK_SIZE` | 800 |
| `ingest.chunk.overlap` | `INGEST_CHUNK_OVERLAP` | 100 |
| `ingest.async` | `INGEST_ASYNC` | true（后台线程解析，POST 返回 uploaded） |
| `ingest.sink` | `INGEST_SINK` | ragflow（另一取值为 weknora，见下） |
| `ingest.ragflow.base-url` / `api-key` | `RAGFLOW_BASE_URL` / `RAGFLOW_API_KEY` | 空（空则用内存实现） |
| `ingest.ragflow.default-dataset` | `RAGFLOW_DEFAULT_DATASET` | kb_default |
| `ingest.ragflow.dept-datasets[部门]` | — | 部门 → 数据集名称映射 |

## 两条推送通道（WK10）

| 通道 | 解析方 | 数据集（KB）命名 | 权限落点 |
| --- | --- | --- | --- |
| `ragflow`（默认） | 本地 PDFBox / POI | `ingest.ragflow.dept-datasets` 按部门映射 | dept/sensitivity/project 走文档 `meta_fields`，检索侧翻译成 `metadata_condition` 下推 |
| `weknora` | WeKnora docreader（服务端） | `KbEncoding.kbName(tenant, 密级, 部门)` = `sl-{tenant}-{L1..L4}-{dept-slug}` | 密级+部门复合进 KB 名（一次 IN 表达两个 AND 维度），project 走 tag，period/scope 留在登记记录 |

要点：

- KB 名与标签必须与 `services/retrieval-proxy/src/retrieval_proxy/kb_encoding.py` **逐字一致**，
  否则检索侧解析不到本模块创建的库，文档会变成「入库成功但永远查不到」。
  两侧一致性由 `KbEncodingTest` 钉死（期望串取自真实 Python 输出）。
- WeKnora 通道下本地不解析、不推块：`upsertChunks` 直接抛 `UnsupportedOperationException`，
  杜绝静默丢内容；上传后轮询 `parse_status` 到 `completed` 才置 `indexed`。
- 服务端解析失败只记固定分类文案（加密/损坏/超时/其他），不透传服务端原文，
  避免文件名或内容片段进入 `reason` 与审计（红线 2）。
- 推送或轮询失败会回滚本次写入（删除外部文档），与 RAGFlow 通道同口径。
- L4 两条通道都只登记为 `held_l4`，不解析、不推送（红线 3）。
- 上传需带 `X-Tenant-Id` 请求头；缺省落到 `default` 租户。

## 测试样例

`src/test/resources/samples/` 下的文件全部由 `src/test/java/.../support/SampleFileGenerator.java` 生成，内容为虚构脱敏数据；样例中的加密口令仅用于测试。重新生成：

```bash
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
java -cp target/test-classes:target/classes:$(cat target/cp.txt) \
  com.shilian.ingest.support.SampleFileGenerator src/test/resources/samples
```

## 未实现（TODO）

- 数据库版 `DocumentRepository`、审计模块 HTTP 版 `AuditSink`
- 扫描件 OCR、旧版 .doc/.xls/.ppt、加密文档解密（WeKnora docreader 通道可覆盖大部分格式）
- 管理端接口的访问控制（当前依赖网关在前面鉴权）
- KB / tag 的租户开通自动化（目前按需由 ingest 首次写入时幂等创建，批量预建见 WK14）
