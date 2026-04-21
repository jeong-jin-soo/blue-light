package com.bluelight.backend.domain.lewserviceorder;

/**
 * Request for LEW Service 주문 상태
 */
public enum LewServiceOrderStatus {
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

    /** 작업 진행 중 */
    IN_PROGRESS,

    /** Request for LEW Service 업로드 완료, 신청자 확인 대기 */
    SLD_UPLOADED,

    /** 신청자 수정 요청 */
    REVISION_REQUESTED,

    /** 완료 */
    COMPLETED
}
