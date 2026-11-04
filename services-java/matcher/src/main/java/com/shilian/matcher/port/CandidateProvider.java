package com.shilian.matcher.port;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import java.util.List;

/**
 * 候选召回接口；真实环境经 HTTP 调 services/retrieval-proxy（权限过滤在检索请求内完成），测试注入 fake。
 *
 * <p>{@link #recall(ChecklistItem, RetrievalPrincipal)} 是真实链路的正解：权限主体随检索请求
 * 一起下发（红线 1）。旧单参方法保留给「无权限上下文」的桩实现与既有测试，
 * 新实现默认委托到它，从而不破坏 StaticCandidateProvider 等既有 fake。
 */
public interface CandidateProvider {
    List<CandidateDoc> recall(ChecklistItem item);

    /** 带权限主体的召回；默认忽略主体委托单参版本，真实 provider 须覆写。 */
    default List<CandidateDoc> recall(ChecklistItem item, RetrievalPrincipal principal) {
        return recall(item);
    }
}
