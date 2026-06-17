package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.admin.LoaFormTemplateService;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Part B — {@link LoaService#adminReplaceLoa} 단위 테스트.
 * <ul>
 *   <li>보관 정책: 기존 동일 타입 파일을 절대 삭제하지 않는다(fileRepository.delete 미호출).</li>
 *   <li>사유(reason) 공백 시 400 {@code LOA_REASON_REQUIRED} 로 거부한다.</li>
 *   <li>허용되지 않는 fileType 은 400 {@code INVALID_LOA_FILE_TYPE} 로 거부한다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoaServiceAdminReplaceTest {

    @Mock private ApplicationRepository applicationRepository;
    @Mock private FileRepository fileRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private AuditLogService auditLogService;
    @Mock private UserRepository userRepository;
    @Mock private ConciergeRequestRepository conciergeRequestRepository;
    @Mock private EmailService emailService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private LoaFormTemplateService loaFormTemplateService;

    @InjectMocks private LoaService loaService;

    /** 최소 stub 된 Application — buildStatus 의 NPE 방지용. */
    private Application stubApplication() {
        Application application = org.mockito.Mockito.mock(Application.class);
        when(application.getApplicationSeq()).thenReturn(42L);
        when(application.getApplicationType()).thenReturn(ApplicationType.NEW);
        return application;
    }

    /** 시그니처가 PDF 로 식별되도록 매직바이트 + content-type + 확장자를 갖춘 가짜 파일. */
    private MultipartFile pdfFile() {
        byte[] content = ("%PDF-1.4\n%minimal pdf body\n").getBytes();
        return new org.springframework.mock.web.MockMultipartFile(
                "file", "loa.pdf", "application/pdf", content);
    }

    @Test
    void adminReplace_keepsExistingFiles_neverDeletes() {
        Application application = stubApplication();
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(application));
        when(fileStorageService.store(any(), anyString())).thenReturn("applications/42/loa.pdf");

        FileEntity saved = FileEntity.builder()
                .application(application)
                .fileType(FileType.LOA_FINAL)
                .fileUrl("applications/42/loa.pdf")
                .originalFilename("loa.pdf")
                .fileSize(123L)
                .build();
        when(fileRepository.save(any())).thenReturn(saved);
        // buildStatus 가 조회하는 파일 리스트 — 보관된 기존본 1개가 그대로 남아있다고 가정.
        lenient().when(fileRepository.findByApplicationApplicationSeqAndFileType(any(), any()))
                .thenReturn(java.util.List.of());
        // buildStatus 의 active 폼 조회는 미설정(unavailable) 경로로 — BusinessException 은 무시된다.
        lenient().when(loaFormTemplateService.getActiveForm())
                .thenThrow(new BusinessException(
                        "no active form", org.springframework.http.HttpStatus.NOT_FOUND, "NO_ACTIVE_LOA_FORM"));

        LoaStatusResponse result =
                loaService.adminReplaceLoa(7L, 42L, FileType.LOA_FINAL, pdfFile(), "Re-upload corrected final LoA");

        assertThat(result).isNotNull();
        // 핵심 보장 — 기존 파일 삭제 호출이 절대 없어야 한다(append-only).
        verify(fileRepository, never()).delete(any());
        // 새 파일은 저장되어야 한다.
        verify(fileRepository).save(any(FileEntity.class));
        // LOA_FINAL 교체는 loaStage 를 FINAL_UPLOADED 로 진전시킨다.
        verify(application).markLoaFinalUploaded();
    }

    @Test
    void adminReplace_blankReason_rejected() {
        assertThatThrownBy(() ->
                loaService.adminReplaceLoa(7L, 42L, FileType.OWNER_AUTH_LETTER, pdfFile(), "   "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "LOA_REASON_REQUIRED");

        // 사유 누락 시 어떤 파일도 저장/삭제되지 않는다.
        verify(fileRepository, never()).save(any());
        verify(fileRepository, never()).delete(any());
    }

    @Test
    void adminReplace_invalidFileType_rejected() {
        assertThatThrownBy(() ->
                loaService.adminReplaceLoa(7L, 42L, FileType.LICENSE_PDF, pdfFile(), "any reason"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_LOA_FILE_TYPE");

        verify(fileRepository, never()).save(any());
        verify(fileRepository, never()).delete(any());
    }
}
