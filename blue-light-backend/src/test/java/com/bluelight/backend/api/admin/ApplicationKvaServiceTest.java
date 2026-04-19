package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.ConfirmKvaRequest;
import com.bluelight.backend.api.admin.dto.ConfirmKvaResponse;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.application.KvaSource;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 PR#1 — {@link ApplicationKvaService} 분기 테스트.
 *
 * <ul>
 *   <li>B-3: PAID/IN_PROGRESS/COMPLETED/EXPIRED 상태에서 force 와 무관하게 409 KVA_LOCKED_AFTER_PAYMENT</li>
 *   <li>B-4: 성공 경로는 force=false → KVA_CONFIRMED_BY_LEW, force=true → KVA_OVERRIDDEN_BY_ADMIN 감사</li>
 *   <li>AC-P1: 이미 CONFIRMED + force=false → 409 KVA_ALREADY_CONFIRMED</li>
 *   <li>AC-A3: 유효하지 않은 tier → 400 INVALID_KVA_TIER</li>
 *   <li>AC-A2: 미할당 LEW → 403 (OwnershipValidator 위임)</li>
 * </ul>
 */
class ApplicationKvaServiceTest {

    private ApplicationRepository applicationRepository;
    private MasterPriceRepository masterPriceRepository;
    private UserRepository userRepository;
    private AuditLogService auditLogService;
    private NotificationService notificationService;
    private ApplicationKvaService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        masterPriceRepository = mock(MasterPriceRepository.class);
        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);
        notificationService = mock(NotificationService.class);
        service = new ApplicationKvaService(
                applicationRepository, masterPriceRepository, userRepository,
                auditLogService, notificationService);
    }

    private Application mockApp(Long id, ApplicationStatus status, KvaStatus kvaStatus,
                                Long ownerSeq, Long assignedLewSeq) {
        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(id);
        when(app.getStatus()).thenReturn(status);
        when(app.getKvaStatus()).thenReturn(kvaStatus);
        when(app.getSelectedKva()).thenReturn(45);
        when(app.getQuoteAmount()).thenReturn(new BigDecimal("350.00"));
        when(app.getApplicationType()).thenReturn(ApplicationType.NEW);
        // owner
        User owner = mock(User.class);
        when(owner.getUserSeq()).thenReturn(ownerSeq);
        when(app.getUser()).thenReturn(owner);
        // LEW
        if (assignedLewSeq != null) {
            User lew = mock(User.class);
            when(lew.getUserSeq()).thenReturn(assignedLewSeq);
            when(app.getAssignedLew()).thenReturn(lew);
        }
        return app;
    }

    private MasterPrice mockPrice() {
        MasterPrice mp = mock(MasterPrice.class);
        when(mp.getPrice()).thenReturn(new BigDecimal("650.00"));
        when(mp.getRenewalPrice()).thenReturn(new BigDecimal("400.00"));
        when(mp.getSldPrice()).thenReturn(new BigDecimal("100.00"));
        return mp;
    }

    private ConfirmKvaRequest req(Integer kva, String note) {
        ConfirmKvaRequest r = new ConfirmKvaRequest();
        r.setSelectedKva(kva);
        r.setNote(note);
        return r;
    }

    @Test
    void B3_PAID_상태에서는_force_true여도_409_KVA_LOCKED_AFTER_PAYMENT() {
        Application app = mockApp(1L, ApplicationStatus.PAID, KvaStatus.CONFIRMED, 10L, 20L);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() ->
                service.confirm(1L, req(100, "force"), true, 99L, "ROLE_ADMIN"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_LOCKED_AFTER_PAYMENT"));

        // 감사: CONFIRMATION_DENIED 기록됨
        verify(auditLogService).logAsync(
                eq(99L), eq(AuditAction.KVA_CONFIRMATION_DENIED),
                any(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), anyString(), any());
        verify(app, never()).confirmKva(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void ACP1_이미_CONFIRMED이고_force_false면_409_KVA_ALREADY_CONFIRMED() {
        Application app = mockApp(1L, ApplicationStatus.PENDING_REVIEW, KvaStatus.CONFIRMED, 10L, 20L);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() ->
                service.confirm(1L, req(200, "retry"), false, 20L, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_ALREADY_CONFIRMED"));
    }

    @Test
    void ACA3_유효하지_않은_tier면_400_INVALID_KVA_TIER() {
        Application app = mockApp(1L, ApplicationStatus.PENDING_REVIEW, KvaStatus.UNKNOWN, 10L, 20L);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        when(masterPriceRepository.findByKva(250)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.confirm(1L, req(250, "bogus"), false, 20L, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("INVALID_KVA_TIER"));
    }

    @Test
    void ACA2_미할당_LEW면_403_ACCESS_DENIED() {
        Application app = mockApp(1L, ApplicationStatus.PENDING_REVIEW, KvaStatus.UNKNOWN,
                /* owner */ 10L, /* assignedLew */ 20L);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() ->
                service.confirm(1L, req(100, "unauthorized LEW"), false,
                        /* actor=다른 LEW */ 999L, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("ACCESS_DENIED"));
    }

    @Test
    void B4_정상_확정시_KVA_CONFIRMED_BY_LEW_감사_이벤트() {
        Application app = mockApp(1L, ApplicationStatus.PENDING_REVIEW, KvaStatus.UNKNOWN, 10L, 20L);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        MasterPrice mp100 = mockPrice();
        when(masterPriceRepository.findByKva(100)).thenReturn(Optional.of(mp100));
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(20L);
        when(userRepository.findById(20L)).thenReturn(Optional.of(lew));

        // 도메인 confirmKva 호출 이후 response 빌더가 읽는 최종 상태
        when(app.getKvaSource()).thenReturn(KvaSource.LEW_VERIFIED);

        ConfirmKvaResponse resp = service.confirm(1L, req(100, "SP bill verified"),
                false, 20L, "ROLE_LEW");

        assertThat(resp).isNotNull();
        verify(app).confirmKva(eq(100), any(BigDecimal.class), eq(lew), eq(false));

        ArgumentCaptor<AuditAction> actionCap = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditLogService).logAsync(
                eq(20L), actionCap.capture(), any(),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), anyString(), any());
        assertThat(actionCap.getValue()).isEqualTo(AuditAction.KVA_CONFIRMED_BY_LEW);
    }

    @Test
    void B4_force_true_확정시_KVA_OVERRIDDEN_BY_ADMIN_감사_이벤트() {
        Application app = mockApp(1L, ApplicationStatus.PENDING_REVIEW, KvaStatus.CONFIRMED, 10L, 20L);
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));
        MasterPrice mp200 = mockPrice();
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp200));
        User admin = mock(User.class);
        when(admin.getUserSeq()).thenReturn(99L);
        when(userRepository.findById(99L)).thenReturn(Optional.of(admin));

        service.confirm(1L, req(200, "Override — actual 200kVA"),
                /* force */ true, 99L, "ROLE_ADMIN");

        verify(app).confirmKva(eq(200), any(BigDecimal.class), eq(admin), eq(true));

        ArgumentCaptor<AuditAction> actionCap = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditLogService).logAsync(
                eq(99L), actionCap.capture(), any(),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), anyString(), any());
        assertThat(actionCap.getValue()).isEqualTo(AuditAction.KVA_OVERRIDDEN_BY_ADMIN);
    }
}
