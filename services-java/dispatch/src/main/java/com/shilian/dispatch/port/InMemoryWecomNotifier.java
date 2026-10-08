package com.shilian.dispatch.port;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 企微 fake：记录所有消息；failNext 可模拟发送失败。 */
public class InMemoryWecomNotifier implements WecomNotifier {

    /** 已发送消息。type 为 text / textcard。 */
    public record SentMessage(String type, List<String> toUsers, String title, String content, String url,
                              String btnText) {
    }

    private final List<SentMessage> sent = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean failing;

    public synchronized void setFailing(boolean failing) {
        this.failing = failing;
    }

    public synchronized List<SentMessage> sent() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
    }

    @Override
    public synchronized NotifyResult sendText(List<String> toUsers, String content) {
        return record(new SentMessage("text", List.copyOf(toUsers), null, content, null, null));
    }

    @Override
    public synchronized NotifyResult sendCard(
            List<String> toUsers, String title, String description, String url, String btnText) {
        return record(new SentMessage("textcard", List.copyOf(toUsers), title, description, url, btnText));
    }

    private NotifyResult record(SentMessage m) {
        if (failing) {
            return NotifyResult.failure("fake: wecom unavailable");
        }
        sent.add(m);
        return NotifyResult.success("msg_" + seq.incrementAndGet());
    }
}
