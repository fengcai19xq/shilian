package com.shilian.wecomsync.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WecomPropertiesTest {

    @Test
    void toStringMasksSecret() {
        WecomProperties p = new WecomProperties("ww-test", "dummy-secret-value", "https://example.invalid");
        assertThat(p.configured()).isTrue();
        assertThat(p.toString()).doesNotContain("dummy-secret-value").contains("******");
    }

    @Test
    void unsetWhenBlank() {
        WecomProperties p = new WecomProperties("", "", null);
        assertThat(p.configured()).isFalse();
        assertThat(p.toString()).contains("<unset>");
    }
}
