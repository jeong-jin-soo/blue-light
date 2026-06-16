package com.bluelight.backend.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * User 다중 역할 단위 테스트 (★ Concierge 강화 + 별도 수금 PR-1, D1=B).
 * <p>
 * 정규화 1:N 모델: primary {@link User#getRole()} 와 secondary {@link User#getRoles()} 의 합집합이
 * effective roles 이며, Spring Security/JWT authority 매핑에 사용된다.
 */
@DisplayName("User 다중 역할 - PR-1 (D1=B)")
class UserMultiRoleTest {

    private User makeUser(UserRole primary) {
        return User.builder()
            .email("user@example.com")
            .password("hash")
            .firstName("Multi")
            .lastName("Role")
            .role(primary)
            .build();
    }

    // ============================================================
    // Builder — primary role 자동 동기화
    // ============================================================

    @Test
    @DisplayName("Builder — primary role 이 자동으로 roles 집합에 포함된다")
    void builder_primaryRoleAutoIncluded() {
        User user = makeUser(UserRole.LEW);

        assertThat(user.getRoles()).containsExactly(UserRole.LEW);
        assertThat(user.hasRole(UserRole.LEW)).isTrue();
        assertThat(user.effectiveRoles()).containsExactly(UserRole.LEW);
    }

    @Test
    @DisplayName("Builder — role 미지정 시 APPLICANT 가 primary 로 들어간다")
    void builder_defaultPrimaryRole() {
        User user = User.builder()
            .email("a@b.com").password("h").firstName("A").lastName("B")
            .build();

        assertThat(user.getRole()).isEqualTo(UserRole.APPLICANT);
        assertThat(user.hasRole(UserRole.APPLICANT)).isTrue();
        assertThat(user.effectiveRoles()).containsExactly(UserRole.APPLICANT);
    }

    // ============================================================
    // hasRole / addRole / effectiveRoles
    // ============================================================

    @Test
    @DisplayName("addRole() — secondary 역할 추가, primary 와 함께 effective 에 노출")
    void addRole_secondary_appendedToEffective() {
        User user = makeUser(UserRole.CONCIERGE_MANAGER);

        user.addRole(UserRole.LEW);

        assertThat(user.getRole()).isEqualTo(UserRole.CONCIERGE_MANAGER); // primary 불변
        assertThat(user.hasRole(UserRole.LEW)).isTrue();
        assertThat(user.hasRole(UserRole.CONCIERGE_MANAGER)).isTrue();
        assertThat(user.effectiveRoles())
            .containsExactlyInAnyOrder(UserRole.CONCIERGE_MANAGER, UserRole.LEW);
    }

    @Test
    @DisplayName("addRole() — primary 와 같은 값을 추가해도 멱등 (중복 무시)")
    void addRole_duplicate_isIdempotent() {
        User user = makeUser(UserRole.ADMIN);

        user.addRole(UserRole.ADMIN);
        user.addRole(UserRole.ADMIN);

        assertThat(user.effectiveRoles()).containsExactly(UserRole.ADMIN);
    }

    @Test
    @DisplayName("addRole(null) — IllegalArgumentException")
    void addRole_null_throws() {
        User user = makeUser(UserRole.APPLICANT);
        assertThatThrownBy(() -> user.addRole(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("hasRole(null) — false (null 안전)")
    void hasRole_null_isFalse() {
        User user = makeUser(UserRole.APPLICANT);
        assertThat(user.hasRole(null)).isFalse();
    }

    @Test
    @DisplayName("effectiveRoles() — 반환된 Set 은 unmodifiable")
    void effectiveRoles_isUnmodifiable() {
        User user = makeUser(UserRole.APPLICANT);
        user.addRole(UserRole.LEW);

        Set<UserRole> roles = user.effectiveRoles();
        assertThatThrownBy(() -> roles.add(UserRole.ADMIN))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ============================================================
    // removeRole — primary 제거 거부
    // ============================================================

    @Test
    @DisplayName("removeRole() — secondary 만 제거 가능")
    void removeRole_secondary_succeeds() {
        User user = makeUser(UserRole.CONCIERGE_MANAGER);
        user.addRole(UserRole.LEW);
        assertThat(user.hasRole(UserRole.LEW)).isTrue();

        user.removeRole(UserRole.LEW);

        assertThat(user.hasRole(UserRole.LEW)).isFalse();
        assertThat(user.hasRole(UserRole.CONCIERGE_MANAGER)).isTrue(); // primary 보존
    }

    @Test
    @DisplayName("removeRole() — primary 제거 시 IllegalStateException (changeRole() 사용 유도)")
    void removeRole_primary_throws() {
        User user = makeUser(UserRole.CONCIERGE_MANAGER);

        assertThatThrownBy(() -> user.removeRole(UserRole.CONCIERGE_MANAGER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("primary");
    }

    @Test
    @DisplayName("removeRole(null) — no-op")
    void removeRole_null_noOp() {
        User user = makeUser(UserRole.APPLICANT);
        user.addRole(UserRole.LEW);

        user.removeRole(null); // 예외 없음

        assertThat(user.effectiveRoles())
            .containsExactlyInAnyOrder(UserRole.APPLICANT, UserRole.LEW);
    }

    @Test
    @DisplayName("removeRole() — 보유하지 않은 secondary 제거는 no-op")
    void removeRole_notPresent_noOp() {
        User user = makeUser(UserRole.APPLICANT);

        user.removeRole(UserRole.SLD_MANAGER);

        assertThat(user.effectiveRoles()).containsExactly(UserRole.APPLICANT);
    }

    // ============================================================
    // changeRole — primary 변경 + roles 동기화
    // ============================================================

    @Test
    @DisplayName("changeRole() — 신규 primary 가 즉시 roles 집합에도 포함된다")
    void changeRole_addsNewPrimaryToRoles() {
        User user = makeUser(UserRole.APPLICANT);
        user.addRole(UserRole.LEW);

        user.changeRole(UserRole.SLD_MANAGER);

        assertThat(user.getRole()).isEqualTo(UserRole.SLD_MANAGER);
        // 이전 primary(APPLICANT) 와 secondary(LEW) 는 그대로 유지됨 + 신규 primary 추가
        assertThat(user.effectiveRoles())
            .containsExactlyInAnyOrder(UserRole.APPLICANT, UserRole.LEW, UserRole.SLD_MANAGER);
    }

    @Test
    @DisplayName("changeRole(null) — IllegalArgumentException")
    void changeRole_null_throws() {
        User user = makeUser(UserRole.APPLICANT);
        assertThatThrownBy(() -> user.changeRole(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("changeRole(LEW) — 면허·등급 없이 LEW 승격 불가 (changeRoleToLew 강제)")
    void changeRole_toLew_throws() {
        User user = makeUser(UserRole.APPLICANT);
        assertThatThrownBy(() -> user.changeRole(UserRole.LEW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("changeRoleToLew — 면허번호·등급 등록 + PENDING 으로 승격")
    void changeRoleToLew_setsCredentialsAndPending() {
        User user = makeUser(UserRole.APPLICANT);

        user.changeRoleToLew("  LEW-2026-00042  ", LewGrade.GRADE_8);

        assertThat(user.getRole()).isEqualTo(UserRole.LEW);
        assertThat(user.getRoles()).contains(UserRole.LEW);
        assertThat(user.getApprovedStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(user.getLewLicenceNo()).isEqualTo("LEW-2026-00042"); // trim
        assertThat(user.getLewGrade()).isEqualTo(LewGrade.GRADE_8);
        assertThat(user.isApproved()).isFalse();
    }

    @Test
    @DisplayName("changeRoleToLew — 면허번호/등급 누락 시 거부")
    void changeRoleToLew_missingCredentials_throws() {
        User u1 = makeUser(UserRole.APPLICANT);
        assertThatThrownBy(() -> u1.changeRoleToLew("  ", LewGrade.GRADE_7))
            .isInstanceOf(IllegalArgumentException.class);

        User u2 = makeUser(UserRole.APPLICANT);
        assertThatThrownBy(() -> u2.changeRoleToLew("LEW-1", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("changeRole(APPLICANT) — LEW 강등 시 면허·등급·승인상태 모두 정리")
    void changeRole_demoteFromLew_clearsLewCredentials() {
        User user = makeUser(UserRole.APPLICANT);
        user.changeRoleToLew("LEW-2026-00042", LewGrade.GRADE_9);

        user.changeRole(UserRole.APPLICANT);

        assertThat(user.getRole()).isEqualTo(UserRole.APPLICANT);
        assertThat(user.getApprovedStatus()).isNull();
        assertThat(user.getLewGrade()).isNull();
        assertThat(user.getLewLicenceNo()).isNull();
    }
}
