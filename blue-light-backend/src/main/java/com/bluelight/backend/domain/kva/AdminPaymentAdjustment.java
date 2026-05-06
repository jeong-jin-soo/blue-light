package com.bluelight.backend.domain.kva;

/**
 * 결제 후 kVA 변경에 따른 수기 정산 처리 상태.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §5.1, §10 D2.</p>
 *
 * <ul>
 *   <li>{@link #PENDING} — ADMIN 이 변경은 적용했으나 외부 정산은 아직.</li>
 *   <li>{@link #PAID_DIFFERENCE} — 신청자가 차액을 추가 지불 완료(외부 채널).</li>
 *   <li>{@link #REFUNDED} — 차액을 환불 완료(외부 채널).</li>
 *   <li>{@link #WAIVED} — 정산 면제(운영 결정).</li>
 * </ul>
 */
public enum AdminPaymentAdjustment {
    PENDING,
    PAID_DIFFERENCE,
    REFUNDED,
    WAIVED
}
