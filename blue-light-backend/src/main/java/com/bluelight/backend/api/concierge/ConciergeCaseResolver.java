package com.bluelight.backend.api.concierge;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;

/**
 * Concierge 신청 시 이메일별 케이스 분기 (★ Kaki Concierge v1.5, PRD §7.7).
 * <p>
 * 신청 폼의 email을 기존 User 레코드와 대조하여 처리 경로를 결정한다.
 * 순수 함수(정적) — 서비스 레이어가 결정 후 각 케이스별 후속 처리를 수행한다.
 */
public final class ConciergeCaseResolver {

    public enum Case {
        /**
         * 이메일 미존재 → 신규 User 자동 생성(PENDING_ACTIVATION).
         */
        C1_NEW_SIGNUP,
        /**
         * 기존 APPLICANT + ACTIVE → 기존 계정 연결 (토큰 발급 불필요).
         */
        C2_EXISTING_ACTIVE,
        /**
         * 기존 APPLICANT + PENDING_ACTIVATION → 기존 계정 재사용 + 토큰 재발급.
         */
        C3_EXISTING_PENDING,
        /**
         * 기존 APPLICANT + SUSPENDED/DELETED → 409 거부.
         */
        C4_EXISTING_INELIGIBLE,
        /**
         * 스태프 계정(LEW/ADMIN/SYSTEM_ADMIN/SLD_MANAGER/CONCIERGE_MANAGER) → 422 거부.
         */
        C5_STAFF_BLOCKED
    }

    public static Case resolve(User existing) {
        if (existing == null) {
            return Case.C1_NEW_SIGNUP;
        }
        if (existing.getRole() != UserRole.APPLICANT) {
            return Case.C5_STAFF_BLOCKED;
        }
        UserStatus status = existing.getStatus();
        if (status == UserStatus.ACTIVE) {
            return Case.C2_EXISTING_ACTIVE;
        }
        if (status == UserStatus.PENDING_ACTIVATION) {
            return Case.C3_EXISTING_PENDING;
        }
        // SUSPENDED / DELETED
        return Case.C4_EXISTING_INELIGIBLE;
    }

    private ConciergeCaseResolver() {
        // utility class
    }
}
