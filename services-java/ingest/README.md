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
| `ingest.ragflow.base-url` / `api-key` | `RAGFLOW_BASE_URL` / `RAGFLOW_API_KEY` | 空（真实客户端未实现） |
| `ingest.ragflow.default-dataset` | `RAGFLOW_DEFAULT_DATASET` | kb_default |
| `ingest.ragflow.dept-datasets[部门]` | — | 部门 → 数据集名称映射 |

## 测试样例

`src/test/resources/samples/` 下的文件全部由 `src/test/java/.../support/SampleFileGenerator.java` 生成，内容为虚构脱敏数据；样例中的加密口令仅用于测试。重新生成：

```bash
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
java -cp target/test-classes:target/classes:$(cat target/cp.txt) \
  com.shilian.ingest.support.SampleFileGenerator src/test/resources/samples
```

## 未实现（TODO）

- 真实 RAGFlow HTTP 客户端（`RagflowClient` 的实现，见接口注释 `TODO(T06-real)`）
- 数据库版 `DocumentRepository`、审计模块 HTTP 版 `AuditSink`
- 扫描件 OCR、旧版 .doc/.xls/.ppt、加密文档解密
- 管理端接口的访问控制（当前依赖网关在前面鉴权）
