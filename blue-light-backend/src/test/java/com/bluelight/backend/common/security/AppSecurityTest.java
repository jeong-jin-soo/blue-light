package com.bluelight.backend.common.security;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AppSecurity 단위 테스트 (LEW Review Form P1.B, 스펙 §7 Access Control, AC §9-3).
 */
@DisplayName("AppSecurity - P1.B")
class AppSecurityTest {

    private ApplicationRepository applicationRepository;
    private AppSecurity appSecurity;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        appSecurity = new AppSecurity(applicationRepository);
    }

    private User userWithSeq(long seq) {
        User u = User.builder()
                .email("u" + seq + "@b.com").password("h").firstName("F").lastName("L")
                .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private Application appAssignedTo(User lew) {
        Application app = Application.builder()
                .user(userWithSeq(99L))
                .address("1 Test Rd")
                .postalCode("111111")
                .selectedKva(10)
                .quoteAmount(new BigDecimal("100.00"))
                .build();
        if (lew != null) {
            app.assignLew(lew);
        }
        return app;
    }

    private Authentication authPrincipal(Long userSeq) {
        return new UsernamePasswordAuthenticationToken(userSeq, null, List.of());
    }

    @Test
    @DisplayName("배정된_LEW_본인이면_true")
    void returns_true_when_principal_is_assigned_lew() {
        User lew = userWithSeq(10L);
        Application app = appAssignedTo(lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        assertThat(appSecurity.isAssignedLew(1L, authPrincipal(10L))).isTrue();
    }

    @Test
    @DisplayName("다른_LEW가_요청하면_false")
    void returns_false_when_different_lew() {
        User lew = userWithSeq(10L);
        Application app = appAssignedTo(lew);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        assertThat(appSecurity.isAssignedLew(1L, authPrincipal(20L))).isFalse();
    }

    @Test
    @DisplayName("미배정_Application이면_false")
    void returns_false_when_unassigned() {
        Application app = appAssignedTo(null);
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.of(app));

        assertThat(appSecurity.isAssignedLew(1L, authPrincipal(10L))).isFalse();
    }

    @Test
    @DisplayName("Application_미존재면_false")
    void returns_false_when_application_not_found() {
        when(applicationRepository.findById(eq(1L))).thenReturn(Optional.empty());

        assertThat(appSecurity.isAssignedLew(1L, authPrincipal(10L))).isFalse();
    }

    @Test
    @DisplayName("applicationId_null이면_false")
    void returns_false_when_id_null() {
        assertThat(appSecurity.isAssignedLew(null, authPrincipal(10L))).isFalse();
    }

    @Test
    @DisplayName("Authentication_null이면_false")
    void returns_false_when_auth_null() {
        assertThat(appSecurity.isAssignedLew(1L, null)).isFalse();
    }

    @Test
    @DisplayName("principal_타입이_Long이_아니면_false")
    void returns_false_when_principal_not_long() {
        Authentication auth = new UsernamePasswordAuthenticationToken("not-a-long", null, List.of());
        assertThat(appSecurity.isAssignedLew(1L, auth)).isFalse();
    }
}
