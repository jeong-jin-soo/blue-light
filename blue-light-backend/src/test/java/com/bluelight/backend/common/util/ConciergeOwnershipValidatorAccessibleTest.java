package com.bluelight.backend.common.util;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.user.ApprovalStatus;
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
 * ★ Concierge 강화 + 별도 수금 PR-3 — assertAccessible (LEW 접근 허용 통합 가드) 단위 테스트.
 *
 * <p>스펙: §6, §10 AC-D1/D2/D4.</p>
 */
@DisplayName("ConciergeOwnershipValidator.assertAccessible - PR-3")
class ConciergeOwnershipValidatorAccessibleTest {

    private User buildUser(long seq, UserRole role) {
        User u = User.builder()
            .email(role.name().toLowerCase() + seq + "@y.com").password("h")
            .firstName(role.name()).lastName("X")
            .role(role).status(UserStatus.ACTIVE)
            .approvedStatus(role == UserRole.LEW ? ApprovalStatus.APPROVED : null)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private ConciergeRequest buildRequest(User applicant, User assignedManager, Long assignedLewSeq) {
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
        if (assignedLewSeq != null) {
            cr.assignLew(assignedLewSeq, now);
        }
        return cr;
    }

    @Test
    @DisplayName("ADMIN — assigned LEW 와 무관하게 접근 허용")
    void admin_allowed() {
        User admin = buildUser(1L, UserRole.ADMIN);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT),
            buildUser(3L, UserRole.CONCIERGE_MANAGER), 99L);
        assertThatCode(() -> ConciergeOwnershipValidator.assertAccessible(cr, admin))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("배정 LEW — 본인 assignedLewSeq 면 통과")
    void assignedLew_allowed() {
        User lew = buildUser(50L, UserRole.LEW);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT),
            buildUser(3L, UserRole.CONCIERGE_MANAGER), 50L);
        assertThatCode(() -> ConciergeOwnershipValidator.assertAccessible(cr, lew))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("타 LEW — 본인이 배정되지 않은 요청 → 403 CONCIERGE_LEW_NOT_ASSIGNED")
    void otherLew_rejected() {
        User lew = buildUser(50L, UserRole.LEW);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT),
            buildUser(3L, UserRole.CONCIERGE_MANAGER), 99L); // 다른 LEW
        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertAccessible(cr, lew))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(be.getCode()).isEqualTo("CONCIERGE_LEW_NOT_ASSIGNED");
            });
    }

    @Test
    @DisplayName("LEW - 미배정 (assignedLewSeq=null) → 403")
    void unassignedLew_rejected() {
        User lew = buildUser(50L, UserRole.LEW);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT),
            buildUser(3L, UserRole.CONCIERGE_MANAGER), null);
        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertAccessible(cr, lew))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("CONCIERGE_LEW_NOT_ASSIGNED"));
    }

    @Test
    @DisplayName("CONCIERGE_MANAGER 본인 배정 — 통과 (LEW 미배정 무관)")
    void assignedManager_allowed() {
        User manager = buildUser(10L, UserRole.CONCIERGE_MANAGER);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), manager, null);
        assertThatCode(() -> ConciergeOwnershipValidator.assertAccessible(cr, manager))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("타 매니저 — 403 CONCIERGE_NOT_ASSIGNED")
    void otherManager_rejected() {
        User actor = buildUser(10L, UserRole.CONCIERGE_MANAGER);
        User other = buildUser(20L, UserRole.CONCIERGE_MANAGER);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), other, null);
        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertAccessible(cr, actor))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("CONCIERGE_NOT_ASSIGNED"));
    }

    @Test
    @DisplayName("APPLICANT — 403 FORBIDDEN")
    void applicant_rejected() {
        User applicant = buildUser(10L, UserRole.APPLICANT);
        ConciergeRequest cr = buildRequest(applicant, buildUser(3L, UserRole.CONCIERGE_MANAGER), 99L);
        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertAccessible(cr, applicant))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("다중 역할 (CONCIERGE_MANAGER + LEW): 매니저로 배정되었으면 통과")
    void multiRole_managerAssigned_allowed() {
        // primary=CONCIERGE_MANAGER, secondary=LEW
        User multi = buildUser(10L, UserRole.CONCIERGE_MANAGER);
        multi.addRole(UserRole.LEW);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), multi, null);
        assertThatCode(() -> ConciergeOwnershipValidator.assertAccessible(cr, multi))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다중 역할 (CONCIERGE_MANAGER + LEW): LEW 로 배정되었으면 매니저 배정이 없어도 통과")
    void multiRole_lewAssigned_allowed() {
        User multi = buildUser(10L, UserRole.CONCIERGE_MANAGER);
        multi.addRole(UserRole.LEW);
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT),
            buildUser(20L, UserRole.CONCIERGE_MANAGER), 10L);
        assertThatCode(() -> ConciergeOwnershipValidator.assertAccessible(cr, multi))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null actor — 401 UNAUTHORIZED")
    void nullActor_rejected() {
        ConciergeRequest cr = buildRequest(buildUser(2L, UserRole.APPLICANT), null, null);
        assertThatThrownBy(() -> ConciergeOwnershipValidator.assertAccessible(cr, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("UNAUTHORIZED"));
    }
}
