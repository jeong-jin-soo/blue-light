package com.bluelight.backend.domain.user;

/**
 * 사용자 역할 구분
 * - APPLICANT: 건물주 (신청자)
 * - LEW: Licensed Electrical Worker (면허 전기 기술자)
 * - ADMIN: 관리자
 */
public enum UserRole {
    APPLICANT,
    LEW,
    ADMIN
}
