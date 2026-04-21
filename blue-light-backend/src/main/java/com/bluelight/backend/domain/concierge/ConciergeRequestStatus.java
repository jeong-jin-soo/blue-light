package com.bluelight.backend.domain.concierge;

/**
 * ConciergeRequest 상태 머신 (★ Kaki Concierge v1.5, PRD §5.1)
 * <p>
 * 전이 다이어그램:
 * <pre>
 *   SUBMITTED → ASSIGNED → CONTACTING → QUOTE_SENT → APPLICATION_CREATED
 *     → AWAITING_APPLICANT_LOA_SIGN → AWAITING_LICENCE_PAYMENT
 *     → IN_PROGRESS → COMPLETED
 *   * 임의 상태(COMPLETED 제외) → CANCELLED
 *   * CONTACTING → APPLICATION_CREATED 도 여전히 허용 (기존 경로 · 견적 이메일 생략 케이스)
 * </pre>
 *
 * - SUBMITTED: 신청 접수 (24h SLA 카운트 시작)
 * - ASSIGNED: 담당 Manager 배정됨
 * - CONTACTING: Manager 첫 연락 노트 기록 (SLA 카운트 종료 = firstContactAt)
 * - QUOTE_SENT: 통화 후 수수료 견적 + 일정 + PayNow 정보를 이메일로 발송한 상태 (Phase 1.5)
 * - APPLICATION_CREATED: Manager가 대리 Application 생성
 * - AWAITING_APPLICANT_LOA_SIGN: LOA 서명 대기 (신청자 본인 서명 또는 경로 A/B)
 * - AWAITING_LICENCE_PAYMENT: 라이선스 발급비 결제 대기 (신청자 본인)
 * - IN_PROGRESS: EMA 처리 진행 중
 * - COMPLETED: 면허 발급 완료 (terminal)
 * - CANCELLED: 취소/환불 (terminal)
 */
public enum ConciergeRequestStatus {
    SUBMITTED,
    ASSIGNED,
    CONTACTING,
    QUOTE_SENT,
    APPLICATION_CREATED,
    AWAITING_APPLICANT_LOA_SIGN,
    AWAITING_LICENCE_PAYMENT,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    /**
     * 다음 상태로 전이 가능한지 확인 (PRD §5.2 전이표 기반 가드).
     * <p>
     * 같은 상태로의 전이는 멱등 허용.
     * COMPLETED/CANCELLED는 terminal 상태로 이후 전이 불가.
     */
    public boolean canTransitionTo(ConciergeRequestStatus next) {
        if (this == next) {
            return true; // 멱등 허용
        }
        return switch (this) {
            case SUBMITTED -> next == ASSIGNED || next == CANCELLED;
            case ASSIGNED -> next == CONTACTING || next == CANCELLED;
            case CONTACTING -> next == QUOTE_SENT || next == APPLICATION_CREATED || next == CANCELLED;
            case QUOTE_SENT -> next == APPLICATION_CREATED || next == CANCELLED;
            case APPLICATION_CREATED -> next == AWAITING_APPLICANT_LOA_SIGN || next == CANCELLED;
            case AWAITING_APPLICANT_LOA_SIGN -> next == AWAITING_LICENCE_PAYMENT || next == CANCELLED;
            case AWAITING_LICENCE_PAYMENT -> next == IN_PROGRESS || next == CANCELLED;
            case IN_PROGRESS -> next == COMPLETED || next == CANCELLED;
            case COMPLETED, CANCELLED -> false; // terminal
        };
    }

    /**
     * Terminal 상태 여부 (COMPLETED 또는 CANCELLED)
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
