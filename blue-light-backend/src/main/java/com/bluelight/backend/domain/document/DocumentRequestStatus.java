package com.bluelight.backend.domain.document;

/**
 * DocumentRequest 상태
 *
 * Phase 2 범위:
 *   - UPLOADED: 신청자가 자발적 업로드한 직후 상태
 *
 * Phase 3 확장 예정:
 *   - REQUESTED: LEW가 요청을 생성한 직후 (파일 미첨부)
 *   - APPROVED:  LEW/ADMIN 승인 완료
 *   - REJECTED:  LEW/ADMIN 반려 (rejectionReason 필수)
 *   - CANCELLED: 신청자 또는 LEW가 요청을 취소
 */
public enum DocumentRequestStatus {
    REQUESTED,
    UPLOADED,
    APPROVED,
    REJECTED,
    CANCELLED
}
