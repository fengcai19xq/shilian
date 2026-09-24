# packaging 契约

执行器是**纯函数**：输入 manifest，输出一组文件。不查库、不调模型、不做判断，因此可放沙箱、可重放、可单测。

## manifest

```json
{
  "package_id": "pkg_0007_v1",
  "title": "中信银行授信资料包",
  "watermark": "仅供中信银行授信审查使用 · 2026-03-12",
  "outputs": ["zip", "merged_pdf", "checklist_xlsx"],
  "entries": [
    {
      "no": "3.1",
      "std_name": "最近三年审计报告",
      "files": [
        { "uri": "oss://docs/d_2031.pdf", "pages": null, "order": 1 }
      ],
      "status": "matched",
      "note": ""
    },
    {
      "no": "6.3",
      "std_name": "最新版公司章程",
      "files": [],
      "status": "missing",
      "note": "库内仅 2022 版，需最新备案版本"
    }
  ]
}
```

## 输出

| 产物 | 规格 |
| --- | --- |
| `zip` | 按清单编号建目录 `01_营业执照/`，文件名 `编号_标准名称_期间.pdf` |
| `merged_pdf` | 封面 + 目录（带页码）+ 正文 + 每页水印 |
| `checklist_xlsx` | 清单项 / 对应文件 / 页码 / 状态 / 备注，缺失项标红并注明责任部门 |

## 硬约束

- 沙箱内运行：禁公网出口、只挂临时目录、不挂生产数据库凭据、用后即销
- 产物落对象存储，返回带时效的签名 URL
- **资料包生成后仍需人工终审并登记外发，系统不自动对外发送**
