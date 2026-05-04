package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailDispatchHistoryItem;
import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailDispatchResponse;
import com.bluelight.backend.api.admin.manualemail.dto.SendManualEmailRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.manualemail.BodyFormat;
import com.bluelight.backend.domain.manualemail.DispatchStatus;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatchRepository;
import com.bluelight.backend.domain.manualemail.RecipientType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-1 — {@link ManualEmailDispatcher} 단위 테스트.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §6 (AC-A1 단일 발송, AC-A5 필수값
 * 검증, AC-A9 멱등성, AC-A10 이력).</p>
 */
@DisplayName("ManualEmailDispatcher — PR-1")
class ManualEmailDispatcherTest {

    private static final long ADMIN_SEQ = 99L;
    private static final long APPLICANT_SEQ = 12L;
    private static final long LEW_SEQ = 45L;

    private ManualEmailDispatchRepository dispatchRepository;
    private UserRepository userRepository;
    private ApplicationRepository applicationRepository;
    private AuditLogService auditLogService;
    private ApplicationEventPublisher eventPublisher;
    private ManualEmailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatchRepository = mock(ManualEmailDispatchRepository.class);
        userRepository = mock(UserRepository.class);
        applicationRepository = mock(ApplicationRepository.class);
        auditLogService = mock(AuditLogService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        dispatcher = new ManualEmailDispatcher(
                dispatchRepository, userRepository, applicationRepository,
                auditLogService, eventPublisher);

        // dispatchRepository.save() 가 dispatchSeq 가 채워진 entity 를 반환하도록 stub.
        when(dispatchRepository.save(any(ManualEmailDispatch.class))).thenAnswer(inv -> {
            ManualEmailDispatch e = inv.getArgument(0);
            // dispatchSeq 는 reflection 없이 setter 가 없으므로 Mockito spy 대신 그대로 반환 — 실제
            // ID 필드는 null 이지만 본 테스트는 ID 가 응답에 그대로 매핑되는지만 확인하므로 OK.
            return e;
        });
        // 멱등성 가드 — 기본은 빈 리스트(중복 없음).
        when(dispatchRepository.findRecentDuplicate(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
    }

    private User systemUser(Long userSeq, UserRole role, String email) {
        User u = mock(User.class);
        when(u.getUserSeq()).thenReturn(userSeq);
        when(u.getRole()).thenReturn(role);
        when(u.getEmail()).thenReturn(email);
        return u;
    }

    private SendManualEmailRequest applicantRequest() {
        SendManualEmailRequest r = new SendManualEmailRequest();
        r.setRecipientType(RecipientType.APPLICANT);
        r.setRecipientUserSeq(APPLICANT_SEQ);
        r.setSubject("Maintenance notice for your application");
        r.setBodyText("Hello,\nWe will undergo maintenance from 22:00 SGT.\n— LicenseKaki");
        r.setCategoryTag("MAINTENANCE");
        return r;
    }

    @Test
    @DisplayName("AC-A1 APPLICANT 단일 발송 — row 저장 + audit + AFTER_COMMIT 이벤트 발행")
    void dispatch_APPLICANT_정상() {
        // Mockito 의 nested-when 트랩 회피 — mock 을 완전히 구성한 뒤 stub 에 넘긴다.
        User user = systemUser(APPLICANT_SEQ, UserRole.APPLICANT, "alice@example.com");
        when(userRepository.findById(APPLICANT_SEQ)).thenReturn(Optional.of(user));

        ManualEmailDispatchResponse response = dispatcher.dispatch(applicantRequest(), ADMIN_SEQ);

        // 응답 status 는 PENDING (AFTER_COMMIT 단계에서 SENT/FAILED 로 갱신).
        assertThat(response.getDispatchStatus()).isEqualTo(DispatchStatus.PENDING);
        assertThat(response.getSentCount()).isZero();
        assertThat(response.getFailedCount()).isZero();

        // 저장된 row 검증.
        ArgumentCaptor<ManualEmailDispatch> rowCaptor = ArgumentCaptor.forClass(ManualEmailDispatch.class);
        verify(dispatchRepository).save(rowCaptor.capture());
        ManualEmailDispatch saved = rowCaptor.getValue();
        assertThat(saved.getSenderUserSeq()).isEqualTo(ADMIN_SEQ);
        assertThat(saved.getRecipientType()).isEqualTo(RecipientType.APPLICANT);
        assertThat(saved.getRecipientUserSeq()).isEqualTo(APPLICANT_SEQ);
        assertThat(saved.getRecipientEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getSubject()).contains("Maintenance");
        assertThat(saved.getBodyFormat()).isEqualTo(BodyFormat.PLAIN_TEXT);
        assertThat(saved.getCategoryTag()).isEqualTo("MAINTENANCE");
        assertThat(saved.getDispatchStatus()).isEqualTo(DispatchStatus.PENDING);

        // audit 로그 기록.
        verify(auditLogService).logAsync(
                eq(ADMIN_SEQ),
                eq(AuditAction.MANUAL_EMAIL_DISPATCHED),
                eq(AuditCategory.ADMIN),
                eq("ManualEmailDispatch"),
                anyString(),
                anyString(),
                any(), any(),
                any(), any(),
                eq("POST"), eq("/api/admin/manual-emails"), eq(200));

        // AFTER_COMMIT 이벤트 발행.
        verify(eventPublisher).publishEvent(any(ManualEmailDispatchRequestedEvent.class));
    }

    @Test
    @DisplayName("AC-A2 LEW 단일 발송 — role 매칭 검증 통과")
    void dispatch_LEW_정상() {
        SendManualEmailRequest req = new SendManualEmailRequest();
        req.setRecipientType(RecipientType.LEW);
        req.setRecipientUserSeq(LEW_SEQ);
        req.setSubject("Reminder");
        req.setBodyText("Please review the application.");
        User user = systemUser(LEW_SEQ, UserRole.LEW, "lew@example.com");
        when(userRepository.findById(LEW_SEQ)).thenReturn(Optional.of(user));

        ManualEmailDispatchResponse response = dispatcher.dispatch(req, ADMIN_SEQ);

        assertThat(response.getDispatchStatus()).isEqualTo(DispatchStatus.PENDING);
        ArgumentCaptor<ManualEmailDispatch> cap = ArgumentCaptor.forClass(ManualEmailDispatch.class);
        verify(dispatchRepository).save(cap.capture());
        assertThat(cap.getValue().getRecipientEmail()).isEqualTo("lew@example.com");
    }

    @Test
    @DisplayName("AC-A3 EXTERNAL — recipientUserSeq 무시 + 입력 이메일 그대로 사용")
    void dispatch_EXTERNAL_정상() {
        SendManualEmailRequest req = new SendManualEmailRequest();
        req.setRecipientType(RecipientType.EXTERNAL);
        req.setRecipientEmail("partner@spgroup.com.sg");
        req.setSubject("Coordination email");
        req.setBodyText("Following up on yesterday's call.");

        dispatcher.dispatch(req, ADMIN_SEQ);

        ArgumentCaptor<ManualEmailDispatch> cap = ArgumentCaptor.forClass(ManualEmailDispatch.class);
        verify(dispatchRepository).save(cap.capture());
        assertThat(cap.getValue().getRecipientType()).isEqualTo(RecipientType.EXTERNAL);
        assertThat(cap.getValue().getRecipientUserSeq()).isNull();
        assertThat(cap.getValue().getRecipientEmail()).isEqualTo("partner@spgroup.com.sg");
        // EXTERNAL 은 user lookup 호출 없음.
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("AC-A7-PR1 MULTI 거부 — 400 MULTI_NOT_SUPPORTED_IN_PR1")
    void dispatch_MULTI_거부() {
        SendManualEmailRequest req = applicantRequest();
        req.setRecipientType(RecipientType.MULTI);

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Multi-recipient")
                .matches(t -> ((BusinessException) t).getCode().equals("MULTI_NOT_SUPPORTED_IN_PR1"));
        verify(dispatchRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("APPLICANT — 사용자 미존재 시 400 RECIPIENT_USER_NOT_FOUND")
    void dispatch_APPLICANT_사용자미존재() {
        SendManualEmailRequest req = applicantRequest();
        when(userRepository.findById(APPLICANT_SEQ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("RECIPIENT_USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("APPLICANT — role 불일치 시 400 RECIPIENT_ROLE_MISMATCH (LEW 가 APPLICANT 로 잘못 지정)")
    void dispatch_APPLICANT_role불일치() {
        SendManualEmailRequest req = applicantRequest();
        req.setRecipientUserSeq(LEW_SEQ);
        User user = systemUser(LEW_SEQ, UserRole.LEW, "lew@example.com");
        when(userRepository.findById(LEW_SEQ)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("RECIPIENT_ROLE_MISMATCH"));
    }

    @Test
    @DisplayName("APPLICANT — recipientUserSeq null 시 400 RECIPIENT_USER_SEQ_REQUIRED")
    void dispatch_APPLICANT_userSeq_null() {
        SendManualEmailRequest req = applicantRequest();
        req.setRecipientUserSeq(null);

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("RECIPIENT_USER_SEQ_REQUIRED"));
    }

    @Test
    @DisplayName("EXTERNAL — recipientEmail null/blank 시 400 RECIPIENT_EMAIL_REQUIRED")
    void dispatch_EXTERNAL_email_null() {
        SendManualEmailRequest req = new SendManualEmailRequest();
        req.setRecipientType(RecipientType.EXTERNAL);
        req.setSubject("X");
        req.setBodyText("Y");

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("RECIPIENT_EMAIL_REQUIRED"));
    }

    @Test
    @DisplayName("relatedApplicationSeq 미존재 시 400 APPLICATION_NOT_FOUND")
    void dispatch_application_미존재() {
        SendManualEmailRequest req = applicantRequest();
        req.setRelatedApplicationSeq(9999L);
        User user = systemUser(APPLICANT_SEQ, UserRole.APPLICANT, "alice@example.com");
        when(userRepository.findById(APPLICANT_SEQ)).thenReturn(Optional.of(user));
        when(applicationRepository.existsById(9999L)).thenReturn(false);

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("APPLICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("AC-A9 멱등성 — 30초 내 동일 (sender+recipient+subject+body) 발견 시 409")
    void dispatch_멱등성_충돌() {
        SendManualEmailRequest req = applicantRequest();
        User user = systemUser(APPLICANT_SEQ, UserRole.APPLICANT, "alice@example.com");
        when(userRepository.findById(APPLICANT_SEQ)).thenReturn(Optional.of(user));
        ManualEmailDispatch recent = ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.APPLICANT)
                .recipientUserSeq(APPLICANT_SEQ)
                .recipientEmail("alice@example.com")
                .subject(req.getSubject())
                .bodyText(req.getBodyText())
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .build();
        when(dispatchRepository.findRecentDuplicate(eq(ADMIN_SEQ), eq("alice@example.com"),
                eq(req.getSubject()), eq(req.getBodyText()), any(), any()))
                .thenReturn(List.of(recent));

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("MANUAL_EMAIL_DUPLICATE_SUSPECTED"));
        verify(dispatchRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("AC-A9 멱등성 우회 — forceDuplicate=true 면 409 무시하고 정상 발송")
    void dispatch_멱등성_forceDuplicate() {
        SendManualEmailRequest req = applicantRequest();
        req.setForceDuplicate(true);
        User user = systemUser(APPLICANT_SEQ, UserRole.APPLICANT, "alice@example.com");
        when(userRepository.findById(APPLICANT_SEQ)).thenReturn(Optional.of(user));
        // 중복이 있어도 force=true 면 검사 자체를 스킵한다.

        ManualEmailDispatchResponse response = dispatcher.dispatch(req, ADMIN_SEQ);

        assertThat(response.getDispatchStatus()).isEqualTo(DispatchStatus.PENDING);
        verify(dispatchRepository).save(any());
        verify(eventPublisher).publishEvent(any(ManualEmailDispatchRequestedEvent.class));
        // force=true 면 멱등 검사 자체를 우회 — repository 호출 없음.
        verify(dispatchRepository, never()).findRecentDuplicate(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("AC-A10 이력 페이지네이션 — 필터 그대로 repository 에 위임")
    void getDispatchHistory_위임() {
        Pageable pageable = PageRequest.of(0, 20);
        ManualEmailDispatcher.HistoryFilter filter = new ManualEmailDispatcher.HistoryFilter(
                ADMIN_SEQ, DispatchStatus.SENT, null, null, null);
        when(dispatchRepository.searchHistory(eq(ADMIN_SEQ), eq(DispatchStatus.SENT), eq(null),
                eq(null), eq(null), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        Page<ManualEmailDispatchHistoryItem> result = dispatcher.getDispatchHistory(filter, pageable);

        assertThat(result.getTotalElements()).isZero();
        verify(dispatchRepository, times(1)).searchHistory(
                eq(ADMIN_SEQ), eq(DispatchStatus.SENT), eq(null), eq(null), eq(null), eq(pageable));
    }

    @Test
    @DisplayName("getDispatchDetail — row 미존재 시 404 MANUAL_EMAIL_DISPATCH_NOT_FOUND")
    void getDispatchDetail_미존재() {
        when(dispatchRepository.findById(123L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dispatcher.getDispatchDetail(123L))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("MANUAL_EMAIL_DISPATCH_NOT_FOUND"));
    }

    @Test
    @DisplayName("body 자유도 — categoryTag/relatedApplicationSeq 모두 null 도 정상 발송")
    void dispatch_옵션필드_null_OK() {
        SendManualEmailRequest req = new SendManualEmailRequest();
        req.setRecipientType(RecipientType.APPLICANT);
        req.setRecipientUserSeq(APPLICANT_SEQ);
        req.setSubject("S");
        req.setBodyText("B");
        User user = systemUser(APPLICANT_SEQ, UserRole.APPLICANT, "alice@example.com");
        when(userRepository.findById(APPLICANT_SEQ)).thenReturn(Optional.of(user));

        dispatcher.dispatch(req, ADMIN_SEQ);

        ArgumentCaptor<ManualEmailDispatch> cap = ArgumentCaptor.forClass(ManualEmailDispatch.class);
        verify(dispatchRepository).save(cap.capture());
        assertThat(cap.getValue().getCategoryTag()).isNull();
        assertThat(cap.getValue().getRelatedApplicationSeq()).isNull();
    }
}
