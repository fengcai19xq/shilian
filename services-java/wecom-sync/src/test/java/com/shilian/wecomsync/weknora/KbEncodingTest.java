package com.shilian.wecomsync.weknora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * KB 名 / 项目标签编码必须与另外两处逐字一致：
 *   - retrieval-proxy: src/retrieval_proxy/kb_encoding.py（事实源）
 *   - ingest:          com.shilian.ingest.ragflow.KbEncoding
 * 三处的期望串都取自同一个 Python 实现的真实输出，
 * 生成脚本：spike/weknora/gen_expect.py。
 *
 * 任一处偏离都不会报错，只会让「同步侧建的库」与「检索侧解析的库」对不上，
 * 表现为文档入库成功但永远查不到 —— 静默漏料，故必须在测试里钉死。
 */
class KbEncodingTest {

    @Test
    void slugMatchesPython() {
        assertThat(KbEncoding.slug("融资部")).isEqualTo("878d8d4490e8");
        assertThat(KbEncoding.slug("风控部")).isEqualTo("98ce63a790e8");
        assertThat(KbEncoding.slug("default")).isEqualTo("default");
        assertThat(KbEncoding.slug("Risk_Control")).isEqualTo("risk_control");
        // 含空格 / 非 ASCII → 整串码点变长 hex
        assertThat(KbEncoding.slug("财务 共享")).isEqualTo("8d2252a12051714eab");
    }

    @Test
    void slugRejectsEmpty() {
        assertThatThrownBy(() -> KbEncoding.slug("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KbEncoding.slug(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kbNameMatchesPython() {
        assertThat(KbEncoding.kbName("acme", "L1", "融资部")).isEqualTo("sl-acme-L1-878d8d4490e8");
        assertThat(KbEncoding.kbName("acme", "L4", "风控部")).isEqualTo("sl-acme-L4-98ce63a790e8");
        assertThat(KbEncoding.kbName("default", "L2", "融资部")).isEqualTo("sl-default-L2-878d8d4490e8");
    }

    @Test
    void kbNameRejectsUnknownSensitivity() {
        assertThatThrownBy(() -> KbEncoding.kbName("acme", "L9", "融资部"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KbEncoding.kbName("acme", null, "融资部"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectTagMatchesPython() {
        assertThat(KbEncoding.projectTag(null)).isEqualTo("project::__none__");
        assertThat(KbEncoding.projectTag("   ")).isEqualTo("project::__none__");
        assertThat(KbEncoding.projectTag("PRJ-DEMO-A"))
                .isEqualTo("project::p00500052004a002d00440045004d004f002d0041");
        assertThat(KbEncoding.projectTag("PRJ-2026-CITIC"))
                .isEqualTo("project::p00500052004a002d0032003000320036002d00430049005400490043");
        assertThat(KbEncoding.projectTag("宁波-授信")).isEqualTo("project::p5b816ce2002d63884fe1");
    }

    @Test
    void caseNeverCollapsed() {
        // 与 ingest 侧同源要求：大小写折叠会让兜底校验误判、静默丢已授权文档
        assertThat(KbEncoding.projectTag("PRJ-A")).isNotEqualTo(KbEncoding.projectTag("prj-a"));
    }

    @Test
    void sensitivitiesAreFourInOrder() {
        assertThat(List.of(KbEncoding.SENSITIVITIES)).containsExactly("L1", "L2", "L3", "L4");
        // 预置 KB 按 部门 × 4 密级 叉乘，四个密级必须齐全
        assertThat(KbEncoding.SENSITIVITY_ORDER).containsExactlyInAnyOrder("L1", "L2", "L3", "L4");
    }
}
