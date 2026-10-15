package com.shilian.dispatch.port;

import java.util.ArrayList;
import java.util.List;

/** matcher fake：只记录重匹配请求。 */
public class InMemoryRematchNotifier implements RematchNotifier {

    /** 一次重匹配请求。 */
    public record Request(String checklistId, String itemId, String ticketId, String docId) {
    }

    private final List<Request> requests = new ArrayList<>();

    @Override
    public synchronized void requestRematch(String checklistId, String itemId, String ticketId, String docId) {
        requests.add(new Request(checklistId, itemId, ticketId, docId));
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }
}
