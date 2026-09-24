# contracts —— 跨模块接口契约

这里是所有模块之间的唯一约定来源。**任何模块不得直接依赖另一个模块的内部实现**，只依赖本目录。

修改规则：

- 契约改动必须单独提 PR，并 @ 所有受影响模块的负责人
- 只增不改：新增可选字段无需协商；删除或改语义必须走评审
- 子会话开发时 `contracts/` 为只读

| 文件 | 约定内容 | 消费方 |
| --- | --- | --- |
| `retrieval.md` | 权限四元组、检索请求与返回 | web / decide / matcher |
| `matching.md` | 清单项、候选、三态与打分 | web / packager |
| `packaging.md` | manifest 与产物规格 | matcher / web |
| `gateway.md` | 模型调用、密级路由、用量计费 | 全部 |
