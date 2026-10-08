package com.shilian.matcher.support;

import com.shilian.matcher.config.MatcherConfig;
import com.shilian.matcher.config.ParserConfig;
import com.shilian.matcher.config.ScoringConfig;
import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.Constraints;
import com.shilian.matcher.model.Scope;
import java.util.List;

/** 测试公共夹具（对应 Python tests/conftest.py）：全部使用假实现，测试数据均为虚构。 */
public final class Fixtures {

    private Fixtures() {
    }

    /** conftest.config：reference_year=2023。 */
    public static MatcherConfig config() {
        return new MatcherConfig(new ScoringConfig(), ParserConfig.withReferenceYear(2023));
    }

    public static ItemBuilder item() {
        return new ItemBuilder();
    }

    public static DocBuilder doc() {
        return new DocBuilder();
    }

    public static ChecklistItem makeItem() {
        return item().build();
    }

    public static CandidateDoc makeDoc() {
        return doc().build();
    }

    public static final class ItemBuilder {
        private String itemId = "it_0031";
        private String checklistId = "cl_0007";
        private String no = "3.1";
        private String rawText = "2023年度审计报告（合并口径，加盖公章，3份）";
        private String stdType = "AUDIT_REPORT";
        private String typeStatus = "resolved";
        private String stdName = "审计报告";
        private Constraints constraints = new Constraints(List.of("2023"), Scope.CONSOLIDATED, 3, true);

        public ItemBuilder rawText(String v) {
            rawText = v;
            return this;
        }

        public ItemBuilder stdType(String v) {
            stdType = v;
            return this;
        }

        public ItemBuilder typeStatus(String v) {
            typeStatus = v;
            return this;
        }

        public ItemBuilder stdName(String v) {
            stdName = v;
            return this;
        }

        public ChecklistItem build() {
            return new ChecklistItem(itemId, checklistId, no, rawText, stdType, typeStatus, stdName, constraints);
        }
    }

    public static final class DocBuilder {
        private String docId = "d_2031";
        private String docName = "2023年度审计报告.pdf";
        private String uri = "oss://docs/d_2031.pdf";
        private String stdType = "AUDIT_REPORT";
        private List<String> period = List.of("2023");
        private Scope scope = Scope.CONSOLIDATED;
        private Integer copies = 3;
        private Boolean stamped = true;
        private double recallScore = 1.0;
        private double formScore = 1.0;

        public DocBuilder docId(String v) {
            docId = v;
            return this;
        }

        public DocBuilder docName(String v) {
            docName = v;
            return this;
        }

        public DocBuilder uri(String v) {
            uri = v;
            return this;
        }

        public DocBuilder stdType(String v) {
            stdType = v;
            return this;
        }

        public DocBuilder period(String... v) {
            period = List.of(v);
            return this;
        }

        public DocBuilder scope(Scope v) {
            scope = v;
            return this;
        }

        public DocBuilder copies(Integer v) {
            copies = v;
            return this;
        }

        public DocBuilder stamped(Boolean v) {
            stamped = v;
            return this;
        }

        public DocBuilder recallScore(double v) {
            recallScore = v;
            return this;
        }

        public DocBuilder formScore(double v) {
            formScore = v;
            return this;
        }

        public CandidateDoc build() {
            return new CandidateDoc(docId, docName, uri, stdType, period, scope, copies, stamped, null,
                    recallScore, formScore);
        }
    }
}
