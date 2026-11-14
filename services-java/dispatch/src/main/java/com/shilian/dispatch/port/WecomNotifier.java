package com.shilian.dispatch.port;

import java.util.List;

/**
 * 企业微信应用消息。真实实现调用企微「发送应用消息」接口，corp_id / agent_id / secret 一律从环境变量读取；
 * 本模块只提供内存 fake（{@link InMemoryWecomNotifier}），不连真实企微。
 */
public interface WecomNotifier {

    /** 文本消息。 */
    NotifyResult sendText(List<String> toUsers, String content);

    /** 文本卡片消息（textcard），url 为点击跳转地址。 */
    NotifyResult sendCard(List<String> toUsers, String title, String description, String url, String btnText);
}
