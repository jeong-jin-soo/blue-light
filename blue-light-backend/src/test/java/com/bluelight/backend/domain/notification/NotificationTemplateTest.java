package com.bluelight.backend.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationTemplate PR-T1 신규 필드 단위 테스트.
 *
 * <p>{@code version}(낙관락) / {@code catalogMetaKey} / {@code category} / {@code severity} /
 * {@code recipientRoles} 필드와 {@code applyPublishedSnapshot} 메서드 검증.</p>
 */
@DisplayName("NotificationTemplate PR-T1 신규 필드")
class NotificationTemplateTest {

    private NotificationTemplate buildTemplate() {
        return NotificationTemplate.builder()
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .providerTemplateName(null)
                .subject("[LicenseKaki] Payment requested · #{{publicCode}}")
                .bodyText("Hi {{applicantName}}, please pay SGD {{amount}}.")
                .variablesJson("[\"applicantName\",\"amount\",\"publicCode\"]")
                .enabled(true)
                .catalogMetaKey("A-17")
                .category(NotificationCategory.PAYMENT)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .build();
    }

    @Test
    @DisplayName("빌더 - PR-T1 신규 필드 영속")
    void builder_persistsPrT1Fields() {
        NotificationTemplate template = buildTemplate();

        assertThat(template.getCatalogMetaKey()).isEqualTo("A-17");
        assertThat(template.getCategory()).isEqualTo(NotificationCategory.PAYMENT);
        assertThat(template.getSeverity()).isEqualTo(NotificationSeverity.CRITICAL);
        assertThat(template.getRecipientRoles()).isEqualTo("APPLICANT");
        // version 은 JPA 가 영속 시 0 으로 초기화 — 빌더 시점에는 null 가능 (Long 박싱)
    }

    @Test
    @DisplayName("applyPublishedSnapshot - draft approve 시 본 row 를 새 스냅샷으로 덮어쓴다")
    void applyPublishedSnapshot_replacesContent() {
        NotificationTemplate template = buildTemplate();

        template.applyPublishedSnapshot(
                "[LicenseKaki] Payment requested (revised) · #{{publicCode}}",
                "Hi {{applicantName}}, please settle SGD {{amount}} by {{deadline}}.",
                "[\"applicantName\",\"amount\",\"publicCode\",\"deadline\"]",
                "licensekaki_payment_requested_en",
                NotificationCategory.PAYMENT,
                NotificationSeverity.CRITICAL,
                "APPLICANT,LEW"
        );

        assertThat(template.getSubject()).contains("(revised)");
        assertThat(template.getBodyText()).contains("{{deadline}}");
        assertThat(template.getVariablesJson()).contains("deadline");
        assertThat(template.getProviderTemplateName()).isEqualTo("licensekaki_payment_requested_en");
        assertThat(template.getRecipientRoles()).isEqualTo("APPLICANT,LEW");
        // 카테고리·중요도는 동일 (draft 가 같은 값으로 submit 한 경우)
        assertThat(template.getCategory()).isEqualTo(NotificationCategory.PAYMENT);
        assertThat(template.getSeverity()).isEqualTo(NotificationSeverity.CRITICAL);
    }

    @Test
    @DisplayName("enable/disable - 기존 도메인 메서드 동작 유지")
    void enableDisable_preservesExistingBehavior() {
        NotificationTemplate template = buildTemplate();
        assertThat(template.isEnabled()).isTrue();

        template.disable();
        assertThat(template.isEnabled()).isFalse();

        template.enable();
        assertThat(template.isEnabled()).isTrue();
    }
}
