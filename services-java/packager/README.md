# packager（Java）—— 成册执行器（纯函数）

对应任务：T18（Java 重写 `services/packager/`）
接口契约：`contracts/packaging.md`（只读）

manifest 进、文件出。不查库、不调模型、不做判断，沙箱内运行。JDK 17 + Spring Boot 3 + Maven，PDF 用 Apache PDFBox，Excel 用 Apache POI。

## 如何本地跑

```bash
cd services-java/packager
mvn -q test                 # 单元测试（移植自 services/packager/tests，另含 Java 版新增用例）
mvn spring-boot:run         # 启动 HTTP 服务（默认 8080）
```

## 对外接口

- 纯函数：`PackagingExecutor.buildArtifacts(manifest, SourceReader)` → `{OutputKind: byte[]}`
- 落存储：`PackagingExecutor.execute(manifest, Storage)` → `PackageResult`
- HTTP：`POST /v1/packages`，请求体为契约 manifest（snake_case），返回
  `{"package_id", "artifacts": [{"kind", "filename", "uri", "size"}], "missing_entries", "requires_manual_review": true}`；
  manifest 不合法 → 422/400，源文件读不到 → 502，错误体 `{"error", "message"}`

## 实现要点

| 产物 | 说明 |
| --- | --- |
| `zip` | 目录 `01_营业执照/`（编号首段补零），文件名 `编号_标准名称_期间.pdf`；同条目多份追加 `_1`、`_2` |
| `merged_pdf` | 封面 + 目录（条目首页页码）+ 正文 + 每页斜向水印与页脚 `- 页码 / 总页数 -` |
| `checklist_xlsx` | 编号 / 清单项 / 对应文件（zip 内路径）/ 页码（合订本页码区间）/ 状态 / 责任部门 / 备注；缺失标红、待确认标黄 |

- **存储抽象**：`Storage`（`read` / `write` / `signedUrl`）；`LocalStorage`（沙箱临时目录，防目录穿越）、`InMemoryStorage`（内存 fake）、`ObjectStorage`（生产对象存储桩）。配置 `packager.storage.type=local|memory`
- **缺失项**：`missing` 条目不读取源文件、不入 zip 与合订本，只出现在核对表并整行标红，责任部门取 `owner_dept`，未给出时填「待指派」
- **重放字节一致**（zip / pdf / xlsx 三者都是）：zip 用 commons-compress 写，成员固定 DOS 时间 1980-01-01 00:00:00（写完直接改写头部字段，避开时区换算）、固定压缩级别、按 manifest 顺序；PDF 固定 Producer/Creator、不写日期、trailer `/ID` 由 package_id 派生；xlsx 文档属性时间固定，并把 POI 生成的容器按同规则重打包。与运行时区无关
- **字体**：西文 DejaVu Sans + 中文 Droid Sans Fallback，随包内置并子集嵌入，见 `src/main/resources/fonts/README.md`
- 产物生成后 `requires_manual_review=true`，仅落存储，不对外发送
