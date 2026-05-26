package com.bluelight.backend.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationCatalog 빌더·updateMetadata 단위 테스트 (PR-T1).
 *
 * <p>카탈로그 메타는 TemplateLinter(L1)가 변수 화이트리스트 SSOT 로 조회하므로,
 * 필드 누락 없이 영속될 수 있는지 빌더 단계에서 검증한다.</p>
 */
@DisplayName("NotificationCatalog - PR-T1")
class NotificationCatalogTest {

    private NotificationCatalog buildCatalog() {
        return NotificationCatalog.builder()
                .templateCode("A-17")
                .allowedVariablesJson("[\"applicantName\",\"amount\",\"publicCode\",\"paynowUen\"]")
                .defaultCategory(NotificationCategory.PAYMENT)
                .defaultSeverity(NotificationSeverity.CRITICAL)
                .defaultRecipientRoles("APPLICANT")
                .description("결제 요청 알림 (PENDING_PAYMENT 전이)")
                .requiredTokensJson("[\"{{paynowUen}}\",\"{{paynowReference}}\"]")
                .build();
    }

    @Test
    @DisplayName("빌더 - 모든 필드 영속")
    void builder_persistsAllFields() {
        NotificationCatalog catalog = buildCatalog();

        assertThat(catalog.getTemplateCode()).isEqualTo("A-17");
        assertThat(catalog.getAllowedVariablesJson()).contains("applicantName");
        assertThat(catalog.getDefaultCategory()).isEqualTo(NotificationCategory.PAYMENT);
        assertThat(catalog.getDefaultSeverity()).isEqualTo(NotificationSeverity.CRITICAL);
        assertThat(catalog.getDefaultRecipientRoles()).isEqualTo("APPLICANT");
        assertThat(catalog.getDescription()).isEqualTo("결제 요청 알림 (PENDING_PAYMENT 전이)");
        assertThat(catalog.getRequiredTokensJson()).contains("{{paynowUen}}");
    }

    @Test
    @DisplayName("updateMetadata - 변수 풀과 카테고리·강제 토큰 일괄 교체")
    void updateMetadata_replacesAllMetaFields() {
        NotificationCatalog catalog = buildCatalog();

        catalog.updateMetadata(
                "[\"applicantName\",\"amount\",\"publicCode\",\"paynowUen\",\"paynowReference\",\"deadline\"]",
                NotificationCategory.PAYMENT,
                NotificationSeverity.CRITICAL,
                "APPLICANT,LEW",
                "결제 요청 — 마감일 변수 추가",
                "[\"{{paynowUen}}\",\"{{paynowReference}}\",\"{{deadline}}\"]"
        );

        assertThat(catalog.getAllowedVariablesJson()).contains("deadline");
        assertThat(catalog.getDefaultRecipientRoles()).isEqualTo("APPLICANT,LEW");
        assertThat(catalog.getDescription()).contains("마감일 변수 추가");
        assertThat(catalog.getRequiredTokensJson()).contains("{{deadline}}");
    }
}
