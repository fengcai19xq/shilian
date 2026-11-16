package com.shilian.matcher.port;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 最小生产版 p(satisfies_item)：基于清单项标准名/原文与候选文件名的词面覆盖。
 *
 * <p>不调模型、无网络、可解释；判定类任务优先走本地/规则实现（AGENTS.md 省 token）。
 * 这不是最终的语义判定，T03 黄金集到位后应做阈值回归并考虑 Jev/小模型替换；
 * 在此之前用保守的 0.55 基线 + 覆盖度 + 类型一致性，宁可 pending 也不轻易 matched。
 */
public class LexicalDecideProvider implements DecideProvider {

    @Override
    public DecideResult decide(ChecklistItem item, CandidateDoc candidate) {
        String query = item.stdName() == null || item.stdName().isBlank()
                ? item.rawText()
                : item.stdName();
        Set<String> wanted = tokens(query);
        if (wanted.isEmpty()) {
            return new DecideResult(0.4, "清单项无可用词面特征");
        }
        Set<String> actual = tokens(candidate.docName() + " " + candidate.stdType());
        int overlap = 0;
        for (String token : wanted) {
            if (actual.contains(token)) {
                overlap++;
            }
        }
        double coverage = (double) overlap / wanted.size();
        boolean typeMatch = item.stdType() != null && item.stdType().equals(candidate.stdType());
        double probability = 0.55 + 0.30 * coverage + (typeMatch ? 0.10 : 0.0);
        probability = Math.max(0.0, Math.min(0.95, probability));
        return new DecideResult(probability,
                "词面覆盖 " + Math.round(coverage * 100) + "%" + (typeMatch ? "，类型一致" : ""));
    }

    /** 中文按二元组、ASCII 按长度 >=2 的词切分；只用于本地判定，不追求语言完整性。 */
    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i + 1 < normalized.length(); i++) {
            char a = normalized.charAt(i);
            char b = normalized.charAt(i + 1);
            if (isCjk(a) && isCjk(b)) {
                out.add(normalized.substring(i, i + 2));
            }
        }
        for (String word : normalized.split("[^a-z0-9]+")) {
            if (word.length() >= 2) {
                out.add(word);
            }
        }
        return out;
    }

    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
