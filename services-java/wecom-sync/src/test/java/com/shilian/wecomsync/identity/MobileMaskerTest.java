package com.shilian.wecomsync.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MobileMaskerTest {

    @Test
    void keepsFirstThreeAndLastFour() {
        assertThat(MobileMasker.mask("13800001234")).isEqualTo("138****1234");
    }

    @Test
    void stripsCountryCodeAndSeparators() {
        assertThat(MobileMasker.mask("+86 138-0000-1234")).isEqualTo("138****1234");
    }

    @Test
    void shortOrEmptyNumbers() {
        assertThat(MobileMasker.mask("12345")).isEqualTo("****");
        assertThat(MobileMasker.mask("")).isNull();
        assertThat(MobileMasker.mask(null)).isNull();
    }
}
