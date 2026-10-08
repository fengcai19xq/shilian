package com.shilian.matcher.port;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;

/** 未接入 decide 时的默认实现：p 恒为 0，最高只能落 missing/pending，绝不自动 matched。 */
public class RejectAllDecideProvider implements DecideProvider {
    @Override
    public DecideResult decide(ChecklistItem item, CandidateDoc candidate) {
        return new DecideResult(0.0, "decide 未接入");
    }
}
