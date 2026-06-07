package com.bluelight.backend.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AuthPrincipal 정적 헬퍼")
class AuthPrincipalTest {

    private Authentication auth(Object principal, String... roles) {
        var authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Test
    @DisplayName("userSeq - Long principal 정상 추출")
    void userSeq_extractsLongPrincipal() {
        assertThat(AuthPrincipal.userSeq(auth(42L, "ROLE_LEW"))).isEqualTo(42L);
    }

    @Test
    @DisplayName("userSeq - principal 이 String 이면 IllegalStateException")
    void userSeq_failsForNonLongPrincipal() {
        Authentication a = auth("not-a-long", "ROLE_USER");
        assertThatThrownBy(() -> AuthPrincipal.userSeq(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected Long principal");
    }

    @Test
    @DisplayName("userSeq - auth null 이면 IllegalStateException")
    void userSeq_failsForNullAuth() {
        assertThatThrownBy(() -> AuthPrincipal.userSeq(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authentication is null");
    }

    @Test
    @DisplayName("role - 첫 authority 추출")
    void role_extractsFirstAuthority() {
        assertThat(AuthPrincipal.role(auth(1L, "ROLE_ADMIN"))).isEqualTo("ROLE_ADMIN");
    }

    @Test
    @DisplayName("role - authorities 비어있으면 IllegalStateException")
    void role_failsForEmptyAuthorities() {
        Authentication a = new UsernamePasswordAuthenticationToken(1L, null, List.of());
        assertThatThrownBy(() -> AuthPrincipal.role(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authorities");
    }

    @Test
    @DisplayName("role - auth null 이면 IllegalStateException")
    void role_failsForNullAuth() {
        assertThatThrownBy(() -> AuthPrincipal.role(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authentication is null");
    }
}
