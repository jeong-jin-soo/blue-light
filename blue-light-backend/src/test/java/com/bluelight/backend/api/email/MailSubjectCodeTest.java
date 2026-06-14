package com.bluelight.backend.api.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MailSubjectCode - 메일 코드 prefix")
class MailSubjectCodeTest {

    @Test
    @DisplayName("비운영(default/dev) 프로필 - [코드] prefix 부착")
    void prefix_nonProd() {
        assertThat(MailSubjectCode.prefix("default", "A-17")).isEqualTo("[A-17] ");
        assertThat(MailSubjectCode.prefix("dev", "A-17")).isEqualTo("[A-17] ");
        assertThat(MailSubjectCode.prefix(null, "A-17")).isEqualTo("[A-17] ");
        assertThat(MailSubjectCode.prefix("", "A-17")).isEqualTo("[A-17] ");
    }

    @Test
    @DisplayName("운영(prod) 프로필 - prefix 없음")
    void prefix_prod() {
        assertThat(MailSubjectCode.prefix("prod", "A-17")).isEmpty();
        assertThat(MailSubjectCode.prefix("PROD", "A-17")).isEmpty();
        assertThat(MailSubjectCode.prefix("dev,prod", "A-17")).isEmpty();
    }

    @Test
    @DisplayName("코드 없음(null/blank) - prefix 없음")
    void prefix_noCode() {
        assertThat(MailSubjectCode.prefix("default", null)).isEmpty();
        assertThat(MailSubjectCode.prefix("default", "")).isEmpty();
        assertThat(MailSubjectCode.prefix("default", "  ")).isEmpty();
    }

    @Test
    @DisplayName("isProdProfile - prod 포함 여부")
    void isProdProfile() {
        assertThat(MailSubjectCode.isProdProfile("prod")).isTrue();
        assertThat(MailSubjectCode.isProdProfile("dev,prod")).isTrue();
        assertThat(MailSubjectCode.isProdProfile("default")).isFalse();
        assertThat(MailSubjectCode.isProdProfile(null)).isFalse();
        assertThat(MailSubjectCode.isProdProfile("production")).isFalse();
    }
}
