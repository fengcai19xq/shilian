package com.shilian.matcher.core;

import com.shilian.matcher.config.ScoringConfig;
import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.ItemResult;
import com.shilian.matcher.model.ItemStatus;
import com.shilian.matcher.model.ScoredCandidate;
import com.shilian.matcher.model.TypeStatus;
import com.shilian.matcher.port.DecideProvider;
import com.shilian.matcher.port.DecideResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * 打分与三态判定。
 *
 * <p>final_score = 0.35×recall_score + 0.50×p(satisfies_item) + 0.15×form_score；
 * 硬约束不通过的候选先被否决，final_score 记 0，不参与择优。
 */
public final class Scoring {

    private Scoring() {
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static ScoredCandidate scoreCandidate(ChecklistItem item, CandidateDoc candidate,
            DecideProvider decide, ScoringConfig config) {
        List<String> reasons = HardConstraints.check(item, candidate);
        if (!reasons.isEmpty()) {
            // 硬约束先行：不调 decide，省 token 也避免高分翻盘
            return new ScoredCandidate(candidate.docId(), candidate.docName(), candidate.uri(), true, reasons,
                    candidate.recallScore(), 0.0, candidate.formScore(), 0.0, "");
        }
        DecideResult result = decide.decide(item, candidate);
        double p = clamp(result.p());
        double fin = config.wRecall() * clamp(candidate.recallScore())
                + config.wDecide() * p
                + config.wForm() * clamp(candidate.formScore());
        return new ScoredCandidate(candidate.docId(), candidate.docName(), candidate.uri(), false, List.of(),
                candidate.recallScore(), p, candidate.formScore(), round6(fin), result.reason());
    }

    public static ItemStatus decideStatus(double score, ScoringConfig config) {
        if (score >= config.matchedThreshold()) {
            return ItemStatus.MATCHED;
        }
        if (score >= config.pendingThreshold()) {
            return ItemStatus.PENDING;
        }
        return ItemStatus.MISSING;
    }

    public static ItemResult scoreItem(ChecklistItem item, List<CandidateDoc> candidates,
            DecideProvider decide, ScoringConfig config) {
        List<ScoredCandidate> scored = new ArrayList<>();
        for (CandidateDoc c : candidates) {
            scored.add(scoreCandidate(item, c, decide, config));
        }
        ScoredCandidate best = null;
        for (ScoredCandidate s : scored) {
            if (!s.rejected() && (best == null || s.finalScore() > best.finalScore())) {
                best = s;
            }
        }
        // 未否决的在前，同组按分数降序；稳定排序保留召回顺序
        scored.sort(Comparator.comparing(ScoredCandidate::rejected)
                .thenComparing(Comparator.comparingDouble(ScoredCandidate::finalScore).reversed()));

        double score = best != null ? best.finalScore() : 0.0;
        ItemStatus status = best != null ? decideStatus(score, config) : ItemStatus.MISSING;
        List<String> bound = best != null && status == ItemStatus.MATCHED ? List.of(best.docId()) : List.of();

        String note = "";
        if (best == null && !scored.isEmpty()) {
            TreeSet<String> reasons = new TreeSet<>();
            scored.forEach(s -> reasons.addAll(s.rejectReasons()));
            note = "全部候选被硬约束否决：" + String.join(",", reasons);
        } else if (scored.isEmpty()) {
            note = "库内无候选";
        }
        if (TypeStatus.UNKNOWN.equals(item.typeStatus())) {
            note = (note.isEmpty() ? "" : note + "；") + "资料类型未在词典中，需人工补充词典";
        }
        return new ItemResult(item.itemId(), item.no(), item.stdType(), item.stdName(), status, score, scored,
                bound, null, note);
    }

    private static double round6(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_EVEN).doubleValue();
    }
}
