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
    private com.bluelight.backend.domain.cof.CertificateOfFitnessRepository cofRepository;
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
        // 동선 재설계 B: completeApplication CoF 가드용 리포지토리.
        cofRepository =
            mock(com.bluelight.backend.domain.cof.CertificateOfFitnessRepository.class);
        service = new AdminApplicationService(
            applicationRepository, userRepository, cofRepository, emailService, eventPublisher);
    }

    @Test
    void approveForPayment_UNKNOWN이면_400_KVA_NOT_CONFIRMED() {
        Application app = mock(Application.class);
        when(app.getStatus()).thenReturn(ApplicationStatus.PENDING_REVIEW);
        when(app.getKvaStatus()).thenReturn(KvaStatus.UNKNOWN);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));

        // ★ 코드 부채 P0 단일화 — LEW 가드는 컨트롤러 SpEL @appSec.isAssignedLew 로 이관됨.
        // 본 단위 테스트는 KVA_NOT_CONFIRMED 비즈니스 가드만 검증.
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

    @Test
    void completeApplication_CoF미finalize면_COF_NOT_FINALIZED() {
        // 동선 재설계 B: IN_PROGRESS여도 CoF가 finalize 안 됐으면 라이선스 발급 차단.
        Application app = mock(Application.class);
        when(app.getStatus()).thenReturn(ApplicationStatus.IN_PROGRESS);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        // CoF 자체가 없는 케이스
        when(cofRepository.findByApplication_ApplicationSeq(1L)).thenReturn(Optional.empty());

        var request = new com.bluelight.backend.api.admin.dto.CompleteApplicationRequest();

        assertThatThrownBy(() -> service.completeApplication(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex ->
                        assertThat(((BusinessException) ex).getCode()).isEqualTo("COF_NOT_FINALIZED"));

        verify(app, never()).issueLicense(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completeApplication_CoF있어도_미finalize면_차단() {
        Application app = mock(Application.class);
        when(app.getStatus()).thenReturn(ApplicationStatus.IN_PROGRESS);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        var cof = mock(com.bluelight.backend.domain.cof.CertificateOfFitness.class);
        when(cof.isFinalized()).thenReturn(false);
        when(cofRepository.findByApplication_ApplicationSeq(1L)).thenReturn(Optional.of(cof));

        var request = new com.bluelight.backend.api.admin.dto.CompleteApplicationRequest();

        assertThatThrownBy(() -> service.completeApplication(1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex ->
                        assertThat(((BusinessException) ex).getCode()).isEqualTo("COF_NOT_FINALIZED"));

        verify(app, never()).issueLicense(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
