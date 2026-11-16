package com.shilian.matcher.port;

import static com.shilian.matcher.support.Fixtures.doc;
import static com.shilian.matcher.support.Fixtures.makeItem;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LexicalDecideProviderTest {

    private final LexicalDecideProvider provider = new LexicalDecideProvider();

    @Test
    void 文件名覆盖标准名时给出高分但仍不越界() {
        DecideResult r = provider.decide(makeItem(), doc().docName("2023年度审计报告.pdf").build());
        assertThat(r.p()).isGreaterThanOrEqualTo(0.9).isLessThanOrEqualTo(0.95);
        assertThat(r.reason()).contains("类型一致");
    }

    @Test
    void 无关文件名给出低分() {
        DecideResult r = provider.decide(makeItem(), doc().docName("员工考勤表.xlsx")
                .stdType("ATTENDANCE").build());
        assertThat(r.p()).isLessThan(0.7);
    }

    @Test
    void 类型不一致会降分() {
        DecideResult match = provider.decide(makeItem(), doc().docName("审计报告.pdf").build());
        DecideResult mismatch = provider.decide(makeItem(), doc().docName("审计报告.pdf")
                .stdType("OTHER").build());
        assertThat(mismatch.p()).isLessThan(match.p());
    }
}
