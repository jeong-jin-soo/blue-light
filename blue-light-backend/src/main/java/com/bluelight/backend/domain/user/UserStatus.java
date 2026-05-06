package com.bluelight.backend.domain.user;

/**
 * 계정 활성화 상태 (★ Kaki Concierge v1.4)
 * <p>
 * v1.3까지의 {@code signupCompleted} boolean을 대체하는 명시적 enum.
 * 컨시어지 신청으로 자동 생성된 계정은 {@link #PENDING_ACTIVATION}로 시작하며,
 * 최초 로그인 성공 시점에 {@link #ACTIVE}로 전환된다.
 *
 * - PENDING_ACTIVATION: 컨시어지 신청 후 첫 로그인 전 — 로그인 차단
 * - ACTIVE: 정상 활성화 — 모든 기능 접근 가능 (기존 회원가입 플로우는 ACTIVE로 시작)
 * - SUSPENDED: 관리자 정지 — 로그인 차단, 데이터는 보존
 * - DELETED: soft-delete 완료 상태 (deleted_at과 병행 추적)
 */
public enum UserStatus {
    PENDING_ACTIVATION,
    ACTIVE,
    SUSPENDED,
    DELETED
}
