package com.bluelight.backend.domain.sldorder;

/**
 * SLD 전용 주문 상태
 */
public enum SldOrderStatus {
    /** SLD_MANAGER 견적 대기 */
    PENDING_QUOTE,

    /** 견적 제안됨, 신청자 확인 대기 */
    QUOTE_PROPOSED,

    /** 신청자 견적 거절 (종료) */
    QUOTE_REJECTED,

    /** 결제 대기 */
    PENDING_PAYMENT,

    /** 결제 완료 */
    PAID,

    /** SLD 생성 중 (AI/수동) */
    IN_PROGRESS,

    /** SLD 업로드 완료, 신청자 확인 대기 */
    SLD_UPLOADED,

    /** 신청자 수정 요청 */
    REVISION_REQUESTED,

    /** 완료 */
    COMPLETED
}
