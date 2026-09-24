# gateway 契约

所有模型调用必须经网关，**禁止模块内直接持有模型密钥**。

## 调用

```json
{
  "purpose": "generate | decide | embed | rerank",
  "sensitivity": "L1 | L2 | L3 | L4",
  "preferred_model": "deepseek-v3",
  "principal": { "user_id": "u_10231", "dept": ["融资部"] },
  "payload": { "...": "按 purpose 不同" }
}
```

## 密级路由

| 密级 | 允许通道 | 说明 |
| --- | --- | --- |
| L1 | 通用公有云 API | 公开资料 |
| L2 | 企业版 API（已签 DPA、不用于训练） | 内部制度流程 |
| L3 | 企业版 API + 调用前实体脱敏 | 合同、报价、财务 |
| L4 | 仅 VPC 内自部署模型 | 核心工艺、配方、未公开技术路线 |

`preferred_model` 只是建议。密级不允许时网关**降级到合规通道并在返回里标注 `switched_reason`**，由前端显式提示用户——静默切换视为缺陷。

## 用量与成本

返回体必带：

```json
{ "model": "deepseek-v3-enterprise", "switched_reason": "L3 内容不允许通用通道",
  "usage": { "prompt_tokens": 2841, "completion_tokens": 512, "cost_cny": 0.14 } }
```

按 `dept + purpose + model` 出账。达月度预算 80% 告警，100% 自动降级到低成本模型并通知数智化中心。

## 省 token 的四条默认策略

1. 判定类（路由、打标、相关性）走 Jev 或本地小模型，不用大模型
2. 路由探测检索用 `mode=probe`，`top_k=3`、不 rerank
3. 相同 query 与相同文档打标结果做缓存，命中直接返回
4. 上下文按 rerank 后的 top 片段截断，不整篇塞
