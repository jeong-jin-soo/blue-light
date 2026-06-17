package com.bluelight.backend.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PaynowMasker} 단위 테스트 (PR-PN2).
 */
@DisplayName("PaynowMasker - PR-PN2")
class PaynowMaskerTest {

    @Test
    @DisplayName("마지막 4자만 남기고 마스킹")
    void mask_keepsLast4() {
        assertThat(PaynowMasker.mask("97771983")).isEqualTo("****1983");
        assertThat(PaynowMasker.mask("201837490N")).isEqualTo("******490N");
    }

    @Test
    @DisplayName("4자 이하는 전부 마스킹, null/blank는 null")
    void mask_edgeCases() {
        assertThat(PaynowMasker.mask("1234")).isEqualTo("****");
        assertThat(PaynowMasker.mask("12")).isEqualTo("**");
        assertThat(PaynowMasker.mask(null)).isNull();
        assertThat(PaynowMasker.mask("  ")).isNull();
    }

    @Test
    @DisplayName("앞뒤 공백 trim 후 마스킹")
    void mask_trims() {
        assertThat(PaynowMasker.mask("  97771983 ")).isEqualTo("****1983");
    }
}
