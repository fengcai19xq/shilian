package com.shilian.matcher.port;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;

/** decide 判定接口；真实环境经 HTTP 调 services/decide，测试注入 fake。 */
public interface DecideProvider {
    DecideResult decide(ChecklistItem item, CandidateDoc candidate);
}
