package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.admin.LoaFormTemplateService;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.MimeTypeValidator;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.application.LoaSignatureSource;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bluelight.backend.api.notification.orchestrator.NotificationDispatchEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LOA 비즈니스 로직 오케스트레이션 서비스
 * - PDF 생성, 서명, 상태 조회
 * - FileStorageService를 통해 파일 저장 (Local/S3 무관)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoaService {

    private final ApplicationRepository applicationRepository;
    private final FileRepository fileRepository;
    private final LoaGenerationService loaGenerationService;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;
    // ★ Kaki Concierge v1.5 Phase 1 PR#6 Stage A
    private final UserRepository userRepository;
    private final ConciergeRequestRepository conciergeRequestRepository;
    private final EmailService emailService;
    // PR-W3a: 알림 오케스트레이터 경로(A-36 등) — 레거시 EmailService 직접발송 대체.
    private final ApplicationEventPublisher eventPublisher;
    // 교환 모델 (PR3b) — active LoA 폼 소비 (설정 우선 원칙).
    private final LoaFormTemplateService loaFormTemplateService;

    /**
     * LOA PDF 생성 (Admin/LEW 액션)
     * - 기존 미서명 LOA가 있으면 삭제 후 재생성
     */
    @Transactional
    public FileResponse generateLoa(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);

        // RENEWAL 타입은 LOA 자동 생성 불가 — 신청자가 관계기관에서 받아 업로드
        if (application.getApplicationType() == ApplicationType.RENEWAL) {
            throw new BusinessException(
                    "LOA cannot be auto-generated for renewal applications. Please upload the LOA document.",
                    HttpStatus.BAD_REQUEST, "LOA_RENEWAL_UPLOAD_REQUIRED");
        }

        // 이미 서명된 LOA가 있으면 재생성 불가
        if (application.getLoaSignatureUrl() != null) {
            throw new BusinessException("LOA has already been signed. Cannot regenerate.",
                    HttpStatus.BAD_REQUEST, "LOA_ALREADY_SIGNED");
        }

        // 기존 미서명 LOA 삭제 (재생성 케이스)
        List<FileEntity> existingLoas = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);
        existingLoas.forEach(f -> fileRepository.delete(f));

        // 타입에 따라 PDF 생성 (LoaGenerationService가 FileStorageService로 저장)
        String pdfStoredPath;
        if (application.getApplicationType() == ApplicationType.RENEWAL) {
            pdfStoredPath = loaGenerationService.generateRenewalLoa(application);
        } else {
            pdfStoredPath = loaGenerationService.generateNewLicenceLoa(application);
        }

        // Phase 2 PR#4 (B-5) — LOA 생성 시점의 신청자 신원 스냅샷 기록 (법적 무결성)
        // @Column(updatable=false) + 엔티티 가드로 한 번만 기록됨.
        User applicant = application.getUser();
        boolean snapshotRecorded = application.recordLoaSnapshot(
                applicant.getFullName(),
                applicant.getCompanyName(),
                applicant.getUen(),
                applicant.getDesignation()
        );
        if (snapshotRecorded) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("applicantNameSnapshot", applicant.getFullName());
            after.put("companyNameSnapshot", applicant.getCompanyName());
            after.put("uenSnapshot", applicant.getUen());
            after.put("designationSnapshot", applicant.getDesignation());
            auditLogService.logAsync(
                    applicant.getUserSeq(),
                    AuditAction.LOA_SNAPSHOT_CREATED,
                    AuditCategory.DATA_PROTECTION,
                    "Application", String.valueOf(applicationSeq),
                    "LOA applicant identity snapshot captured at generation time (immutable)",
                    null, after,
                    null, null, "POST", "/api/admin/applications/" + applicationSeq + "/loa/generate", 201
            );
        }

        // 파일 크기: FileStorageService에서 로드하여 확인
        long fileSize = getFileSize(pdfStoredPath);

        // FileEntity 레코드 생성
        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(FileType.OWNER_AUTH_LETTER)
                .fileUrl(pdfStoredPath)
                .originalFilename("LOA_" + applicationSeq + ".pdf")
                .fileSize(fileSize)
                .build();

        FileEntity saved = fileRepository.save(fileEntity);
        log.info("LOA generated: applicationSeq={}, fileSeq={}", applicationSeq, saved.getFileSeq());

        return FileResponse.from(saved);
    }

    /**
     * LOA 전자서명 (Applicant 액션)
     * - 서명 이미지 저장 → PDF에 임베드 → FileEntity 업데이트
     */
    @Transactional
    public FileResponse signLoa(Long userSeq, Long applicationSeq, MultipartFile signatureImage) {
        Application application = findApplicationOrThrow(applicationSeq);

        // 소유권 검증
        if (!application.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        // 이미 서명된 경우
        if (application.getLoaSignatureUrl() != null) {
            throw new BusinessException("LOA has already been signed",
                    HttpStatus.BAD_REQUEST, "LOA_ALREADY_SIGNED");
        }

        // LOA PDF 존재 확인
        List<FileEntity> loaFiles = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);

        if (loaFiles.isEmpty()) {
            throw new BusinessException("LOA has not been generated yet",
                    HttpStatus.BAD_REQUEST, "LOA_NOT_FOUND");
        }

        FileEntity loaFile = loaFiles.get(loaFiles.size() - 1); // 최신 LOA

        // 서명 이미지를 FileStorageService로 저장
        String subDirectory = "applications/" + applicationSeq;
        String signatureRelativePath = fileStorageService.store(signatureImage, subDirectory);

        // PDF에 서명 임베드 (LoaGenerationService가 FileStorageService를 통해 로드/저장)
        String signedPdfPath = loaGenerationService.embedSignatureIntoPdf(
                loaFile.getFileUrl(), signatureRelativePath, application);

        // FileEntity 업데이트 (서명된 PDF로 교체)
        long fileSize = getFileSize(signedPdfPath);
        loaFile.updateFileUrl(signedPdfPath, "LOA_SIGNED_" + applicationSeq + ".pdf", fileSize);

        // Application에 서명 정보 등록
        application.registerLoaSignature(signatureRelativePath);

        log.info("LOA signed: applicationSeq={}, signatureUrl={}", applicationSeq, signatureRelativePath);

        return FileResponse.from(loaFile);
    }

    /**
     * Manager 대리 서명 업로드 (★ Kaki Concierge v1.5 Phase 1 PR#6 Stage A).
     * <p>
     * 경로 A — Concierge Manager가 신청자에게서 직접 받은 서명 파일을 대신 업로드.
     * PRD v1.5 §7.2.1-LOA 3경로 모델 중 MANAGER_UPLOAD 경로.
     * <ul>
     *   <li>권한: CONCIERGE_MANAGER (본인 담당) / ADMIN / SYSTEM_ADMIN</li>
     *   <li>CONCIERGE_MANAGER는 {@code viaConciergeRequestSeq}가 있는 신청서만 업로드 가능,
     *       해당 ConciergeRequest의 assignedManager와 일치해야 함</li>
     *   <li>ADMIN/SYSTEM_ADMIN은 viaConcierge 무관하게 업로드 가능 (운영상 우회)</li>
     *   <li>LEW는 URL 매처 단계에서 차단됨 (Controller에 {@code @PreAuthorize} 명시, AC-15b)</li>
     * </ul>
     * 후속 동작:
     * - {@code Application.recordLoaSignatureSource(MANAGER_UPLOAD, ...)} + uploadedBy 세팅 (PR#1 Stage 3)
     * - 연결된 ConciergeRequest가 AWAITING_APPLICANT_LOA_SIGN 상태면 자동 전이 {@code markLoaSigned()}
     * - 감사 로그 {@code LOA_SIGNATURE_UPLOADED_BY_MANAGER}
     * - afterCommit 훅으로 N5-UploadConfirm 이메일 발송 (7일 이의 제기 창구, AC-22b / O-15)
     * <p>
     * Phase 2 EXIF 제거(ImageSanitizer)는 현재 범위 외 — 업로드 이미지의 메타데이터 제거는 별도 PR.
     */
    @Transactional
    public FileResponse uploadSignatureByManager(
            Long managerSeq, Long applicationSeq, MultipartFile signatureImage,
            String memo, HttpServletRequest httpRequest) {

        // 1. 파일 검증 — PNG/JPEG 최대 2MB (매직바이트 + MIME)
        MimeTypeValidator.validate(signatureImage, "image/png,image/jpeg");
        MimeTypeValidator.validateSize(signatureImage, 2);

        // 2. Manager 조회 + 역할 검증
        User manager = userRepository.findById(managerSeq)
                .orElseThrow(() -> new BusinessException(
                        "Manager not found", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
        UserRole role = manager.getRole();
        if (role != UserRole.CONCIERGE_MANAGER
                && role != UserRole.ADMIN
                && role != UserRole.SYSTEM_ADMIN) {
            throw new BusinessException(
                    "Only Concierge Managers or administrators can upload LOA signatures.",
                    HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        Application application = findApplicationOrThrow(applicationSeq);

        // 3. CONCIERGE_MANAGER 경로별 본인 담당 검증
        ConciergeRequest linkedCr = null;
        if (role == UserRole.CONCIERGE_MANAGER) {
            Long viaSeq = application.getViaConciergeRequestSeq();
            if (viaSeq == null) {
                throw new BusinessException(
                        "This application was not created via concierge service.",
                        HttpStatus.FORBIDDEN, "NOT_VIA_CONCIERGE");
            }
            linkedCr = conciergeRequestRepository.findById(viaSeq)
                    .orElseThrow(() -> new BusinessException(
                            "Concierge request not found",
                            HttpStatus.NOT_FOUND, "NOT_FOUND"));
            if (linkedCr.getAssignedManager() == null
                    || !linkedCr.getAssignedManager().getUserSeq().equals(managerSeq)) {
                throw new BusinessException(
                        "This concierge request is not assigned to you.",
                        HttpStatus.FORBIDDEN, "CONCIERGE_NOT_ASSIGNED");
            }
        } else if (application.getViaConciergeRequestSeq() != null) {
            // ADMIN/SYSTEM_ADMIN도 전이용으로 ConciergeRequest를 로드 (afterCommit + markLoaSigned)
            linkedCr = conciergeRequestRepository.findById(application.getViaConciergeRequestSeq())
                    .orElse(null);
        }

        // 4. 이미 서명된 경우 차단
        if (application.getLoaSignatureUrl() != null) {
            throw new BusinessException("LOA has already been signed",
                    HttpStatus.BAD_REQUEST, "LOA_ALREADY_SIGNED");
        }

        // 5. LOA PDF 존재 확인
        List<FileEntity> loaFiles = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);
        if (loaFiles.isEmpty()) {
            throw new BusinessException("LOA has not been generated yet",
                    HttpStatus.BAD_REQUEST, "LOA_NOT_FOUND");
        }
        FileEntity loaFile = loaFiles.get(loaFiles.size() - 1);

        // 6. 서명 이미지 저장 + PDF 임베드 (기존 signLoa와 동일)
        String subDirectory = "applications/" + applicationSeq;
        String signatureRelativePath = fileStorageService.store(signatureImage, subDirectory);
        String signedPdfPath = loaGenerationService.embedSignatureIntoPdf(
                loaFile.getFileUrl(), signatureRelativePath, application);

        long fileSize = getFileSize(signedPdfPath);
        loaFile.updateFileUrl(signedPdfPath, "LOA_SIGNED_" + applicationSeq + ".pdf", fileSize);

        // 7. Application — 서명 등록 + 출처 기록 (PR#1 Stage 3 도메인 메서드 재사용)
        application.registerLoaSignature(signatureRelativePath);
        application.recordLoaSignatureSource(LoaSignatureSource.MANAGER_UPLOAD, managerSeq, memo);
        application.setLoaSignatureUploadedBy(manager);

        // 8. ConciergeRequest 자동 전이 — APPLICATION_CREATED 단계라면 LOA 서명 요청 단계를
        //    먼저 거쳐 AWAITING_APPLICANT_LOA_SIGN을 채운 뒤 곧바로 markLoaSigned로 진행.
        //    Manager가 수동으로 "Request LOA signing" 버튼을 누르지 않고 바로 업로드하는 경우 대비.
        if (linkedCr != null) {
            if (linkedCr.getStatus() == ConciergeRequestStatus.APPLICATION_CREATED) {
                linkedCr.requestLoaSign();
            }
            if (linkedCr.getStatus() == ConciergeRequestStatus.AWAITING_APPLICANT_LOA_SIGN) {
                linkedCr.markLoaSigned();
            }
        }

        // 9. 감사 로그
        auditLogService.log(
                manager.getUserSeq(), manager.getEmail(), manager.getRole().name(),
                AuditAction.LOA_SIGNATURE_UPLOADED_BY_MANAGER, AuditCategory.APPLICATION,
                "application", applicationSeq.toString(),
                "Manager uploaded LOA signature on behalf of applicant "
                        + application.getUser().getUserSeq()
                        + (memo != null && !memo.isBlank() ? " (memo: " + memo + ")" : ""),
                null, null,
                extractIp(httpRequest), userAgent(httpRequest),
                "POST", "/api/admin/applications/{id}/loa/upload-signature", 201);

        // 10. A-36 (CONCIERGE_LOA_UPLOAD_CONFIRM) — 오케스트레이터 경로.
        //     orchestrator 의 @TransactionalEventListener(AFTER_COMMIT) 가 커밋 후 발송을 보장하므로
        //     수동 TransactionSynchronization 불필요. 채널(현재 EMAIL)·locale·옵트인은 orchestrator 결정.
        Map<String, String> a36 = new LinkedHashMap<>();
        a36.put("applicantName", application.getUser().getFullName());
        a36.put("managerName", manager.getFullName());
        a36.put("publicCode", linkedCr != null ? linkedCr.getPublicCode() : "APP-" + applicationSeq);
        if (memo != null && !memo.isBlank()) {
            a36.put("managerNote", memo);
        }
        a36.put("objectionDeadline",
                LocalDateTime.now().plusDays(7).format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        eventPublisher.publishEvent(new NotificationDispatchEvent(
                "CONCIERGE_LOA_UPLOAD_CONFIRM",
                application.getUser().getUserSeq(),
                "APPLICATION", applicationSeq,
                "A-36", a36));

        log.info("LOA signature uploaded by manager: applicationSeq={}, managerSeq={}, role={}",
                applicationSeq, managerSeq, role);

        return FileResponse.from(loaFile);
    }

    // ══════════════════════════════════════════════════════════════════
    //  교환 모델 (loa-exchange-redesign-spec.md §3.3, PR3b)
    //  send-form (LEW) → applicant-upload (Owner) → final-upload (LEW)
    // ══════════════════════════════════════════════════════════════════

    /** applicant-upload 허용 MIME (오프라인 서명본). */
    private static final String LOA_UPLOAD_MIME = "application/pdf,image/jpeg,image/png";
    /** LoA 파일 크기 상한(MB). */
    private static final int LOA_UPLOAD_MAX_MB = 20;

    /**
     * §3.3 — LEW 가 active LoA 폼을 신청자에게 전달 (NEW 전용).
     * <p>active 폼 버전을 신청에 고정({@code loaFormTemplateSeq}) + {@code loaStage → FORM_SENT} +
     * 신청자 알림. active 폼이 없으면 409 {@code NO_ACTIVE_LOA_FORM}.</p>
     *
     * <p>권한(담당 LEW)은 컨트롤러 {@code @PreAuthorize("@appSec.isAssignedLew(...)")}에서 강제한다.</p>
     */
    @Transactional
    public LoaStatusResponse sendLoaForm(Long lewUserSeq, Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);

        // RENEWAL 은 플랫폼이 폼을 제공하지 않는다 (신청자 지참 첨부 또는 DocumentRequest 경로).
        if (application.getApplicationType() == ApplicationType.RENEWAL) {
            throw new BusinessException(
                    "LoA form is not applicable for renewal applications.",
                    HttpStatus.CONFLICT, "LOA_FORM_NOT_APPLICABLE");
        }

        // PR2 LoaFormTemplateService.getActiveForm()은 active 폼이 없으면 404 NO_ACTIVE_LOA_FORM throw.
        var active = loaFormTemplateService.getActiveForm();

        application.markLoaFormSent(active.getLoaFormTemplateSeq());

        // 신청자 알림 — 레거시 EmailService 직접발송 (오케스트레이터 풀 시드는 PR-W 범위).
        sendFormSentNotification(application);

        auditLogService.logAsync(
                lewUserSeq, AuditAction.LOA_FORM_SENT, AuditCategory.APPLICATION,
                "Application", String.valueOf(applicationSeq),
                "LEW sent active LoA form (template " + active.getLoaFormTemplateSeq()
                        + ") to applicant",
                null, null, null, null,
                "POST", "/api/lew/applications/" + applicationSeq + "/loa/send-form", 200);

        log.info("LoA form sent: applicationSeq={}, lewUserSeq={}, templateSeq={}",
                applicationSeq, lewUserSeq, active.getLoaFormTemplateSeq());

        return buildStatus(application);
    }

    /**
     * §3.3 — 신청자가 오프라인 서명본 업로드.
     * <p>소유자 검증 → MIME/크기 검증 → FileEntity(OWNER_AUTH_LETTER) 저장(기존 최신본 교체) →
     * 신원 스냅샷 최초 기록 → {@code loaStage → APPLICANT_UPLOADED}.</p>
     */
    @Transactional
    public LoaStatusResponse applicantUploadLoa(Long userSeq, String role,
                                                Long applicationSeq, MultipartFile file) {
        Application application = findApplicationOrThrow(applicationSeq);

        // 소유자 검증 (ADMIN 대리 업로드 허용 — 권한표 §5).
        OwnershipValidator.validateOwnerOrAdmin(
                application.getUser().getUserSeq(), userSeq, role);

        MimeTypeValidator.validate(file, LOA_UPLOAD_MIME);
        MimeTypeValidator.validateSize(file, LOA_UPLOAD_MAX_MB);

        // 재업로드 시 기존 OWNER_AUTH_LETTER 최신본 교체 (엣지 §8-2).
        List<FileEntity> existing = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);
        existing.forEach(fileRepository::delete);

        String subDirectory = "applications/" + applicationSeq;
        String storedPath = fileStorageService.store(file, subDirectory);

        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(FileType.OWNER_AUTH_LETTER)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();
        FileEntity saved = fileRepository.save(fileEntity);

        // 신원 스냅샷 최초 기록 (생성→업로드 시점으로 트리거 이동, §2.3).
        User applicant = application.getUser();
        boolean snapshotRecorded = application.recordLoaSnapshot(
                applicant.getFullName(), applicant.getCompanyName(),
                applicant.getUen(), applicant.getDesignation());
        if (snapshotRecorded) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("applicantNameSnapshot", applicant.getFullName());
            after.put("companyNameSnapshot", applicant.getCompanyName());
            after.put("uenSnapshot", applicant.getUen());
            after.put("designationSnapshot", applicant.getDesignation());
            auditLogService.logAsync(
                    applicant.getUserSeq(), AuditAction.LOA_SNAPSHOT_CREATED,
                    AuditCategory.DATA_PROTECTION, "Application", String.valueOf(applicationSeq),
                    "LoA applicant identity snapshot captured at applicant upload time (immutable)",
                    null, after, null, null,
                    "POST", "/api/applications/" + applicationSeq + "/loa/applicant-upload", 200);
        }

        application.markLoaApplicantUploaded();

        auditLogService.logAsync(
                userSeq, AuditAction.LOA_APPLICANT_UPLOADED, AuditCategory.APPLICATION,
                "Application", String.valueOf(applicationSeq),
                "Applicant uploaded signed LoA (fileSeq=" + saved.getFileSeq() + ")",
                null, null, null, null,
                "POST", "/api/applications/" + applicationSeq + "/loa/applicant-upload", 200);

        log.info("LoA applicant upload: applicationSeq={}, userSeq={}, fileSeq={}",
                applicationSeq, userSeq, saved.getFileSeq());

        return buildStatus(application);
    }

    /**
     * §3.3 — LEW 가 보완한 최종본 업로드.
     * <p>FileEntity(LOA_FINAL) 저장(기존 최신본 교체) → {@code loaStage → FINAL_UPLOADED}.</p>
     *
     * <p>권한(담당 LEW)은 컨트롤러 {@code @PreAuthorize}에서 강제한다.</p>
     */
    @Transactional
    public LoaStatusResponse finalUploadLoa(Long lewUserSeq, Long applicationSeq, MultipartFile file) {
        Application application = findApplicationOrThrow(applicationSeq);

        MimeTypeValidator.validate(file, LOA_UPLOAD_MIME);
        MimeTypeValidator.validateSize(file, LOA_UPLOAD_MAX_MB);

        List<FileEntity> existing = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.LOA_FINAL);
        existing.forEach(fileRepository::delete);

        String subDirectory = "applications/" + applicationSeq;
        String storedPath = fileStorageService.store(file, subDirectory);

        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(FileType.LOA_FINAL)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();
        FileEntity saved = fileRepository.save(fileEntity);

        application.markLoaFinalUploaded();

        auditLogService.logAsync(
                lewUserSeq, AuditAction.LOA_FINAL_UPLOADED, AuditCategory.APPLICATION,
                "Application", String.valueOf(applicationSeq),
                "LEW uploaded final LoA (fileSeq=" + saved.getFileSeq() + ")",
                null, null, null, null,
                "POST", "/api/lew/applications/" + applicationSeq + "/loa/final-upload", 200);

        log.info("LoA final upload: applicationSeq={}, lewUserSeq={}, fileSeq={}",
                applicationSeq, lewUserSeq, saved.getFileSeq());

        return buildStatus(application);
    }

    // §3.2 active LoA 폼 메타/다운로드는 PR2 LoaActiveFormController 가 단독 담당
    // (/api/applications/{id}/loa/active-form[/download]). 여기서 중복 제공하지 않는다.
    // 신청별 고정 버전(AC-6) 반영은 후속 — 현재 PR2 컨트롤러는 글로벌 active 를 반환.

    private void sendFormSentNotification(Application application) {
        try {
            User applicant = application.getUser();
            String subject = "[LicenseKaki] LoA form ready for your application #" + application.getApplicationSeq();
            String body = "Dear " + escape(applicant.getFullName()) + ",<br><br>"
                    + "Your assigned LEW has shared the Letter of Appointment (LoA) form for your "
                    + "application. Please download the form, sign it offline, and upload the signed copy "
                    + "in your application page.<br><br>"
                    + "This is an automated notification from LicenseKaki. We will never ask for your "
                    + "password or payment details by email.";
            emailService.sendGenericEmail(applicant.getEmail(), subject, body);
        } catch (Exception e) {
            // 알림 실패는 흐름을 막지 않는다 (다른 알림 메서드와 동일 정책).
            log.warn("Failed to send LoA form-sent notification: applicationSeq={}",
                    application.getApplicationSeq(), e);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String extractIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request != null ? request.getHeader("User-Agent") : null;
    }

    /**
     * LOA 상태 조회
     * - Owner, ADMIN, LEW 모두 접근 가능
     */
    public LoaStatusResponse getLoaStatus(Long userSeq, String role, Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);

        // 접근 권한 검증: Admin 전체 / LEW는 담당 신청서만 / Applicant는 본인 소유만
        Long assignedLewSeq = application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq() : null;
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                application.getUser().getUserSeq(), userSeq, role, assignedLewSeq);

        return buildStatus(application);
    }

    /**
     * 교환 모델 상태 응답 빌드 (§3.3) — loaStage + 파일 seq 2종(applicant/final) + active 폼 메타.
     * 레거시 필드(loaGenerated/loaSigned/loaFileSeq/loaSignedAt)는 하위호환을 위해 함께 채운다.
     */
    private LoaStatusResponse buildStatus(Application application) {
        Long applicationSeq = application.getApplicationSeq();

        List<FileEntity> applicantFiles = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);
        List<FileEntity> finalFiles = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.LOA_FINAL);

        Long applicantFileSeq = applicantFiles.isEmpty()
                ? null : applicantFiles.get(applicantFiles.size() - 1).getFileSeq();
        Long finalFileSeq = finalFiles.isEmpty()
                ? null : finalFiles.get(finalFiles.size() - 1).getFileSeq();

        // active 폼 메타 (NEW 전용). PR2 getActiveForm()은 미설정 시 404 throw → unavailable 처리.
        boolean activeFormAvailable = false;
        String activeFormLabel = null;
        if (application.getApplicationType() != ApplicationType.RENEWAL) {
            try {
                var active = loaFormTemplateService.getActiveForm();
                activeFormAvailable = true;
                activeFormLabel = active.getLabel();
            } catch (BusinessException ignored) {
                // active 폼 미설정 → unavailable
            }
        }

        boolean loaGenerated = applicantFileSeq != null || finalFileSeq != null;
        boolean loaSigned = application.getLoaSignatureUrl() != null;

        return LoaStatusResponse.builder()
                .applicationSeq(applicationSeq)
                .applicationType(application.getApplicationType().name())
                .loaStage(application.getLoaStage() != null ? application.getLoaStage().name() : null)
                .applicantFileSeq(applicantFileSeq)
                .finalFileSeq(finalFileSeq)
                .activeFormAvailable(activeFormAvailable)
                .activeFormLabel(activeFormLabel)
                // 레거시 (하위호환)
                .loaGenerated(loaGenerated)
                .loaSigned(loaSigned)
                .loaSignedAt(application.getLoaSignedAt())
                .loaFileSeq(applicantFileSeq != null ? applicantFileSeq : finalFileSeq)
                .build();
    }

    private Application findApplicationOrThrow(Long applicationSeq) {
        return applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }

    /**
     * FileStorageService에서 파일을 로드하여 크기 확인
     */
    private long getFileSize(String storedPath) {
        try {
            return fileStorageService.loadAsResource(storedPath)
                    .getInputStream().readAllBytes().length;
        } catch (Exception e) {
            log.warn("Failed to determine file size: {}", storedPath, e);
            return 0;
        }
    }
}
