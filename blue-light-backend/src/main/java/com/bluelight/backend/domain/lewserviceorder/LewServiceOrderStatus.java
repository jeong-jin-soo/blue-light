package com.bluelight.backend.domain.lewserviceorder;

/**
 * Request for LEW Service 주문 상태 (방문형 서비스 기준).
 *
 * <p>LEW Service 방문형 리스키닝 PR 3 — enum rename:
 * <ul>
 *   <li>IN_PROGRESS → VISIT_SCHEDULED (방문 일정 확정, 방문 전/중)</li>
 *   <li>SLD_UPLOADED → VISIT_COMPLETED (방문 종료 + 보고서 업로드, 신청자 확인 대기)</li>
 *   <li>REVISION_REQUESTED → REVISIT_REQUESTED (재방문 요청)</li>
 * </ul>
 *
 * <p>ON_SITE 는 별도 상태로 추가하지 않고 {@code status=VISIT_SCHEDULED && checkInAt != null}
 * 조합으로 파생한다. 근거: enum 폭발 방지 + 체크인 시각을 별도로 저장하므로 추가 상태가 불필요.
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

    /**
     * 방문 일정 확정.
     * <p>checkInAt 이 null 이면 "방문 전", non-null 이면 "현장 방문 중(ON_SITE)"로 해석.
     */
    VISIT_SCHEDULED,

    /** 방문 완료 + 보고서 업로드 완료. 신청자 확인 대기. */
    VISIT_COMPLETED,

    /** 신청자 재방문 요청 */
    REVISIT_REQUESTED,

    /** 완료 */
    COMPLETED
}
