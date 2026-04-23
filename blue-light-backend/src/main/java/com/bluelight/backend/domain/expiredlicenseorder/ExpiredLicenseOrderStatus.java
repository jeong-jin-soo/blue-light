package com.bluelight.backend.domain.expiredlicenseorder;

/**
 * Expired License 주문 상태 (LEW Service 와 동일한 생애주기).
 * <p>ON_SITE 는 별도 상태 대신 {@code status=VISIT_SCHEDULED && checkInAt != null} 로 파생.
 */
public enum ExpiredLicenseOrderStatus {
    PENDING_QUOTE,
    QUOTE_PROPOSED,
    QUOTE_REJECTED,
    PENDING_PAYMENT,
    PAID,
    VISIT_SCHEDULED,
    VISIT_COMPLETED,
    REVISIT_REQUESTED,
    COMPLETED
}
