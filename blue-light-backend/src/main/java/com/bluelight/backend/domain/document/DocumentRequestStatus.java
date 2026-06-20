package com.bluelight.backend.domain.document;

/**
 * DocumentRequest 상태
 *
 * 전이 규칙 (LEW 승인/반려 단계 제거 후 — 2026-06-18):
 *   REQUESTED → UPLOADED (신청자 fulfill)
 *   REQUESTED → CANCELLED (LEW 취소)
 *   UPLOADED  → UPLOADED  (신청자 재업로드 — 같은 상태 덮어쓰기 허용)
 *   CANCELLED → (종결 상태, 전이 불가)
 *
 * UPLOADED = "수취 완료" 의 종결 의미(별도 승인 단계 없음). 과거 APPROVED/REJECTED 행은
 * 마이그레이션으로 UPLOADED 로 전환됨(DatabaseMigrationRunner).
 * 그 외 모든 조합은 불법 전이로 409 INVALID_STATE_TRANSITION 처리.
 */
public enum DocumentRequestStatus {
    REQUESTED,
    UPLOADED,
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
            case UPLOADED -> next == UPLOADED;
            case CANCELLED -> false;
        };
    }
}
