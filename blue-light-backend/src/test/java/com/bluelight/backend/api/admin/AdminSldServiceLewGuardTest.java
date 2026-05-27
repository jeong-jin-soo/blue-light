package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SldUploadedDto;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.SldRequestRepository;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L-3 (보안 감사 H-2 동일 패턴) — AdminSldService LEW cross-tenant 변조 차단 검증.
 *
 * <p>임의 승인 LEW 가 타인 배정 신청서의 SLD 관리 API 를 호출 시 403 (ACCESS_DENIED) 발생.
 * ADMIN/SYSTEM_ADMIN 은 가드 통과.</p>
 */
@DisplayName("AdminSldService LEW cross-tenant 가드 - L-3")
class AdminSldServiceLewGuardTest {

    private static final Long LEW_A_SEQ = 100L;
    private static final Long LEW_B_SEQ = 200L;
    private static final Long APPLICATION_SEQ = 1L;

    private ApplicationRepository applicationRepository;
    private SldRequestRepository sldRequestRepository;
    private AdminSldService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        sldRequestRepository = mock(SldRequestRepository.class);
        service = new AdminSldService(applicationRepository, sldRequestRepository);
    }

    private Application appAssignedTo(Long lewSeq) {
        Application app = mock(Application.class);
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(lewSeq);
        when(app.getAssignedLew()).thenReturn(lew);
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));
        when(applicationRepository.existsById(APPLICATION_SEQ)).thenReturn(true);
        return app;
    }

    private Application appWithNoAssignedLew() {
        Application app = mock(Application.class);
        when(app.getAssignedLew()).thenReturn(null);
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));
        when(applicationRepository.existsById(APPLICATION_SEQ)).thenReturn(true);
        return app;
    }

    // ============================================================
    // getAdminSldRequest
    // ============================================================

    @Test
    @DisplayName("getAdminSldRequest - LEW 가 타인 배정 신청서 호출 → 403 ACCESS_DENIED")
    void getAdminSldRequest_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.getAdminSldRequest(APPLICATION_SEQ, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        // sldRequestRepository.find 가 호출되지 않았는지 확인 (가드가 차단)
        verify(sldRequestRepository, never()).findByApplicationApplicationSeq(any());
    }

    @Test
    @DisplayName("getAdminSldRequest - assignedLew=null 신청서를 LEW 가 호출 → 403")
    void getAdminSldRequest_unassignedApplicationBlocksLew() {
        appWithNoAssignedLew();

        assertThatThrownBy(() -> service.getAdminSldRequest(APPLICATION_SEQ, LEW_A_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(sldRequestRepository, never()).findByApplicationApplicationSeq(any());
    }

    @Test
    @DisplayName("getAdminSldRequest - ADMIN 호출은 가드 통과 (LEW 가드 무관)")
    void getAdminSldRequest_adminBypassesLewGuard() {
        when(applicationRepository.existsById(APPLICATION_SEQ)).thenReturn(true);

        // ADMIN 은 LEW 가드 즉시 통과 (findById 미호출), validateApplicationExists 만 동작
        service.getAdminSldRequest(APPLICATION_SEQ, 999L, "ROLE_ADMIN");

        // findById 는 호출되지 않아야 함 (ADMIN 은 early-return)
        verify(applicationRepository, never()).findById(any());
        verify(sldRequestRepository).findByApplicationApplicationSeq(APPLICATION_SEQ);
    }

    @Test
    @DisplayName("getAdminSldRequest - LEW 본인 배정 신청서는 가드 통과")
    void getAdminSldRequest_lewOwnAssignedApplicationPassesGuard() {
        appAssignedTo(LEW_A_SEQ);

        service.getAdminSldRequest(APPLICATION_SEQ, LEW_A_SEQ, "ROLE_LEW");

        verify(sldRequestRepository).findByApplicationApplicationSeq(APPLICATION_SEQ);
    }

    // ============================================================
    // uploadSld
    // ============================================================

    @Test
    @DisplayName("uploadSld - LEW 가 타인 배정 신청서 호출 → 403, SldRequest mutate 없음")
    void uploadSld_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);
        SldUploadedDto dto = mock(SldUploadedDto.class);

        assertThatThrownBy(() -> service.uploadSld(APPLICATION_SEQ, dto, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(sldRequestRepository, never()).findByApplicationApplicationSeq(any());
    }

    // ============================================================
    // confirmSld
    // ============================================================

    @Test
    @DisplayName("confirmSld - LEW 가 타인 배정 신청서 호출 → 403")
    void confirmSld_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.confirmSld(APPLICATION_SEQ, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(sldRequestRepository, never()).findByApplicationApplicationSeq(any());
    }

    // ============================================================
    // unconfirmSld
    // ============================================================

    @Test
    @DisplayName("unconfirmSld - LEW 가 타인 배정 신청서 호출 → 403")
    void unconfirmSld_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.unconfirmSld(APPLICATION_SEQ, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(sldRequestRepository, never()).findByApplicationApplicationSeq(any());
    }

    // ============================================================
    // startAiGeneration
    // ============================================================

    @Test
    @DisplayName("startAiGeneration - LEW 가 타인 배정 신청서 호출 → 403")
    void startAiGeneration_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.startAiGeneration(APPLICATION_SEQ, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(sldRequestRepository, never()).findByApplicationApplicationSeq(any());
    }

    // ============================================================
    // SYSTEM_ADMIN bypass
    // ============================================================

    @Test
    @DisplayName("SYSTEM_ADMIN 호출은 가드 즉시 통과 (DB findById 미접근)")
    void systemAdminBypassesLewGuard() {
        when(applicationRepository.existsById(APPLICATION_SEQ)).thenReturn(true);

        service.getAdminSldRequest(APPLICATION_SEQ, 999L, "ROLE_SYSTEM_ADMIN");

        verify(applicationRepository, never()).findById(any());
        verify(sldRequestRepository).findByApplicationApplicationSeq(APPLICATION_SEQ);
    }
}
