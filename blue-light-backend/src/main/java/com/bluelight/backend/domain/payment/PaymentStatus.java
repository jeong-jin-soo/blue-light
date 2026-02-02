package com.bluelight.backend.domain.payment;

/**
 * 결제 상태
 */
public enum PaymentStatus {
    /**
     * 결제 성공
     */
    SUCCESS,

    /**
     * 결제 실패
     */
    FAILED,

    /**
     * 환불됨
     */
    REFUNDED
}
