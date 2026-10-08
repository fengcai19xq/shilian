package com.shilian.matcher;

import static com.shilian.matcher.core.HardConstraints.COPIES_INSUFFICIENT;
import static com.shilian.matcher.core.HardConstraints.PERIOD_MISMATCH;
import static com.shilian.matcher.core.HardConstraints.PERIOD_UNKNOWN;
import static com.shilian.matcher.core.HardConstraints.SCOPE_MISMATCH;
import static com.shilian.matcher.core.HardConstraints.STAMP_MISSING;
import static com.shilian.matcher.core.HardConstraints.TYPE_MISMATCH;
import static com.shilian.matcher.support.Fixtures.doc;
import static com.shilian.matcher.support.Fixtures.item;
import static com.shilian.matcher.support.Fixtures.makeDoc;
import static com.shilian.matcher.support.Fixtures.makeItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shilian.matcher.config.ScoringConfig;
import com.shilian.matcher.core.HardConstraints;
import com.shilian.matcher.core.Scoring;
import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.ItemResult;
import com.shilian.matcher.model.ItemStatus;
import com.shilian.matcher.model.Scope;
import com.shilian.matcher.model.ScoredCandidate;
import com.shilian.matcher.support.FakeDecideProvider;
import com.shilian.matcher.support.Fixtures.DocBuilder;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 硬约束与三态打分（对应 Python tests/test_scoring.py）。 */
class ScoringTest {

    private static final ScoringConfig CFG = new ScoringConfig();

    @Test
    void formulaWeights() {
        CandidateDoc d = doc().recallScore(0.8).formScore(0.6).build();
        ScoredCandidate s = Scoring.scoreCandidate(makeItem(), d, new FakeDecideProvider(0.9), CFG);
        assertThat(s.finalScore()).isCloseTo(0.35 * 0.8 + 0.50 * 0.9 + 0.15 * 0.6, within(1e-6));
    }

