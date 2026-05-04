package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.admin.manualemail.dto.SendManualEmailRequest;
import com.bluelight.backend.api.audit.AuditLogService;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-2 — {@link ManualEmailDispatcher} MULTI 분기 단위 테스트.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §6 (AC-A4 부분 실패 — listener
 * 책임이지만 dispatcher 는 row 1건 + 수신자 리스트 저장만 검증).</p>
 */
@DisplayName("ManualEmailDispatcher — PR-2 MULTI 분기")
class ManualEmailDispatcherMultiTest {

    private static final long ADMIN_SEQ = 99L;
    private static final long APPLICANT1_SEQ = 12L;
    private static final long APPLICANT2_SEQ = 13L;
    private static final long LEW_SEQ = 45L;

    private ManualEmailDispatchRepository dispatchRepository;
    private UserRepository userRepository;
    private ApplicationRepository applicationRepository;
    private AuditLogService auditLogService;
    private ApplicationEventPublisher eventPublisher;
    private com.bluelight.backend.api.email.EmailService emailService;
    private ManualEmailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatchRepository = mock(ManualEmailDispatchRepository.class);
        userRepository = mock(UserRepository.class);
        applicationRepository = mock(ApplicationRepository.class);
        auditLogService = mock(AuditLogService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        emailService = mock(com.bluelight.backend.api.email.EmailService.class);
        dispatcher = new ManualEmailDispatcher(
                dispatchRepository, userRepository, applicationRepository,
                auditLogService, eventPublisher, emailService);

        when(dispatchRepository.save(any(ManualEmailDispatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(dispatchRepository.findRecentDuplicateByHash(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of());
    }

    /**
     * Mockito 의 nested-when 트랩 회피 — User mock 을 별도 메서드 안에서 완성한 뒤 반환한다.
     * (when(repo.findById(...)).thenReturn(buildUser(...)) 형태로 사용해 호출 순서가 꼬이지
     * 않도록 보장.)
     */
    private static User user(Long seq, UserRole role, String email) {
        User u = mock(User.class);
        when(u.getUserSeq()).thenReturn(seq);
        when(u.getRole()).thenReturn(role);
        when(u.getEmail()).thenReturn(email);
        return u;
    }

    private SendManualEmailRequest baseMulti() {
        SendManualEmailRequest r = new SendManualEmailRequest();
        r.setRecipientType(RecipientType.MULTI);
        r.setSubject("Maintenance batch");
        r.setBodyText("System maintenance scheduled.");
        return r;
    }

    @Test
    @DisplayName("MULTI 시스템 사용자 2명 — row 1건 저장 + emailsJson/userSeqsJson 채워짐")
    void dispatch_MULTI_systemUsers_OK() {
        // Mockito nested-when 트랩 회피: mock 을 변수로 먼저 만든 후 stub 에 주입.
        User u1 = user(APPLICANT1_SEQ, UserRole.APPLICANT, "alice@example.com");
        User u2 = user(APPLICANT2_SEQ, UserRole.APPLICANT, "bob@example.com");
        when(userRepository.findById(APPLICANT1_SEQ)).thenReturn(Optional.of(u1));
        when(userRepository.findById(APPLICANT2_SEQ)).thenReturn(Optional.of(u2));

        SendManualEmailRequest req = baseMulti();
        req.setRecipientUserSeqs(List.of(APPLICANT1_SEQ, APPLICANT2_SEQ));

        dispatcher.dispatch(req, ADMIN_SEQ);

        ArgumentCaptor<ManualEmailDispatch> cap = ArgumentCaptor.forClass(ManualEmailDispatch.class);
        verify(dispatchRepository).save(cap.capture());
        ManualEmailDispatch saved = cap.getValue();
        assertThat(saved.getRecipientType()).isEqualTo(RecipientType.MULTI);
        // 단일 컬럼 호환: 첫 번째(대표) 이메일.
        assertThat(saved.getRecipientEmail()).isEqualTo("alice@example.com");
        // PR-2: 전체 목록은 _json 컬럼.
        assertThat(saved.getRecipientUserSeqsJson()).containsExactly(APPLICANT1_SEQ, APPLICANT2_SEQ);
        assertThat(saved.getRecipientEmailsJson())
                .containsExactly("alice@example.com", "bob@example.com");
        // 멱등성 hash 채워짐.
        assertThat(saved.getRecipientHash()).hasSize(64);
        verify(eventPublisher).publishEvent(any(ManualEmailDispatchRequestedEvent.class));
    }

    @Test
    @DisplayName("MULTI 시스템 + 외부 혼합 — userSeqsJson + emailsJson 모두 채워지고 정렬+중복제거")
    void dispatch_MULTI_mixed_OK() {
        User u1 = user(APPLICANT1_SEQ, UserRole.APPLICANT, "Alice@Example.com");
        User uLew = user(LEW_SEQ, UserRole.LEW, "lew@licensekaki.sg");
        when(userRepository.findById(APPLICANT1_SEQ)).thenReturn(Optional.of(u1));
        when(userRepository.findById(LEW_SEQ)).thenReturn(Optional.of(uLew));

        SendManualEmailRequest req = baseMulti();
        req.setRecipientUserSeqs(List.of(APPLICANT1_SEQ, LEW_SEQ));
        // 외부 이메일 — 시스템 사용자와 중복(alice@example.com 소문자) + 신규.
        req.setRecipientEmails(List.of("alice@example.com", "partner@spgroup.com.sg"));

        dispatcher.dispatch(req, ADMIN_SEQ);

        ArgumentCaptor<ManualEmailDispatch> cap = ArgumentCaptor.forClass(ManualEmailDispatch.class);
        verify(dispatchRepository).save(cap.capture());
        ManualEmailDispatch saved = cap.getValue();
        // 중복 제거: alice 는 1번만 등장. 시스템 사용자가 우선 출현.
        assertThat(saved.getRecipientEmailsJson()).hasSize(3);
        // 시스템 사용자 부분은 입력 순서 유지: Alice(원본 대소문자), lew, partner.
        assertThat(saved.getRecipientEmailsJson().get(0)).isEqualToIgnoringCase("alice@example.com");
        assertThat(saved.getRecipientEmailsJson()).contains("lew@licensekaki.sg", "partner@spgroup.com.sg");
        assertThat(saved.getRecipientUserSeqsJson()).containsExactly(APPLICANT1_SEQ, LEW_SEQ);
    }

    @Test
    @DisplayName("MULTI 외부 이메일만 2명 — userSeqsJson 은 null, emailsJson 만 채워짐")
    void dispatch_MULTI_externalOnly_OK() {
        SendManualEmailRequest req = baseMulti();
        req.setRecipientEmails(List.of("a@x.com", "b@x.com"));

        dispatcher.dispatch(req, ADMIN_SEQ);

        ArgumentCaptor<ManualEmailDispatch> cap = ArgumentCaptor.forClass(ManualEmailDispatch.class);
        verify(dispatchRepository).save(cap.capture());
        ManualEmailDispatch saved = cap.getValue();
        assertThat(saved.getRecipientUserSeqsJson()).isNull();
        assertThat(saved.getRecipientEmailsJson()).containsExactly("a@x.com", "b@x.com");
        // 시스템 사용자 lookup 호출 없음.
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("MULTI 1건 (시스템 1명만) — 400 MULTI_REQUIRES_AT_LEAST_TWO_RECIPIENTS")
    void dispatch_MULTI_1건_거부() {
        User u1 = user(APPLICANT1_SEQ, UserRole.APPLICANT, "alice@example.com");
        when(userRepository.findById(APPLICANT1_SEQ)).thenReturn(Optional.of(u1));
        SendManualEmailRequest req = baseMulti();
        req.setRecipientUserSeqs(List.of(APPLICANT1_SEQ));

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode()
                        .equals("MULTI_REQUIRES_AT_LEAST_TWO_RECIPIENTS"));
        verify(dispatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("MULTI 동일 시스템 사용자 2번 — 중복 제거 후 1건 → 400 거부")
    void dispatch_MULTI_중복제거_후_1건_거부() {
        User u1 = user(APPLICANT1_SEQ, UserRole.APPLICANT, "alice@example.com");
        when(userRepository.findById(APPLICANT1_SEQ)).thenReturn(Optional.of(u1));
        SendManualEmailRequest req = baseMulti();
        req.setRecipientUserSeqs(List.of(APPLICANT1_SEQ));
        req.setRecipientEmails(List.of("ALICE@example.com")); // 같은 이메일

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode()
                        .equals("MULTI_REQUIRES_AT_LEAST_TWO_RECIPIENTS"));
    }

    @Test
    @DisplayName("MULTI 100건 초과 — 400 MULTI_EXCEEDS_MAX_RECIPIENTS")
    void dispatch_MULTI_상한_초과() {
        SendManualEmailRequest req = baseMulti();
        // 100 + 1 건 — DTO 의 @Size(max=100) 로도 막히지만 service 내 가드 별도 검증.
        // 본 테스트는 DTO validation 우회 (직접 호출).
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) tooMany.add("user" + i + "@x.com");
        req.setRecipientEmails(tooMany);

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode()
                        .equals("MULTI_EXCEEDS_MAX_RECIPIENTS"));
    }

    @Test
    @DisplayName("MULTI 시스템 사용자 ADMIN role 거부 — RECIPIENT_ROLE_MISMATCH")
    void dispatch_MULTI_role_불허() {
        User uAdmin = user(APPLICANT1_SEQ, UserRole.ADMIN, "boss@licensekaki.sg");
        when(userRepository.findById(APPLICANT1_SEQ)).thenReturn(Optional.of(uAdmin));
        SendManualEmailRequest req = baseMulti();
        req.setRecipientUserSeqs(List.of(APPLICANT1_SEQ));
        req.setRecipientEmails(List.of("partner@spgroup.com.sg"));

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("RECIPIENT_ROLE_MISMATCH"));
    }

    @Test
    @DisplayName("MULTI 멱등성 — 동일 hash 30초 내 발견 시 409")
    void dispatch_MULTI_멱등성_충돌() {
        User u1 = user(APPLICANT1_SEQ, UserRole.APPLICANT, "alice@example.com");
        User u2 = user(APPLICANT2_SEQ, UserRole.APPLICANT, "bob@example.com");
        when(userRepository.findById(APPLICANT1_SEQ)).thenReturn(Optional.of(u1));
        when(userRepository.findById(APPLICANT2_SEQ)).thenReturn(Optional.of(u2));

        ManualEmailDispatch recent = ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.MULTI)
                .recipientEmail("alice@example.com")
                .build();
        when(dispatchRepository.findRecentDuplicateByHash(eq(ADMIN_SEQ), anyString(), any(), any()))
                .thenReturn(List.of(recent));

        SendManualEmailRequest req = baseMulti();
        req.setRecipientUserSeqs(List.of(APPLICANT1_SEQ, APPLICANT2_SEQ));

        assertThatThrownBy(() -> dispatcher.dispatch(req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .matches(t -> ((BusinessException) t).getCode().equals("MANUAL_EMAIL_DUPLICATE_SUSPECTED"));
        verify(dispatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("MULTI 멱등성 — forceDuplicate=true 면 409 무시하고 정상 발송")
    void dispatch_MULTI_force_OK() {
        User u1 = user(APPLICANT1_SEQ, UserRole.APPLICANT, "alice@example.com");
        User u2 = user(APPLICANT2_SEQ, UserRole.APPLICANT, "bob@example.com");
        when(userRepository.findById(APPLICANT1_SEQ)).thenReturn(Optional.of(u1));
        when(userRepository.findById(APPLICANT2_SEQ)).thenReturn(Optional.of(u2));

        SendManualEmailRequest req = baseMulti();
        req.setRecipientUserSeqs(List.of(APPLICANT1_SEQ, APPLICANT2_SEQ));
        req.setForceDuplicate(true);

        dispatcher.dispatch(req, ADMIN_SEQ);

        verify(dispatchRepository).save(any());
        verify(dispatchRepository, never())
                .findRecentDuplicateByHash(anyLong(), anyString(), any(), any());
        verify(eventPublisher).publishEvent(any(ManualEmailDispatchRequestedEvent.class));
    }
}
