package com.shilian.dispatch.port;

/**
 * 回传后通知 matcher 重新匹配（真实实现走 POST /matcher/checklists/{id}/run）。
 * 派单侧只把工单置为 uploaded，不替 matcher 判定匹配。
 */
public interface RematchNotifier {

    void requestRematch(String checklistId, String itemId, String ticketId, String docId);
}
