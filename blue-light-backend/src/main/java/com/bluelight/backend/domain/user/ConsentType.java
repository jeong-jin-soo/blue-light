package com.bluelight.backend.domain.user;

/**
 * 동의 항목 유형 (★ Kaki Concierge v1.3, PRD §3.11)
 *
 * - PDPA: 개인정보 수집·이용 동의 (필수)
 * - TERMS: 서비스 이용약관 동의 (필수)
 * - SIGNUP: 회원가입 자동 생성 동의 (필수, Concierge 경로)
 * - DELEGATION: 대행 위임 동의 (필수, Concierge 고유)
 * - MARKETING: 마케팅 수신 동의 (선택)
 */
public enum ConsentType {
    PDPA,
    TERMS,
    SIGNUP,
    DELEGATION,
    MARKETING
}
