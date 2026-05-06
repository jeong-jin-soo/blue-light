package com.bluelight.backend.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment offline 기록 + isOffline() 단위 테스트
 * (★ Concierge 강화 + 별도 수금 PR-1, D2=B).
 */
@DisplayName("Payment offline 기록 - PR-1 (D2=B)")
class PaymentOfflineRecordTest {

    // ============================================================
    // isOffline() / getPaymentMethodEnum()
    // ============================================================

    @Test
    @DisplayName("isOffline() — PAYNOW_ONLINE 은 false, 그 외 4종은 true")
    void isOffline_classification() {
        assertThat(PaymentMethod.PAYNOW_ONLINE.isOffline()).isFalse();
        assertThat(PaymentMethod.BANK_TRANSFER.isOffline()).isTrue();
        assertThat(PaymentMethod.PAYNOW_OFFLINE.isOffline()).isTrue();
        assertThat(PaymentMethod.CASH.isOffline()).isTrue();
        assertThat(PaymentMethod.OTHER.isOffline()).isTrue();
    }

    @Test
    @DisplayName("getPaymentMethodEnum() — 알 수 없는 String 은 OTHER 로 매핑 (NPE 방지)")
    void getPaymentMethodEnum_unknownStringFallsBackToOther() {
        Payment p = Payment.builder()
            .amount(new BigDecimal("100.00"))
            .paymentMethod("NOT_AN_ENUM_VALUE")
            .referenceType(PaymentReferenceType.CONCIERGE_REQUEST)
            .referenceSeq(1L)
            .build();

        assertThat(p.getPaymentMethodEnum()).isEqualTo(PaymentMethod.OTHER);
        assertThat(p.isOffline()).isTrue();
    }

    @Test
    @DisplayName("getPaymentMethodEnum() — 정상 enum 키 그대로 매핑")
    void getPaymentMethodEnum_validKey() {
        Payment p = Payment.builder()
            .amount(new BigDecimal("100.00"))
            .paymentMethod(PaymentMethod.BANK_TRANSFER.name())
            .referenceType(PaymentReferenceType.APPLICATION)
            .referenceSeq(1L)
            .build();

        assertThat(p.getPaymentMethodEnum()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(p.isOffline()).isTrue();
    }

    @Test
    @DisplayName("Builder 기본값 — paymentMethod 미지정 시 PAYNOW_ONLINE")
    void builder_defaultMethod_isPaynowOnline() {
        Payment p = Payment.builder()
            .amount(new BigDecimal("100.00"))
            .referenceType(PaymentReferenceType.APPLICATION)
            .referenceSeq(1L)
            .build();

        assertThat(p.getPaymentMethod()).isEqualTo(PaymentMethod.PAYNOW_ONLINE.name());
        assertThat(p.isOffline()).isFalse();
    }

    // ============================================================
    // createOfflineRecord — 시그니처 검증 (PR-1 시그니처만, 실제 사용은 PR-2)
    // ============================================================

    @Test
    @DisplayName("createOfflineRecord — 정상 인자로 미저장 Payment 생성")
    void createOfflineRecord_buildsUnsavedPayment() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 1, 10, 0);

        Payment p = Payment.createOfflineRecord(
            PaymentReferenceType.CONCIERGE_REQUEST,
            42L,
            new BigDecimal("500.00"),
            PaymentMethod.BANK_TRANSFER,
            7L,        // recordedByUserSeq
            now,
            99L        // applicantUserSeq (PR-1 에서는 검증만, 엔티티에 저장 X)
        );

        assertThat(p.getReferenceType()).isEqualTo(PaymentReferenceType.CONCIERGE_REQUEST);
        assertThat(p.getReferenceSeq()).isEqualTo(42L);
        assertThat(p.getAmount()).isEqualByComparingTo("500.00");
        assertThat(p.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER.name());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(p.getRecordedByUserSeq()).isEqualTo(7L);
        assertThat(p.getRecordedAt()).isEqualTo(now);
        assertThat(p.isOffline()).isTrue();
    }

    @Test
    @DisplayName("createOfflineRecord — recordedAt null 이면 now() 로 대체")
    void createOfflineRecord_nullRecordedAt_usesNow() {
        Payment p = Payment.createOfflineRecord(
            PaymentReferenceType.CONCIERGE_REQUEST, 42L,
            new BigDecimal("100.00"), PaymentMethod.CASH,
            7L, null, null);

        assertThat(p.getRecordedAt()).isNotNull();
    }

    @Test
    @DisplayName("createOfflineRecord — onlinePAYNOW_ONLINE 은 거부 (offline 만 허용)")
    void createOfflineRecord_onlineMethodRejected() {
        assertThatThrownBy(() -> Payment.createOfflineRecord(
            PaymentReferenceType.CONCIERGE_REQUEST, 42L,
            new BigDecimal("100.00"),
            PaymentMethod.PAYNOW_ONLINE,  // 온라인 — 거부
            7L, LocalDateTime.now(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("offline");
    }

    @Test
    @DisplayName("createOfflineRecord — null referenceType 거부")
    void createOfflineRecord_nullReferenceType_rejected() {
        assertThatThrownBy(() -> Payment.createOfflineRecord(
            null, 42L, new BigDecimal("100.00"),
            PaymentMethod.CASH, 7L, LocalDateTime.now(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("referenceType");
    }

    @Test
    @DisplayName("createOfflineRecord — 비양수 amount 거부")
    void createOfflineRecord_nonPositiveAmount_rejected() {
        assertThatThrownBy(() -> Payment.createOfflineRecord(
            PaymentReferenceType.CONCIERGE_REQUEST, 42L,
            BigDecimal.ZERO,
            PaymentMethod.CASH, 7L, LocalDateTime.now(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("createOfflineRecord — null recordedByUserSeq 거부")
    void createOfflineRecord_nullRecordedBy_rejected() {
        assertThatThrownBy(() -> Payment.createOfflineRecord(
            PaymentReferenceType.CONCIERGE_REQUEST, 42L,
            new BigDecimal("100.00"),
            PaymentMethod.CASH, null, LocalDateTime.now(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recordedByUserSeq");
    }
}
