package com.shilian.matcher.support;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.port.DecideProvider;
import com.shilian.matcher.port.DecideResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 按 doc_id 给定概率，未指定用 default；记录调用以便断言「硬约束否决时不调 decide」。 */
public class FakeDecideProvider implements DecideProvider {

    private final Map<String, Double> probabilities;
    private final double defaultP;
    public final List<String[]> calls = new ArrayList<>();

    public FakeDecideProvider() {
        this(Map.of(), 0.5);
    }

    public FakeDecideProvider(double defaultP) {
        this(Map.of(), defaultP);
    }

    public FakeDecideProvider(Map<String, Double> probabilities) {
        this(probabilities, 0.5);
    }

    public FakeDecideProvider(Map<String, Double> probabilities, double defaultP) {
        this.probabilities = probabilities;
        this.defaultP = defaultP;
    }

    @Override
    public synchronized DecideResult decide(ChecklistItem item, CandidateDoc candidate) {
        calls.add(new String[] {item.itemId(), candidate.docId()});
        return new DecideResult(probabilities.getOrDefault(candidate.docId(), defaultP), "fake");
    }
}
