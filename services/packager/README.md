# packager —— 成册执行器（纯函数）

对应任务：T11
接口契约：`contracts/packaging.md`（只读）

manifest 进、文件出。不查库、不调模型、不做判断，沙箱内运行。

## 开发约定

- 只读写本目录，跨模块需求走契约，不直接改其它模块代码
- 对外只暴露契约里定义的接口，内部结构自由
- 单元测试与本模块同目录，`pytest` 可独立跑通
- 不在代码里硬编码模型密钥与数据库口令，一律读环境变量

## 如何本地跑

需要 Python 3.11（代码兼容 3.10，便于低版本环境自测）。

```bash
cd services/packager
python3.11 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

python -m pytest          # 单元测试
ruff check . && ruff format --check .   # lint
```

最小调用示例（源文件与产物都在本地临时目录）：

```python
from packager import LocalStorage, execute

storage = LocalStorage("/tmp/pkg/mnt", out_dir="/tmp/pkg/out")
# manifest 结构见 contracts/packaging.md；uri 形如 oss://docs/d_2031.pdf，
# LocalStorage 会剥掉 scheme，按 /tmp/pkg/mnt/docs/d_2031.pdf 读取
result = execute(manifest, storage)
for art in result.artifacts:
    print(art.kind, art.uri, storage.signed_url(art.uri))
```

## 实现要点

| 产物 | 说明 |
| --- | --- |
| `zip` | 目录 `01_营业执照/`（编号首段补零），文件名 `编号_标准名称_期间.pdf`；同条目多份追加 `_1`、`_2` |
| `merged_pdf` | 封面 + 目录（条目首页页码）+ 正文 + 每页斜向水印与页脚 `- 页码 / 总页数 -` |
| `checklist_xlsx` | 编号 / 清单项 / 对应文件（zip 内路径）/ 页码（合订本页码区间）/ 状态 / 责任部门 / 备注 |

- **纯函数**：`build_artifacts(manifest, read)` 只依赖传入的读文件函数，不查库、不调模型、不做匹配判断
- **存储抽象**：`Storage` 协议（`read` / `write` / `signed_url`），`LocalStorage` 用于测试与沙箱临时目录，`ObjectStorage` 为生产对象存储桩
- **缺失项**：`missing` 条目不读取源文件、不入 zip 与合订本，只出现在核对表并整行标红，责任部门取 `owner_dept`，未给出时填「待指派」
- **入册规则**：除 `missing` 外有文件的条目都入册（`pending` 在核对表标黄）；入册与否已由上游人工确认决定，执行器不做判断
- **幂等重放**：zip 成员固定时间戳、reportlab `invariant` 模式、PDF 固定元数据，同一 manifest 两次产出的 zip 与 PDF 字节一致；xlsx 仅文档属性时间不同
- **可选扩展字段**（契约只增不改）：`entry.period`、`entry.owner_dept`、`files[].period`；`files[].pages` 为 1 起算页码列表
- 产物生成后 `requires_manual_review=True`，仅落存储，不对外发送
