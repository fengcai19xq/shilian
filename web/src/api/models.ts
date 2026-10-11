// 模型目录：gateway 契约未提供模型列表接口，mock/http 两种模式共用此静态目录。
// 分组对齐 contracts/gateway.md 的密级路由通道；不合规项置灰并写明原因。
import type { Channel, ModelOption } from './types'

export const CHANNEL_LABEL: Record<Channel, string> = {
  public_api: '通用公有云',
  enterprise_api: '企业版 API',
  vpc_self_hosted: 'VPC 自部署',
}

/** 选择器分组标题：写明每组允许的密级 */
export const CHANNEL_GROUP_TITLE: Record<Channel, string> = {
  public_api: '通用公有云（仅 L1 公开资料）',
  enterprise_api: '企业版 API（L2/L3，L3 调用前脱敏）',
  vpc_self_hosted: 'VPC 自部署（可处理 L4）',
}

export const CHANNEL_ORDER: Channel[] = ['public_api', 'enterprise_api', 'vpc_self_hosted']

export const MODEL_CATALOG: ModelOption[] = [
  { model: 'deepseek-v3', label: 'DeepSeek V3（公有云）', group: 'public_api', disabled: false },
  { model: 'qwen-max', label: '通义千问 Max（公有云）', group: 'public_api', disabled: false },
  {
    model: 'deepseek-v3-enterprise',
    label: 'DeepSeek V3 企业版',
    group: 'enterprise_api',
    disabled: false,
  },
  {
    model: 'gpt-4o',
    label: 'GPT-4o',
    group: 'enterprise_api',
    disabled: true,
    disabled_reason: '未签数据处理协议',
  },
  {
    model: 'claude-3-5-sonnet',
    label: 'Claude 3.5 Sonnet',
    group: 'enterprise_api',
    disabled: true,
    disabled_reason: '数据出境未备案',
  },
  {
    model: 'hangli-local-14b',
    label: '航锂自部署 14B（VPC 内）',
    group: 'vpc_self_hosted',
    disabled: false,
  },
]
