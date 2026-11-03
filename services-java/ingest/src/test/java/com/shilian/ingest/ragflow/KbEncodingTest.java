package com.shilian.ingest.ragflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 编码必须与 retrieval-proxy/src/retrieval_proxy/kb_encoding.py 逐字一致：
 * 两侧生成的 KB 名 / tag 名任何一个字节不同，检索侧就解析不到本模块创建的库，
 * 文档会变成「入库成功但永远查不到」。期望串直接取自真实 Python 模块输出。
 */
class KbEncodingTest {

    @Test
    void slugMatchesPython() {
        assertThat(KbEncoding.slug("融资部")).isEqualTo("878d8d4490e8");
        assertThat(KbEncoding.slug("风控部")).isEqualTo("98ce63a790e8");
        // ASCII 安全串原样小写保留，便于人工阅读
        assertThat(KbEncoding.slug("default")).isEqualTo("default");
        assertThat(KbEncoding.slug("Risk_Control")).isEqualTo("risk_control");
    }

    @Test
    void slugHexesAnythingUnsafe() {
        // 含中文标点 / 空格 / 大写非 ASCII → 整串码点 hex（变长，与 Python 的 {ord:x} 一致）
        assertThat(KbEncoding.slug("财务 共享")).isEqualTo("8d2252a12051714eab");
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
    }

    @Test
    void projectTagMatchesPython() {
        assertThat(KbEncoding.projectTag(null)).isEqualTo("project::__none__");
        assertThat(KbEncoding.projectTag("   ")).isEqualTo("project::__none__");
        // 恒为 p + 每码点 4 位 hex（不做明文放行，否则与 Python 不一致）
        assertThat(KbEncoding.projectTag("PRJ-DEMO-A"))
                .isEqualTo("project::p00500052004a002d00440045004d004f002d0041");
        assertThat(KbEncoding.projectTag("PRJ-2026-CITIC"))
                .isEqualTo("project::p00500052004a002d0032003000320036002d00430049005400490043");
        assertThat(KbEncoding.projectTag("宁波-授信")).isEqualTo("project::p5b816ce2002d63884fe1");
    }

    @Test
    void distinctProjectsNeverCollide() {
        // 大小写必须编出不同标签，否则兜底校验会误放行（spike 陷阱 3 的根因）
        assertThat(KbEncoding.projectTag("PRJ-A")).isNotEqualTo(KbEncoding.projectTag("prj-a"));
    }
}
