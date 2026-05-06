package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 PR#1 / B-1 — {@code approveForPayment} 의 kVA 가드.
 *
 * <p>kvaStatus=UNKNOWN 이면 400 {@code KVA_NOT_CONFIRMED} 로 거부되고,
 * {@code Application.approveForPayment()} 도메인 메서드는 호출되지 않아야 한다.
 */
class AdminApplicationServiceKvaGuardTest {

    private ApplicationRepository applicationRepository;
    private UserRepository userRepository;
    private EmailService emailService;
    private AdminApplicationService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        // ★ PR#7: ApplicationEventPublisher mock 추가
        org.springframework.context.ApplicationEventPublisher eventPublisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new AdminApplicationService(
            applicationRepository, userRepository, emailService, eventPublisher);
    }

    @Test
    void approveForPayment_UNKNOWN이면_400_KVA_NOT_CONFIRMED() {
        Application app = mock(Application.class);
        when(app.getStatus()).thenReturn(ApplicationStatus.PENDING_REVIEW);
        when(app.getKvaStatus()).thenReturn(KvaStatus.UNKNOWN);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.approveForPayment(1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("KVA_NOT_CONFIRMED");
                });

        verify(app, never()).approveForPayment();
    }

    // CONFIRMED 성공 경로는 AdminApplicationResponse.from 이 applicationType 등을 요구하므로
    // 통합 테스트(MockMvc) 스코프로 이관하고, 여기서는 B-1 가드만 검증.
}
