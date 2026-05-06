package com.bluelight.backend.domain.user;

/**
 * 가입 경로 구분 (★ Kaki Concierge v1.3)
 * <p>
 * Application/대시보드에서 컨시어지 경유 가입 비율을 분석하기 위한 지표.
 *
 * - DIRECT_SIGNUP: 기존 회원가입 플로우(본인 직접 가입)
 * - CONCIERGE_REQUEST: 컨시어지 신청 시 자동 생성된 계정
 * - ADMIN_INVITE: 관리자 수동 초대로 생성된 계정 (Phase 3)
 */
public enum SignupSource {
    DIRECT_SIGNUP,
    CONCIERGE_REQUEST,
    ADMIN_INVITE
}
