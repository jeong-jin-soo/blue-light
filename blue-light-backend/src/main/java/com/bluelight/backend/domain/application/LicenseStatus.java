package com.bluelight.backend.domain.application;

/**
 * 발급된 라이선스(artifact)의 유효성 상태.
 *
 * <p>신청 워크플로우 상태({@link ApplicationStatus})와 <b>구분</b>되는 개념이다.
 * 신청은 COMPLETED 로 종결되며, 그 신청으로 발급된 라이선스는 별도로 유효(ACTIVE)하거나
 * 만료(EXPIRED)될 수 있다. 라이선스가 발급되기 전(COMPLETED 이전)에는 {@code null} 이다.</p>
 */
public enum LicenseStatus {
    /** 발급되어 유효기간 내 — 정상 */
    ACTIVE,

    /** 유효기간(licenseExpiryDate) 경과 — 만료 (LicenseExpiryScheduler 자동 전환) */
    EXPIRED
}
