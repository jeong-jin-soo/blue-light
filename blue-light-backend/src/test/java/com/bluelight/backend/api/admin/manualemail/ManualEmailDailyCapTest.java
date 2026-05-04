package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.admin.manualemail.dto.SendManualEmailRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatchRepository;
import com.bluelight.backend.domain.manualemail.RecipientType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-4 — {@link ManualEmailDispatcher} Daily cap 가드 단위 테스트.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §8.4 / AC-A12 / D5=B.</p>
 *
 * <h3>검증</h3>
 * <ul>
 *   <li>오늘 99건 발송된 ADMIN 이 1건 추가 → 통과 (99+1=100, cap=100).</li>
 *   <li>오늘 100건 발송된 ADMIN 이 1건 추가 → 429 MANUAL_EMAIL_DAILY_CAP_EXCEEDED.</li>
 *   <li>오늘 95건 발송된 ADMIN 이 MULTI 6건 → 429 (95+6=101 > 100).</li>
 *   <li>QuotaSnapshot 응답 — usedToday + remaining = dailyCap 항등식.</li>
 * </ul>
 */
@DisplayName("ManualEmailDispatcher — PR-4 Daily cap")
class ManualEmailDailyCapTest {

    private static final long ADMIN_SEQ = 99L;
    private static final long APPLICANT_SEQ = 12L;

    private ManualEmailDispatchRepository dispatchRepository;
    private UserRepository userRepository;
    private ApplicationRepository applicationRepository;
    private AuditLogService auditLogService;
    private ApplicationEventPublisher eventPublisher;
    private EmailService emailService;
    private ManualEmailSettings manualEmailSettings;
    private ManualEmailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatchRepository = mock(ManualEmailDispatchRepository.class);
        userRepository = mock(UserRepository.class);
        applicationRepository = mock(ApplicationRepository.class);
        auditLogService = mock(AuditLogService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        emailService = mock(EmailService.class);
        manualEmailSettings = mock(ManualEmailSettings.class);
        dispatcher = new ManualEmailDispatcher(
                dispatchRepository, userRepository, applicationRepository,
                auditLogService, eventPublisher, emailService, manualEmailSettings);

        // Cap 100, 멱등성 무효 (빈 리스트), save 는 echo.
        when(manualEmailSettings.loadDailyCap()).thenReturn(100);
        when(dispatchRepository.findRecentDuplicateByHash(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of());
        when(dispatchRepository.save(any(ManualEmailDispatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // APPLICANT lookup
        User applicant = mock(User.class);
        when(applicant.getUserSeq()).thenReturn(APPLICANT_SEQ);
        when(applicant.getRole()).thenReturn(UserRole.APPLICANT);
        when(applicant.getEmail()).thenReturn("alice@example.com");
        when(userRepository.findById(APPLICANT_SEQ)).thenReturn(Optional.of(applicant));
    }

    private SendManualEmailRequest singleApplicantRequest() {
        SendManualEmailRequest r = new SendManualEmailRequest();
        r.setRecipientType(RecipientType.APPLICANT);
        r.setRecipientUserSeq(APPLICANT_SEQ);
        r.setSubject("Notice");
        r.setBodyText("Hello.");
        return r;
    }

    @Test
    @DisplayName("cap 100, 오늘 99건 발송된 ADMIN — 1건 추가 통과 (99+1=100)")
    void dispatch_99_then_1_passes() {
        when(dispatchRepository.sumDailyRecipientCountByCreatedBy(anyLong(), any(), any()))
                .thenReturn(99L);

        // 정상 통과 (예외 없음).
        dispatcher.dispatch(singleApplicantRequest(), ADMIN_SEQ);

        // row 가 저장되어야 한다.
        verify(dispatchRepository).save(any(ManualEmailDispatch.class));
    }

    @Test
    @DisplayName("AC-A12: cap 100, 오늘 100건 발송된 ADMIN — 1건 추가 시 429")
    void dispatch_100_then_1_rejects() {
        when(dispatchRepository.sumDailyRecipientCountByCreatedBy(anyLong(), any(), any()))
                .thenReturn(100L);

        assertThatThrownBy(() -> dispatcher.dispatch(singleApplicantRequest(), ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(be.getCode()).isEqualTo("MANUAL_EMAIL_DAILY_CAP_EXCEEDED");
                    // 메시지에 cap + remaining 정보 포함.
                    assertThat(be.getMessage()).contains("100");
                    assertThat(be.getMessage()).contains("remaining 0");
                });

        // row 저장도 일어나지 않아야 한다 (가드가 save 이전에 거부).
        verify(dispatchRepository, never()).save(any(ManualEmailDispatch.class));
    }

    @Test
    @DisplayName("MULTI 6건이 cap 을 넘는 경우 — 95+6=101 → 429")
    void dispatch_multi_exceeds_cap() {
        // sumDailyRecipientCountByCreatedBy = 95, 발송 시도 = MULTI 6건 → cap 100 초과.
        when(dispatchRepository.sumDailyRecipientCountByCreatedBy(anyLong(), any(), any()))
                .thenReturn(95L);

        // MULTI 6명 — 시스템 사용자 5명 + 외부 1명.
        for (long s = 1; s <= 5; s++) {
            User u = mock(User.class);
            when(u.getUserSeq()).thenReturn(s);
            when(u.getRole()).thenReturn(UserRole.APPLICANT);
            when(u.getEmail()).thenReturn("user" + s + "@example.com");
            when(userRepository.findById(s)).thenReturn(Optional.of(u));
        }

        SendManualEmailRequest multi = new SendManualEmailRequest();
        multi.setRecipientType(RecipientType.MULTI);
        multi.setRecipientUserSeqs(List.of(1L, 2L, 3L, 4L, 5L));
        multi.setRecipientEmails(List.of("ext@example.com"));
        multi.setSubject("Batch");
        multi.setBodyText("Hi");

        assertThatThrownBy(() -> dispatcher.dispatch(multi, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo("MANUAL_EMAIL_DAILY_CAP_EXCEEDED");
                    assertThat(be.getMessage()).contains("remaining 5");
                });
    }

    @Test
    @DisplayName("getQuotaSnapshot — usedToday + remaining = dailyCap 항등식")
    void quotaSnapshot_basic() {
        when(dispatchRepository.sumDailyRecipientCountByCreatedBy(anyLong(), any(), any()))
                .thenReturn(32L);

        ManualEmailDispatcher.QuotaSnapshot snap = dispatcher.getQuotaSnapshot(ADMIN_SEQ);

        assertThat(snap.dailyCap()).isEqualTo(100);
        assertThat(snap.usedToday()).isEqualTo(32);
        assertThat(snap.remaining()).isEqualTo(68);
    }

    @Test
    @DisplayName("getQuotaSnapshot — sum 이 cap 보다 크면 remaining=0 (음수 방지)")
    void quotaSnapshot_overcap() {
        when(dispatchRepository.sumDailyRecipientCountByCreatedBy(anyLong(), any(), any()))
                .thenReturn(120L);

        ManualEmailDispatcher.QuotaSnapshot snap = dispatcher.getQuotaSnapshot(ADMIN_SEQ);

        assertThat(snap.remaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("getCategorySuggestions — settings 로드값 그대로 반환")
    void categorySuggestions_passthrough() {
        when(manualEmailSettings.loadCategorySuggestions())
                .thenReturn(List.of("MAINTENANCE", "URGENT"));

        List<String> result = dispatcher.getCategorySuggestions();

        assertThat(result).containsExactly("MAINTENANCE", "URGENT");
    }
}
