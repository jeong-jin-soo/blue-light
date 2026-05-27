package com.bluelight.backend.api.admin;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.config.GeminiConfig;
import com.bluelight.backend.config.SldAgentConfig;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.SldRequestRepository;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.sldchat.SldChatMessageRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.api.file.FileStorageService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L-3 (보안 감사 H-2 동일 패턴) — SldAgentService LEW cross-tenant 변조 차단 검증.
 *
 * <p>임의 승인 LEW 가 타인 배정 신청서의 SLD AI 채팅 / 미리보기 / 다운로드 API 를 호출 시
 * 403 (ACCESS_DENIED) 발생. ADMIN/SYSTEM_ADMIN 은 가드 통과.</p>
 *
 * <p>chatStream() 은 SSE + 비동기 트랜잭션이라 본 테스트에서 제외; 가드 로직은 동일하게
 * ensureLewCanAccess(application, userSeq, role) 를 transactional 블록 내 호출.</p>
 */
@DisplayName("SldAgentService LEW cross-tenant 가드 - L-3")
class SldAgentServiceLewGuardTest {

    private static final Long LEW_A_SEQ = 100L;
    private static final Long LEW_B_SEQ = 200L;
    private static final Long APPLICATION_SEQ = 1L;
    private static final String FILE_ID = "tmp_file_xyz";

    private ApplicationRepository applicationRepository;
    private SldChatMessageRepository sldChatMessageRepository;
    private SldRequestRepository sldRequestRepository;
    private SldAgentService service;

    @BeforeEach
    void setUp() {
        WebClient webClient = mock(WebClient.class);
        SldAgentConfig sldAgentConfig = mock(SldAgentConfig.class);
        sldChatMessageRepository = mock(SldChatMessageRepository.class);
        applicationRepository = mock(ApplicationRepository.class);
        sldRequestRepository = mock(SldRequestRepository.class);
        FileRepository fileRepository = mock(FileRepository.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        GeminiConfig geminiConfig = mock(GeminiConfig.class);

        service = new SldAgentService(
                webClient,
                sldAgentConfig,
                sldChatMessageRepository,
                applicationRepository,
                sldRequestRepository,
                fileRepository,
                fileStorageService,
                objectMapper,
                transactionTemplate,
                systemAdminService,
                geminiConfig);
    }

    private void appAssignedTo(Long lewSeq) {
        Application app = mock(Application.class);
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(lewSeq);
        when(app.getAssignedLew()).thenReturn(lew);
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));
        when(applicationRepository.existsById(APPLICATION_SEQ)).thenReturn(true);
    }

    private void appWithNoAssignedLew() {
        Application app = mock(Application.class);
        when(app.getAssignedLew()).thenReturn(null);
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));
        when(applicationRepository.existsById(APPLICATION_SEQ)).thenReturn(true);
    }

    // ============================================================
    // getChatHistory
    // ============================================================

    @Test
    @DisplayName("getChatHistory - LEW 가 타인 배정 신청서 호출 → 403, repo 호출 없음")
    void getChatHistory_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.getChatHistory(APPLICATION_SEQ, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(sldChatMessageRepository, never()).findByApplicationSeqOrderByCreatedAtAsc(any());
    }

    @Test
    @DisplayName("getChatHistory - assignedLew=null 신청서를 LEW 가 호출 → 403")
    void getChatHistory_unassignedApplicationBlocksLew() {
        appWithNoAssignedLew();

        assertThatThrownBy(() -> service.getChatHistory(APPLICATION_SEQ, LEW_A_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("getChatHistory - ADMIN 호출은 가드 즉시 통과 (LEW 가드 무관, findById 미호출)")
    void getChatHistory_adminBypassesLewGuard() {
        when(applicationRepository.existsById(APPLICATION_SEQ)).thenReturn(true);

        service.getChatHistory(APPLICATION_SEQ, 999L, "ROLE_ADMIN");

        verify(applicationRepository, never()).findById(any());
        verify(sldChatMessageRepository).findByApplicationSeqOrderByCreatedAtAsc(APPLICATION_SEQ);
    }

    @Test
    @DisplayName("getChatHistory - LEW 본인 배정 신청서는 가드 통과")
    void getChatHistory_lewOwnAssignedApplicationPassesGuard() {
        appAssignedTo(LEW_A_SEQ);

        service.getChatHistory(APPLICATION_SEQ, LEW_A_SEQ, "ROLE_LEW");

        verify(sldChatMessageRepository).findByApplicationSeqOrderByCreatedAtAsc(APPLICATION_SEQ);
    }

    // ============================================================
    // resetChat (mutate)
    // ============================================================

    @Test
    @DisplayName("resetChat - LEW 가 타인 배정 신청서 호출 → 403, 삭제 호출 없음")
    void resetChat_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.resetChat(APPLICATION_SEQ, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(sldChatMessageRepository, never()).deleteByApplicationSeq(any());
    }

    // ============================================================
    // acceptSld (mutate)
    // ============================================================

    @Test
    @DisplayName("acceptSld - LEW 가 타인 배정 신청서 호출 → 403, SldRequest mutate 없음")
    void acceptSld_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.acceptSld(APPLICATION_SEQ, FILE_ID, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
        verify(sldRequestRepository, never()).findByApplicationApplicationSeq(any());
    }

    // ============================================================
    // getSvgPreview
    // ============================================================

    @Test
    @DisplayName("getSvgPreview - LEW 가 타인 배정 신청서 호출 → 403")
    void getSvgPreview_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.getSvgPreview(APPLICATION_SEQ, FILE_ID, LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
    }

    // ============================================================
    // downloadGeneratedFile
    // ============================================================

    @Test
    @DisplayName("downloadGeneratedFile - LEW 가 타인 배정 신청서 호출 → 403")
    void downloadGeneratedFile_lewCrossTenantBlocked() {
        appAssignedTo(LEW_A_SEQ);

        assertThatThrownBy(() -> service.downloadGeneratedFile(APPLICATION_SEQ, FILE_ID, "pdf", LEW_B_SEQ, "ROLE_LEW"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("ACCESS_DENIED"));
    }

    // ============================================================
    // SYSTEM_ADMIN bypass
    // ============================================================

    @Test
    @DisplayName("SYSTEM_ADMIN 호출은 가드 즉시 통과 (DB findById 미접근)")
    void systemAdminBypassesLewGuard() {
        when(applicationRepository.existsById(APPLICATION_SEQ)).thenReturn(true);

        service.getChatHistory(APPLICATION_SEQ, 999L, "ROLE_SYSTEM_ADMIN");

        verify(applicationRepository, never()).findById(any());
        verify(sldChatMessageRepository).findByApplicationSeqOrderByCreatedAtAsc(APPLICATION_SEQ);
    }
}
