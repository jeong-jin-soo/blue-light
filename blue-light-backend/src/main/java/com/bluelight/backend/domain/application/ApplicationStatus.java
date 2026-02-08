package com.bluelight.backend.domain.application;

/**
 * 라이선스 신청 진행 상태
 */
public enum ApplicationStatus {
    /**
     * LEW 검토 대기
     */
    PENDING_REVIEW,

    /**
     * 보완 요청됨
     */
    REVISION_REQUESTED,

    /**
     * 결제 대기 중
     */
    PENDING_PAYMENT,

    /**
     * 결제 완료
     */
    PAID,

    /**
     * 점검 진행 중
     */
    IN_PROGRESS,

    /**
     * 라이선스 발급 완료
     */
    COMPLETED,

    /**
     * 만료됨
     */
    EXPIRED
}