    static Stream<Arguments> hardConstraintCases() {
        return Stream.of(
                Arguments.of("period=[2022]", (Consumer<DocBuilder>) b -> b.period("2022"), PERIOD_MISMATCH),
                Arguments.of("period=[]", (Consumer<DocBuilder>) b -> b.period(), PERIOD_UNKNOWN),
                Arguments.of("scope=standalone", (Consumer<DocBuilder>) b -> b.scope(Scope.STANDALONE), SCOPE_MISMATCH),
                Arguments.of("copies=2", (Consumer<DocBuilder>) b -> b.copies(2), COPIES_INSUFFICIENT),
                Arguments.of("stamped=false", (Consumer<DocBuilder>) b -> b.stamped(false), STAMP_MISSING),
                Arguments.of("std_type=FINANCIAL_STATEMENT",
                        (Consumer<DocBuilder>) b -> b.stdType("FINANCIAL_STATEMENT"), TYPE_MISMATCH));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("hardConstraintCases")
    void eachHardConstraintRejects(String label, Consumer<DocBuilder> override, String reason) {
        DocBuilder b = doc();
        override.accept(b);
        assertThat(HardConstraints.check(makeItem(), b.build())).contains(reason);
    }

    /** 硬约束否决优先于高分：召回、形式、decide 全满分也必须 rejected，且不调 decide。 */
    @Test
    void hardConstraintVetoBeatsHighScore() {
        FakeDecideProvider decide = new FakeDecideProvider(1.0);
        CandidateDoc bad = doc().scope(Scope.STANDALONE).recallScore(1.0).formScore(1.0).build();
        ScoredCandidate s = Scoring.scoreCandidate(makeItem(), bad, decide, CFG);
        assertThat(s.rejected()).isTrue();
        assertThat(s.finalScore()).isEqualTo(0.0);
        assertThat(decide.calls).isEmpty();

        ItemResult result = Scoring.scoreItem(makeItem(), List.of(bad), decide, CFG);
        assertThat(result.status()).isEqualTo(ItemStatus.MISSING);
        assertThat(result.boundDocIds()).isEmpty();
        assertThat(result.note()).contains("scope_mismatch");
    }

    @Test
    void period2022ReportFor2023ItemRejected() {
        ChecklistItem it = item().rawText("2023年度审计报告").build();
        CandidateDoc d = doc().docId("d_2022").docName("2022年度审计报告.pdf").period("2022").build();
        ItemResult result = Scoring.scoreItem(it, List.of(d), new FakeDecideProvider(1.0), CFG);
        assertThat(result.candidates().get(0).rejected()).isTrue();
        assertThat(result.candidates().get(0).rejectReasons()).contains(PERIOD_MISMATCH);
        assertThat(result.status()).isEqualTo(ItemStatus.MISSING);
    }

    @Test
    void rejectedHighScoreDoesNotOutrankValidLowScore() {
        CandidateDoc bad = doc().docId("bad").period("2022").build();
        CandidateDoc ok = doc().docId("ok").recallScore(0.6).formScore(0.6).build();
        FakeDecideProvider decide = new FakeDecideProvider(Map.of("ok", 0.6));
        ItemResult result = Scoring.scoreItem(makeItem(), List.of(bad, ok), decide, CFG);
        assertThat(result.candidates().get(0).docId()).isEqualTo("ok");
        assertThat(result.status()).isEqualTo(ItemStatus.PENDING);
        assertThat(result.boundDocIds()).isEmpty(); // pending 不自动绑定
    }

    static Stream<Arguments> boundaryCases() {
        return Stream.of(
                Arguments.of(0.85, ItemStatus.MATCHED),
                Arguments.of(0.8499, ItemStatus.PENDING),
                Arguments.of(0.5, ItemStatus.PENDING),
                Arguments.of(0.4999, ItemStatus.MISSING),
                Arguments.of(0.0, ItemStatus.MISSING),
                Arguments.of(1.0, ItemStatus.MATCHED));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("boundaryCases")
    void threeStateBoundaries(double score, ItemStatus status) {
        assertThat(Scoring.decideStatus(score, CFG)).isEqualTo(status);
    }

    @Test
    void threeStateBoundaryViaFormula() {
        // recall=form=1.0 时 final = 0.5 + 0.5p，p=0.7 恰好 0.85
        ChecklistItem it = makeItem();
        ItemResult hit = Scoring.scoreItem(it, List.of(makeDoc()), new FakeDecideProvider(0.7), CFG);
        assertThat(hit.score()).isCloseTo(0.85, within(1e-6));
        assertThat(hit.status()).isEqualTo(ItemStatus.MATCHED);
        assertThat(hit.boundDocIds()).containsExactly("d_2031");
        ItemResult below = Scoring.scoreItem(it, List.of(makeDoc()), new FakeDecideProvider(0.69), CFG);
        assertThat(below.status()).isEqualTo(ItemStatus.PENDING);
    }

    @Test
    void thresholdsConfigurable() {
        ScoringConfig strict = ScoringConfig.withThresholds(0.95, 0.7);
        assertThat(Scoring.decideStatus(0.9, strict)).isEqualTo(ItemStatus.PENDING);
        assertThat(Scoring.decideStatus(0.6, strict)).isEqualTo(ItemStatus.MISSING);
    }

    @Test
    void noCandidatesIsMissing() {
        ItemResult result = Scoring.scoreItem(makeItem(), List.of(), new FakeDecideProvider(), CFG);
        assertThat(result.status()).isEqualTo(ItemStatus.MISSING);
        assertThat(result.note()).isEqualTo("库内无候选");
    }

    @Test
    void unknownTypeItemNoted() {
        ChecklistItem it = item().stdType("UNKNOWN").typeStatus("unknown").stdName(null).build();
        ItemResult result = Scoring.scoreItem(it, List.of(), new FakeDecideProvider(), CFG);
        assertThat(result.status()).isEqualTo(ItemStatus.MISSING);
        assertThat(result.note()).contains("词典");
    }

    @Test
    void decideProbabilityClamped() {
        ScoredCandidate s = Scoring.scoreCandidate(makeItem(), makeDoc(), new FakeDecideProvider(7.0), CFG);
        assertThat(s.pSatisfies()).isEqualTo(1.0);
    }
}
