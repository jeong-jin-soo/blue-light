package com.bluelight.backend.domain.payment;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment referenceType/referenceSeq 단위 테스트 (★ Kaki Concierge v1.5 Phase 1 PR#7).
 */
@DisplayName("Payment reference 다형 참조 - PR#7")
class PaymentReferenceTest {

    private Application makeApp(long seq) {
        User owner = User.builder()
            .email("a@b.com").password("h").firstName("A").lastName("B")
            .build();
        Application app = Application.builder()
            .user(owner)
            .address("1 Test Rd")
            .postalCode("111111")
            .selectedKva(45)
            .quoteAmount(new BigDecimal("650.00"))
            .applicationType(ApplicationType.NEW)
            .build();
        ReflectionTestUtils.setField(app, "applicationSeq", seq);
        return app;
    }

    // ============================================================
    // Builder 자동 추론
    // ============================================================

    @Test
    @DisplayName("Builder with application → referenceType=APPLICATION, referenceSeq=app.seq 자동 추론")
    void builder_withApplication_autoDerivesReference() {
        Application app = makeApp(42L);

        Payment payment = Payment.builder()
            .application(app)
            .amount(new BigDecimal("650.00"))
            .build();

        assertThat(payment.getReferenceType()).isEqualTo(PaymentReferenceType.APPLICATION);
        assertThat(payment.getReferenceSeq()).isEqualTo(42L);
        assertThat(payment.getApplication()).isSameAs(app);
    }

    @Test
    @DisplayName("Builder with explicit referenceType + referenceSeq → 명시값 유지")
    void builder_withExplicitReference_keepsValues() {
        Payment payment = Payment.builder()
            .amount(new BigDecimal("500.00"))
            .referenceType(PaymentReferenceType.CONCIERGE_REQUEST)
            .referenceSeq(100L)
            .build();

        assertThat(payment.getReferenceType()).isEqualTo(PaymentReferenceType.CONCIERGE_REQUEST);
        assertThat(payment.getReferenceSeq()).isEqualTo(100L);
        assertThat(payment.getApplication()).isNull();
    }

    @Test
    @DisplayName("Builder with both application and explicit reference → 명시값 우선")
    void builder_withBoth_explicitWins() {
        Application app = makeApp(42L);

        Payment payment = Payment.builder()
            .application(app)
            .amount(new BigDecimal("500.00"))
            .referenceType(PaymentReferenceType.CONCIERGE_REQUEST)
            .referenceSeq(100L)
            .build();

        // 명시 참조가 우선, application도 보존
        assertThat(payment.getReferenceType()).isEqualTo(PaymentReferenceType.CONCIERGE_REQUEST);
        assertThat(payment.getReferenceSeq()).isEqualTo(100L);
        assertThat(payment.getApplication()).isSameAs(app);
    }

    @Test
    @DisplayName("Builder without application and without reference → IllegalArgumentException")
    void builder_withoutAny_throws() {
        assertThatThrownBy(() -> Payment.builder()
            .amount(new BigDecimal("100.00"))
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("application");
    }

    @Test
    @DisplayName("Builder with application but app.seq=null → IllegalArgumentException (persist 전 엔티티)")
    void builder_withUnsavedApplication_throws() {
        User owner = User.builder()
            .email("a@b.com").password("h").firstName("A").lastName("B")
            .build();
        Application unsaved = Application.builder()
            .user(owner)
            .address("1 Test Rd")
            .postalCode("111111")
            .selectedKva(45)
            .quoteAmount(new BigDecimal("650.00"))
            .build();
        // applicationSeq=null 상태

        assertThatThrownBy(() -> Payment.builder()
            .application(unsaved)
            .amount(new BigDecimal("100.00"))
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ============================================================
    // isLinkedTo()
    // ============================================================

    @Test
    @DisplayName("isLinkedTo(APPLICATION, seq) - 정확히 일치하면 true")
    void isLinkedTo_match() {
        Payment payment = Payment.builder()
            .application(makeApp(42L))
            .amount(new BigDecimal("650.00"))
            .build();

        assertThat(payment.isLinkedTo(PaymentReferenceType.APPLICATION, 42L)).isTrue();
    }

    @Test
    @DisplayName("isLinkedTo - seq 불일치 false")
    void isLinkedTo_wrongSeq() {
        Payment payment = Payment.builder()
            .application(makeApp(42L))
            .amount(new BigDecimal("650.00"))
            .build();

        assertThat(payment.isLinkedTo(PaymentReferenceType.APPLICATION, 999L)).isFalse();
    }

    @Test
    @DisplayName("isLinkedTo - type 불일치 false (APPLICATION vs CONCIERGE_REQUEST)")
    void isLinkedTo_wrongType() {
        Payment payment = Payment.builder()
            .application(makeApp(42L))
            .amount(new BigDecimal("650.00"))
            .build();

        assertThat(payment.isLinkedTo(PaymentReferenceType.CONCIERGE_REQUEST, 42L)).isFalse();
    }

    @Test
    @DisplayName("isLinkedTo - CONCIERGE_REQUEST 결제도 정확히 매칭")
    void isLinkedTo_conciergePayment() {
        Payment payment = Payment.builder()
            .amount(new BigDecimal("500.00"))
            .referenceType(PaymentReferenceType.CONCIERGE_REQUEST)
            .referenceSeq(100L)
            .build();

        assertThat(payment.isLinkedTo(PaymentReferenceType.CONCIERGE_REQUEST, 100L)).isTrue();
        assertThat(payment.isLinkedTo(PaymentReferenceType.APPLICATION, 100L)).isFalse();
    }
}
