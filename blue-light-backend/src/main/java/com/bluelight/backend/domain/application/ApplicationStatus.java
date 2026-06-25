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
     * 라이선스 발급 완료 — 신청 워크플로우의 종결 상태.
     *
     * <p>발급된 라이선스의 만료는 신청 상태가 아니라 {@link LicenseStatus} 로 분리 추적한다.
     * (이전의 EXPIRED 신청 상태는 제거 — 라이선스 만료는 status=COMPLETED 인 채로
     * {@code licenseStatus=EXPIRED} 로 표현.)</p>
     */
    COMPLETED
}
