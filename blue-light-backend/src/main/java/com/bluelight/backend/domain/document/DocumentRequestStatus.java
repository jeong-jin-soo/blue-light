package com.bluelight.backend.domain.document;

/**
 * DocumentRequest 상태
 *
 * 전이 규칙 (Phase 3):
 *   REQUESTED → UPLOADED (신청자 fulfill)
 *   REQUESTED → CANCELLED (LEW 취소)
 *   UPLOADED  → APPROVED  (LEW 승인)
 *   UPLOADED  → REJECTED  (LEW 반려)
 *   UPLOADED  → UPLOADED  (신청자 재업로드 — 같은 상태 덮어쓰기 허용)
 *   REJECTED  → UPLOADED  (신청자 재업로드)
 *   APPROVED / CANCELLED  → (종결 상태, 전이 불가)
 *
 * 그 외 모든 조합은 불법 전이로 409 INVALID_STATE_TRANSITION 처리.
 */
public enum DocumentRequestStatus {
    REQUESTED,
    UPLOADED,
    APPROVED,
    REJECTED,
    CANCELLED;

    /**
     * 현재 상태에서 next로 전이가 가능한지 검사한다.
     */
    public boolean canTransitionTo(DocumentRequestStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case REQUESTED -> next == UPLOADED || next == CANCELLED;
            case UPLOADED -> next == APPROVED || next == REJECTED || next == UPLOADED;
            case REJECTED -> next == UPLOADED;
            case APPROVED, CANCELLED -> false;
        };
    }
}
