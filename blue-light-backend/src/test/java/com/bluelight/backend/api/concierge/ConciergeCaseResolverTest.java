package com.bluelight.backend.api.concierge;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConciergeCaseResolver 단위 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 */
@DisplayName("ConciergeCaseResolver - PR#2 Stage B")
class ConciergeCaseResolverTest {

    private User buildUser(UserRole role, UserStatus status) {
        return User.builder()
            .email("x@y.com").password("h").firstName("a").lastName("b")
            .role(role)
            .status(status)
            .build();
    }

    @Test
    @DisplayName("null 입력 → C1_NEW_SIGNUP")
    void resolve_null_C1() {
        assertThat(ConciergeCaseResolver.resolve(null))
            .isEqualTo(ConciergeCaseResolver.Case.C1_NEW_SIGNUP);
    }

    @Test
    @DisplayName("APPLICANT + ACTIVE → C2_EXISTING_ACTIVE")
    void resolve_applicantActive_C2() {
        User u = buildUser(UserRole.APPLICANT, UserStatus.ACTIVE);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C2_EXISTING_ACTIVE);
    }

    @Test
    @DisplayName("APPLICANT + PENDING_ACTIVATION → C3_EXISTING_PENDING")
    void resolve_applicantPending_C3() {
        User u = buildUser(UserRole.APPLICANT, UserStatus.PENDING_ACTIVATION);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C3_EXISTING_PENDING);
    }

    @Test
    @DisplayName("APPLICANT + SUSPENDED → C4_EXISTING_INELIGIBLE")
    void resolve_applicantSuspended_C4() {
        User u = buildUser(UserRole.APPLICANT, UserStatus.SUSPENDED);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C4_EXISTING_INELIGIBLE);
    }

    @Test
    @DisplayName("APPLICANT + DELETED → C4_EXISTING_INELIGIBLE")
    void resolve_applicantDeleted_C4() {
        User u = buildUser(UserRole.APPLICANT, UserStatus.DELETED);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C4_EXISTING_INELIGIBLE);
    }

    @Test
    @DisplayName("LEW → C5_STAFF_BLOCKED (status ACTIVE여도)")
    void resolve_lew_C5() {
        User u = buildUser(UserRole.LEW, UserStatus.ACTIVE);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C5_STAFF_BLOCKED);
    }

    @Test
    @DisplayName("ADMIN → C5_STAFF_BLOCKED")
    void resolve_admin_C5() {
        User u = buildUser(UserRole.ADMIN, UserStatus.ACTIVE);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C5_STAFF_BLOCKED);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN → C5_STAFF_BLOCKED")
    void resolve_systemAdmin_C5() {
        User u = buildUser(UserRole.SYSTEM_ADMIN, UserStatus.ACTIVE);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C5_STAFF_BLOCKED);
    }

    @Test
    @DisplayName("SLD_MANAGER → C5_STAFF_BLOCKED")
    void resolve_sldManager_C5() {
        User u = buildUser(UserRole.SLD_MANAGER, UserStatus.ACTIVE);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C5_STAFF_BLOCKED);
    }

    @Test
    @DisplayName("CONCIERGE_MANAGER → C5_STAFF_BLOCKED")
    void resolve_conciergeManager_C5() {
        User u = buildUser(UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        assertThat(ConciergeCaseResolver.resolve(u))
            .isEqualTo(ConciergeCaseResolver.Case.C5_STAFF_BLOCKED);
    }
}
