package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.application.LoaSignatureSource;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LoaService.uploadSignatureByManager 단위 테스트 (★ Kaki Concierge v1.5 Phase 1 PR#6 Stage A).
 * <p>
 * 경로 A — Manager 대리 서명 업로드의 권한/상태/부작용을 검증.
 */
@DisplayName("LoaService.uploadSignatureByManager - PR#6 Stage A")
class LoaServiceUploadSignatureTest {

    // ── 유효 PNG 매직바이트 (8 bytes) + dummy data — MimeTypeValidator 통과 ──
    private static final byte[] PNG_MAGIC = new byte[]{
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D
    };

    private ApplicationRepository applicationRepository;
    private FileRepository fileRepository;
    private LoaGenerationService loaGenerationService;
    private FileStorageService fileStorageService;
    private AuditLogService auditLogService;
    private UserRepository userRepository;
    private ConciergeRequestRepository conciergeRequestRepository;
    private EmailService emailService;
    private LoaService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        fileRepository = mock(FileRepository.class);
        loaGenerationService = mock(LoaGenerationService.class);
        fileStorageService = mock(FileStorageService.class);
        auditLogService = mock(AuditLogService.class);
        userRepository = mock(UserRepository.class);
        conciergeRequestRepository = mock(ConciergeRequestRepository.class);
        emailService = mock(EmailService.class);

        service = new LoaService(
            applicationRepository, fileRepository, loaGenerationService,
            fileStorageService, auditLogService,
            userRepository, conciergeRequestRepository, emailService);

        // 기본 stubs
        when(fileStorageService.store(any(MultipartFile.class), anyString()))
            .thenReturn("applications/42/sig.png");
        when(loaGenerationService.embedSignatureIntoPdf(anyString(), anyString(), any()))
            .thenReturn("applications/42/LOA_SIGNED.pdf");
        Resource emptyResource = new ByteArrayResource(new byte[0]);
        when(fileStorageService.loadAsResource(anyString())).thenReturn(emptyResource);
    }

    // ── 팩토리 ──

    private User makeUser(long seq, UserRole role) {
        User u = User.builder()
            .email(role.name().toLowerCase() + seq + "@y.com").password("h")
            .firstName("F" + seq).lastName("L")
            .role(role).status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private Application makeApplication(long seq, User owner, Long viaConciergeSeq) {
        Application app = Application.builder()
            .user(owner)
            .address("1 Test Road")
            .postalCode("111111")
            .selectedKva(45)
            .quoteAmount(new java.math.BigDecimal("650.00"))
            .applicationType(ApplicationType.NEW)
            .viaConciergeRequestSeq(viaConciergeSeq)
            .build();
        ReflectionTestUtils.setField(app, "applicationSeq", seq);
        return app;
    }

    private ConciergeRequest makeConciergeRequest(long seq, User applicant, User manager) {
        LocalDateTime now = LocalDateTime.now();
        ConciergeRequest cr = ConciergeRequest.builder()
            .publicCode("C-2026-0" + seq)
            .submitterName("S").submitterEmail("s@y.com").submitterPhone("+65")
            .applicantUser(applicant)
            .pdpaConsentAt(now).termsConsentAt(now)
            .signupConsentAt(now).delegationConsentAt(now)
            .build();
        ReflectionTestUtils.setField(cr, "conciergeRequestSeq", seq);
        if (manager != null) {
            cr.assignManager(manager);
            // 추가로 markContacted → linkApplication → requestLoaSign까지 전이하여
            // AWAITING_APPLICANT_LOA_SIGN 상태로 만듦
            cr.markContacted();
            cr.linkApplication(seq * 10);
            cr.requestLoaSign();
        }
        return cr;
    }

    private FileEntity makeLoaFile(long fileSeq, Application application) {
        FileEntity f = FileEntity.builder()
            .application(application)
            .fileType(FileType.OWNER_AUTH_LETTER)
            .fileUrl("applications/42/LOA.pdf")
            .originalFilename("LOA_42.pdf")
            .fileSize(1024L)
            .build();
        ReflectionTestUtils.setField(f, "fileSeq", fileSeq);
        return f;
    }

    private MultipartFile samplePngSignature() {
        return new MockMultipartFile(
            "signature", "sig.png", "image/png", PNG_MAGIC);
    }

    // ────────────────────────────────────────────────────────────
    // 정상 경로 — CONCIERGE_MANAGER 본인 담당
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CONCIERGE_MANAGER 본인 담당 → 성공: MANAGER_UPLOAD source + uploadedBy + 연결된 ConciergeRequest AWAITING→AWAITING_LICENCE_PAYMENT 전이 + N5 이메일")
    void upload_managerOnAssigned_success() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER);
        User applicant = makeUser(20L, UserRole.APPLICANT);
        Application app = makeApplication(42L, applicant, 100L);
        ConciergeRequest cr = makeConciergeRequest(100L, applicant, manager);
        // cr.status = AWAITING_APPLICANT_LOA_SIGN

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(app));
        when(conciergeRequestRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(fileRepository.findByApplicationApplicationSeqAndFileType(42L, FileType.OWNER_AUTH_LETTER))
            .thenReturn(List.of(makeLoaFile(777L, app)));

        FileResponse result = service.uploadSignatureByManager(
            10L, 42L, samplePngSignature(), "email receipt", null);

        assertThat(result).isNotNull();
        // Application 서명 출처 + 업로더 기록
        assertThat(app.getLoaSignatureSource()).isEqualTo(LoaSignatureSource.MANAGER_UPLOAD);
        assertThat(app.getLoaSignatureSourceMemo()).isEqualTo("email receipt");
        assertThat(app.getLoaSignatureUploadedBy()).isSameAs(manager);
        assertThat(app.getLoaSignatureUrl()).isNotNull();
        // ConciergeRequest 자동 전이
        assertThat(cr.getStatus())
            .isEqualTo(com.bluelight.backend.domain.concierge.ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT);
        // 감사 로그
        verify(auditLogService).log(
            eq(10L), anyString(), anyString(),
            eq(com.bluelight.backend.domain.audit.AuditAction.LOA_SIGNATURE_UPLOADED_BY_MANAGER),
            any(), eq("application"), eq("42"), anyString(),
            any(), any(), any(), any(), anyString(), anyString(), eq(201));
        // 트랜잭션 컨텍스트 없음 → 즉시 이메일 발송
        verify(emailService).sendConciergeLoaUploadConfirmEmail(
            eq(applicant.getEmail()), anyString(), anyString(), eq(42L), eq("email receipt"));
    }

    // ────────────────────────────────────────────────────────────
    // 권한 거부
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LEW 호출 → 403 FORBIDDEN")
    void upload_lew_forbidden() {
        User lew = makeUser(30L, UserRole.LEW);
        when(userRepository.findById(30L)).thenReturn(Optional.of(lew));

        assertThatThrownBy(() -> service.uploadSignatureByManager(
            30L, 42L, samplePngSignature(), null, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(be.getCode()).isEqualTo("FORBIDDEN");
            });
        verify(fileStorageService, never()).store(any(), anyString());
    }

    @Test
    @DisplayName("APPLICANT 호출 → 403 FORBIDDEN")
    void upload_applicant_forbidden() {
        User app = makeUser(40L, UserRole.APPLICANT);
        when(userRepository.findById(40L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.uploadSignatureByManager(
            40L, 42L, samplePngSignature(), null, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("CONCIERGE_MANAGER가 타 담당 신청서 업로드 → 403 CONCIERGE_NOT_ASSIGNED")
    void upload_managerOnOthersAssignment_forbidden() {
        User actor = makeUser(10L, UserRole.CONCIERGE_MANAGER);
        User otherManager = makeUser(11L, UserRole.CONCIERGE_MANAGER);
        User applicant = makeUser(20L, UserRole.APPLICANT);
        Application app = makeApplication(42L, applicant, 100L);
        ConciergeRequest cr = makeConciergeRequest(100L, applicant, otherManager);

        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(app));
        when(conciergeRequestRepository.findById(100L)).thenReturn(Optional.of(cr));

        assertThatThrownBy(() -> service.uploadSignatureByManager(
            10L, 42L, samplePngSignature(), null, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(be.getCode()).isEqualTo("CONCIERGE_NOT_ASSIGNED");
            });
    }

    @Test
    @DisplayName("CONCIERGE_MANAGER가 viaConcierge=null 신청서 업로드 → 403 NOT_VIA_CONCIERGE")
    void upload_managerOnNonConciergeApp_forbidden() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER);
        User applicant = makeUser(20L, UserRole.APPLICANT);
        Application app = makeApplication(42L, applicant, null); // viaConcierge=null

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.uploadSignatureByManager(
            10L, 42L, samplePngSignature(), null, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(be.getCode()).isEqualTo("NOT_VIA_CONCIERGE");
            });
    }

    @Test
    @DisplayName("ADMIN은 viaConcierge=null이어도 업로드 허용 (운영 우회)")
    void upload_adminOnNonConciergeApp_allowed() {
        User admin = makeUser(1L, UserRole.ADMIN);
        User applicant = makeUser(20L, UserRole.APPLICANT);
        Application app = makeApplication(42L, applicant, null);

        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(app));
        when(fileRepository.findByApplicationApplicationSeqAndFileType(42L, FileType.OWNER_AUTH_LETTER))
            .thenReturn(List.of(makeLoaFile(777L, app)));

        FileResponse result = service.uploadSignatureByManager(
            1L, 42L, samplePngSignature(), null, null);

        assertThat(result).isNotNull();
        assertThat(app.getLoaSignatureSource()).isEqualTo(LoaSignatureSource.MANAGER_UPLOAD);
        assertThat(app.getLoaSignatureUploadedBy()).isSameAs(admin);
    }

    // ────────────────────────────────────────────────────────────
    // 상태/리소스 검증
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("이미 서명됨 → 400 LOA_ALREADY_SIGNED")
    void upload_alreadySigned_rejected() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER);
        User applicant = makeUser(20L, UserRole.APPLICANT);
        Application app = makeApplication(42L, applicant, 100L);
        app.registerLoaSignature("existing/sig.png");
        ConciergeRequest cr = makeConciergeRequest(100L, applicant, manager);

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(app));
        when(conciergeRequestRepository.findById(100L)).thenReturn(Optional.of(cr));

        assertThatThrownBy(() -> service.uploadSignatureByManager(
            10L, 42L, samplePngSignature(), null, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(be.getCode()).isEqualTo("LOA_ALREADY_SIGNED");
            });
    }

    @Test
    @DisplayName("LOA PDF 미생성 → 400 LOA_NOT_FOUND")
    void upload_loaNotGenerated_rejected() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER);
        User applicant = makeUser(20L, UserRole.APPLICANT);
        Application app = makeApplication(42L, applicant, 100L);
        ConciergeRequest cr = makeConciergeRequest(100L, applicant, manager);

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(app));
        when(conciergeRequestRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(fileRepository.findByApplicationApplicationSeqAndFileType(42L, FileType.OWNER_AUTH_LETTER))
            .thenReturn(List.of()); // 빈 목록

        assertThatThrownBy(() -> service.uploadSignatureByManager(
            10L, 42L, samplePngSignature(), null, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("LOA_NOT_FOUND"));
    }

    @Test
    @DisplayName("잘못된 MIME(application/pdf) → MimeTypeValidator BusinessException")
    void upload_invalidMime_rejected() {
        // MimeTypeValidator는 파일 조회 전 먼저 실행되므로 User/Application stubs 불필요하지만
        // 안전하게 stub 설정 (권한 검증 도달 전 실패 예상)
        MultipartFile pdf = new MockMultipartFile(
            "signature", "sig.pdf", "application/pdf", "not-an-image".getBytes());

        assertThatThrownBy(() -> service.uploadSignatureByManager(
            10L, 42L, pdf, null, null))
            .isInstanceOf(BusinessException.class);
        verify(fileStorageService, never()).store(any(), anyString());
    }

    // ────────────────────────────────────────────────────────────
    // ConciergeRequest 전이 케이스
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("연결된 ConciergeRequest가 AWAITING_APPLICANT_LOA_SIGN 아닌 상태면 전이 안 함")
    void upload_notAwaitingState_noTransition() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER);
        User applicant = makeUser(20L, UserRole.APPLICANT);
        Application app = makeApplication(42L, applicant, 100L);
        ConciergeRequest cr = makeConciergeRequest(100L, applicant, manager);
        // AWAITING_APPLICANT_LOA_SIGN 상태 → markLoaSigned로 AWAITING_LICENCE_PAYMENT로 이미 넘어갔다고 가정
        cr.markLoaSigned();
        var priorStatus = cr.getStatus(); // AWAITING_LICENCE_PAYMENT

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(app));
        when(conciergeRequestRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(fileRepository.findByApplicationApplicationSeqAndFileType(42L, FileType.OWNER_AUTH_LETTER))
            .thenReturn(List.of(makeLoaFile(777L, app)));

        service.uploadSignatureByManager(10L, 42L, samplePngSignature(), null, null);

        // 상태는 그대로 유지 (markLoaSigned가 다시 호출되지 않음)
        assertThat(cr.getStatus()).isEqualTo(priorStatus);
    }

    @Test
    @DisplayName("HttpServletRequest 주어지면 IP/UA 감사 로그에 기록")
    void upload_withHttpRequest_recordsIpUa() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER);
        User applicant = makeUser(20L, UserRole.APPLICANT);
        Application app = makeApplication(42L, applicant, 100L);
        ConciergeRequest cr = makeConciergeRequest(100L, applicant, manager);

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(applicationRepository.findById(42L)).thenReturn(Optional.of(app));
        when(conciergeRequestRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(fileRepository.findByApplicationApplicationSeqAndFileType(anyLong(), any()))
            .thenReturn(List.of(makeLoaFile(777L, app)));

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");
        when(req.getHeader("User-Agent")).thenReturn("MgrUA");

        service.uploadSignatureByManager(10L, 42L, samplePngSignature(), "memo", req);

        verify(auditLogService).log(
            eq(10L), anyString(), anyString(),
            eq(com.bluelight.backend.domain.audit.AuditAction.LOA_SIGNATURE_UPLOADED_BY_MANAGER),
            any(), anyString(), anyString(), anyString(),
            any(), any(),
            eq("203.0.113.5"), eq("MgrUA"),
            anyString(), anyString(), eq(201));
    }
}
