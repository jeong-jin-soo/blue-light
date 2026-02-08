package com.bluelight.backend.domain.user;

/**
 * LEW 가입 승인 상태
 * - PENDING: 승인 대기
 * - APPROVED: 승인됨
 * - REJECTED: 거절됨
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
