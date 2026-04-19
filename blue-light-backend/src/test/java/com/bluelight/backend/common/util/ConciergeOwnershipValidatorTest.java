package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConciergeOwnershipValidator 단위 테스트 (★ Kaki Concierge v1.5 Phase 1 PR#4 Stage A).
 */
@DisplayName("ConciergeOwnershipValidator - PR#4 Stage A")
class ConciergeOwnershipValidatorTest {

    private User buildUser(long seq, UserRole role) {
        User u = User.builder()
            .email(role.name().toLowerCase() + "@y.com").password("h")
            .firstName(role.name()).lastName("X")
            .role(role).status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private ConciergeRequest buildRequest(User applicant, User assignedManager) {
        LocalDateTime now = LocalDateTime.now();
        ConciergeRequest cr = ConciergeRequest.builder()
            .publicCode("C-2026-0001")
            .submitterName("S").submitterEmail("s@y.com").submitterPhone("+6512345678")
            .applicantUser(applicant)
            .pdpaConsentAt(now).termsConsentAt(now)
            .signupConsentAt(now).delegationConsentAt(now)
            .build();
        if (assignedManager != null) {
            cr.assignManager(assignedManager);
        }
        return cr;
    }

    // ============================================================
    // assertManagerCanAccess
    // ============================================================

    @Test
    @DisplayName("ADMIN - 배정 여부와 관계없이 접근 허용")
    void assertManagerCanAccess_admin_allowed() {
        User admin = buildUser(1L, UserRole.ADMIN);
        User applicant = buildUser(2L, UserRole.APPLICANT);
        User otherManager = buildUser(3L, UserRole.CONCIERGE_MANAGER);
        ConciergeRequest cr = buildRequest(applicant, otherManager);

        assertThatCode(() -> ConciergeOwnershipValidator.assertManagerCanAccess(cr, admin))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN - 접근 허용")
    void assertManagerCanAccess_systemAdmin_allowed() {
        User sysadmin = buildUser(1L, UserRole.SYSTEM_ADMIN);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), null);

        assertThatCode(() -> ConciergeOwnershipValidator.assertManagerCanAccess(cr, sysadmin))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CONCIERGE_MANAGER - 본인 배정 건은 허용")
    void assertManagerCanAccess_assignedManager_allowed() {
        User manager = buildUser(10L, UserRole.CONCIERGE_MANAGER);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), manager);

        assertThatCode(() -> ConciergeOwnershipValidator.assertManagerCanAccess(cr, manager))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CONCIERGE_MANAGER - 타 매니저 배정 건은 CONCIERGE_NOT_ASSIGNED")
    void assertManagerCanAccess_otherManager_rejected() {
        User actor = buildUser(10L, UserRole.CONCIERGE_MANAGER);
        User other = buildUser(20L, UserRole.CONCIERGE_MANAGER);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), other);

        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(be.getCode()).isEqualTo("CONCIERGE_NOT_ASSIGNED");
            });
    }

    @Test
    @DisplayName("CONCIERGE_MANAGER - 미배정 건은 CONCIERGE_NOT_ASSIGNED")
    void assertManagerCanAccess_unassigned_rejected() {
        User actor = buildUser(10L, UserRole.CONCIERGE_MANAGER);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), null);

        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("CONCIERGE_NOT_ASSIGNED"));
    }

    @Test
    @DisplayName("APPLICANT - 403 FORBIDDEN")
    void assertManagerCanAccess_applicant_rejected() {
        User actor = buildUser(10L, UserRole.APPLICANT);
        ConciergeRequest cr = buildRequest(actor, buildUser(3L, UserRole.CONCIERGE_MANAGER));

        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(be.getCode()).isEqualTo("FORBIDDEN");
            });
    }

    @Test
    @DisplayName("LEW - 403 FORBIDDEN")
    void assertManagerCanAccess_lew_rejected() {
        User actor = buildUser(10L, UserRole.LEW);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), null);

        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("actor=null → 401 UNAUTHORIZED")
    void assertManagerCanAccess_nullActor_unauthenticated() {
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), null);

        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertManagerCanAccess(cr, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(be.getCode()).isEqualTo("UNAUTHORIZED");
            });
    }

    // ============================================================
    // resolveListFilterManagerSeq
    // ============================================================

    @Test
    @DisplayName("목록 필터 - ADMIN → null (전체 조회)")
    void resolveListFilter_admin_null() {
        assertThat(ConciergeOwnershipValidator.resolveListFilterManagerSeq(
            buildUser(1L, UserRole.ADMIN))).isNull();
    }

    @Test
    @DisplayName("목록 필터 - SYSTEM_ADMIN → null")
    void resolveListFilter_systemAdmin_null() {
        assertThat(ConciergeOwnershipValidator.resolveListFilterManagerSeq(
            buildUser(1L, UserRole.SYSTEM_ADMIN))).isNull();
    }

    @Test
    @DisplayName("목록 필터 - CONCIERGE_MANAGER → 본인 userSeq")
    void resolveListFilter_manager_ownSeq() {
        User actor = buildUser(42L, UserRole.CONCIERGE_MANAGER);
        assertThat(ConciergeOwnershipValidator.resolveListFilterManagerSeq(actor))
            .isEqualTo(42L);
    }

    @Test
    @DisplayName("목록 필터 - APPLICANT → 403")
    void resolveListFilter_applicant_rejected() {
        User actor = buildUser(10L, UserRole.APPLICANT);
        assertThatThrownBy(() -> ConciergeOwnershipValidator.resolveListFilterManagerSeq(actor))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("목록 필터 - null → 401")
    void resolveListFilter_nullActor_unauthenticated() {
        assertThatThrownBy(() -> ConciergeOwnershipValidator.resolveListFilterManagerSeq(null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("UNAUTHORIZED"));
    }
}
