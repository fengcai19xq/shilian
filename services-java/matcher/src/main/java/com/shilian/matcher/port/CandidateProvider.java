package com.shilian.matcher.port;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import java.util.List;

/** 候选召回接口；真实环境经 HTTP 调 services/retrieval-proxy（权限过滤在检索请求内完成），测试注入 fake。 */
public interface CandidateProvider {
    List<CandidateDoc> recall(ChecklistItem item);
}
