package com.shilian.ingest.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocStatusTest {

    @Test
    void allowedTransitions() {
        assertThat(DocStatus.UPLOADED.canTransitTo(DocStatus.PARSING)).isTrue();
        assertThat(DocStatus.UPLOADED.canTransitTo(DocStatus.HELD_L4)).isTrue();
        assertThat(DocStatus.PARSING.canTransitTo(DocStatus.INDEXED)).isTrue();
        assertThat(DocStatus.PARSING.canTransitTo(DocStatus.FAILED)).isTrue();
    }

    @Test
    void terminalStatesAreFinal() {
        for (DocStatus terminal : new DocStatus[] {DocStatus.INDEXED, DocStatus.FAILED, DocStatus.HELD_L4}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (DocStatus next : DocStatus.values()) {
                assertThat(terminal.canTransitTo(next)).isFalse();
            }
        }
        assertThat(DocStatus.UPLOADED.canTransitTo(DocStatus.INDEXED)).isFalse();
        assertThat(DocStatus.PARSING.canTransitTo(DocStatus.HELD_L4)).isFalse();
    }

    @Test
    void wireNamesAreSnakeCase() {
        assertThat(DocStatus.HELD_L4.wire()).isEqualTo("held_l4");
        assertThat(DocStatus.parse("indexed")).isEqualTo(DocStatus.INDEXED);
        assertThatThrownBy(() -> DocStatus.parse("INDEXED")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sensitivityParsing() {
        assertThat(Sensitivity.parse(" l3 ")).isEqualTo(Sensitivity.L3);
        assertThatThrownBy(() -> Sensitivity.parse("L5")).hasMessageContaining("L1~L4");
        assertThatThrownBy(() -> Sensitivity.parse(" ")).hasMessageContaining("sensitivity");
    }
}
