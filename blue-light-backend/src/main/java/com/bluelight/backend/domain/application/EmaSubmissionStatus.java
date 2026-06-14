package com.bluelight.backend.domain.application;

/**
 * EMA ELISE 제출 추적 서브-상태 — {@link ApplicationStatus#IN_PROGRESS} 의 서브-상태 기계.
 *
 * <p>EMA ELISE 는 공개 API 가 없는 수작업 정부 포털이므로, 담당 LEW 가 ELISE 에서 실제로 한
 * 행동을 우리 DB 에 수동으로 미러링한다. 본 enum 은 그 미러링 상태를 표현한다.
 *
 * <p>VARCHAR(30) 저장 + Java Enum 검증 (DB ENUM 미사용 컨벤션 준수, {@link PremisesType} 과 동일 스타일).
 *
 * <p>전이는 {@link Application} 도메인 메서드(markEmaSubmitted/raiseEmaQuery/resubmitEma/approveEma/
 * rejectEma/withdrawEma/revertEmaDecision)가 소유한다. 전이표 정의는
 * {@code doc/Project Analysis/ema-submission-tracking-spec.md} §3 참조.
 *
 * <pre>
 * NOT_SUBMITTED → SUBMITTED → (QUERY_RAISED ↔ RESUBMITTED) → APPROVED / REJECTED / WITHDRAWN
 *   REJECTED → RESUBMITTED (재진입, T10), APPROVED/WITHDRAWN 은 ADMIN revertEmaDecision 으로만 복원
 * </pre>
 */
public enum EmaSubmissionStatus {
    NOT_SUBMITTED,
    SUBMITTED,
    QUERY_RAISED,
    RESUBMITTED,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
