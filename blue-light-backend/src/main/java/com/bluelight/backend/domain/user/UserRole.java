package com.bluelight.backend.domain.user;

/**
 * 사용자 역할 구분
 * - APPLICANT: 건물주 (신청자)
 * - LEW: Licensed Electrical Worker (면허 전기 기술자)
 * - ADMIN: 관리자 (실무 관리자)
 * - SYSTEM_ADMIN: 시스템 관리자 (개발자 — 시스템 설정, API 키, 챗봇 프롬프트 관리)
 */
public enum UserRole {
    APPLICANT,
    LEW,
    ADMIN,
    SYSTEM_ADMIN
}
