package com.shilian.matcher.port;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import java.util.List;

/** 未接入检索时的默认实现：不返回任何候选，全部项落 missing，不产生假阳性。 */
public class NullCandidateProvider implements CandidateProvider {
    @Override
    public List<CandidateDoc> recall(ChecklistItem item) {
        return List.of();
    }
}
