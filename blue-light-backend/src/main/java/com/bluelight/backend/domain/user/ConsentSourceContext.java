package com.bluelight.backend.domain.user;

/**
 * 동의 발생 맥락 (★ Kaki Concierge v1.3, PRD §3.11)
 * <p>
 * 동의가 어느 화면/플로우에서 수집되었는지 기록하여 감사 추적성 확보.
 *
 * - DIRECT_SIGNUP: 일반 회원가입 플로우
 * - CONCIERGE_REQUEST: 컨시어지 신청 폼
 * - PROFILE_UPDATE: 프로필 수정 화면 (마케팅 토글 등)
 * - ADMIN_INVITE: 관리자 초대 (Phase 3)
 */
public enum ConsentSourceContext {
    DIRECT_SIGNUP,
    CONCIERGE_REQUEST,
    PROFILE_UPDATE,
    ADMIN_INVITE
}
