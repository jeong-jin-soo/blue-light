package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.admin.LoaFormTemplateService;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.MimeTypeValidator;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bluelight.backend.api.notification.orchestrator.NotificationDispatchEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LOA 비즈니스 로직 오케스트레이션 서비스
 * - 교환(exchange) 모델: send-form / applicant-upload / final-upload + 상태 조회
 * - FileStorageService를 통해 파일 저장 (Local/S3 무관)
 * <p>레거시 generate/sign/upload-signature 모델은 제거됨.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoaService {

    private final ApplicationRepository applicationRepository;
    private final FileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;
    // 알림 오케스트레이터 경로(A-57 LoA form sent 등) — 레거시 직접발송 대체.
    private final ApplicationEventPublisher eventPublisher;
    // 교환 모델 (PR3b) — active LoA 폼 소비 (설정 우선 원칙).
    private final LoaFormTemplateService loaFormTemplateService;

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
            if (applicant == null || applicant.getUserSeq() == null) {
                return;
            }
            Long seq = application.getApplicationSeq();
            // 오케스트레이터(인앱+이메일) + 템플릿 A-57 — 하드코딩 직접발송 대체 (설정 우선 원칙).
            eventPublisher.publishEvent(new NotificationDispatchEvent(
                    "LOA_FORM_SENT", applicant.getUserSeq(), "APPLICATION", seq, "A-57",
                    java.util.Map.of(
                            "applicantName", applicant.getFullName() != null ? applicant.getFullName() : "",
                            "publicCode", String.valueOf(seq),
                            "ctaUrl", "/applications/" + seq)));
        } catch (RuntimeException e) {
            // 알림 실패는 흐름을 막지 않는다 (다른 알림 메서드와 동일 정책).
            log.warn("Failed to dispatch LoA form-sent notification: applicationSeq={}",
                    application.getApplicationSeq(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Admin 교환 패널 (Part B) — LoA 파일 등록/교체
    //  기존 파일을 삭제하지 않고 보관(append-only) + 사유를 감사에 기록한다.
    // ══════════════════════════════════════════════════════════════════

    /**
     * ADMIN/SYSTEM_ADMIN 이 LoA 파일(신청자 서명본 또는 LEW 최종본)을 등록/교체한다.
     *
     * <p>교환 모델 메서드(applicant-upload / final-upload)와 달리, <b>기존 동일 타입 파일을
     * 절대 삭제하지 않고 보관</b>한다. 새 {@link FileEntity}만 추가되며 {@code buildStatus}는
     * 항상 fileSeq 최댓값(=최신본)을 노출한다. 사유({@code reason})는 필수이며 감사 로그에 남는다.</p>
     */
    @Transactional
    public LoaStatusResponse adminReplaceLoa(Long adminSeq, Long applicationSeq,
                                             FileType fileType, MultipartFile file, String reason) {
        // 1. 허용 파일 타입 검증 — LoA 교환에 쓰이는 2종만.
        if (fileType != FileType.OWNER_AUTH_LETTER && fileType != FileType.LOA_FINAL) {
            throw new BusinessException(
                    "Only OWNER_AUTH_LETTER or LOA_FINAL can be replaced via the admin LoA panel.",
                    HttpStatus.BAD_REQUEST, "INVALID_LOA_FILE_TYPE");
        }

        // 2. 사유 필수 — 감사 무결성.
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(
                    "A reason is required when replacing a LoA file.",
                    HttpStatus.BAD_REQUEST, "LOA_REASON_REQUIRED");
        }

        Application application = findApplicationOrThrow(applicationSeq);

        // 3. MIME/크기 검증 — 교환 메서드와 동일 정책.
        MimeTypeValidator.validate(file, LOA_UPLOAD_MIME);
        MimeTypeValidator.validateSize(file, LOA_UPLOAD_MAX_MB);

        // 4. 파일 저장 — 기존 파일은 보관(delete 호출 없음). 새 FileEntity만 추가.
        String subDirectory = "applications/" + applicationSeq;
        String storedPath = fileStorageService.store(file, subDirectory);

        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(fileType)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();
        FileEntity saved = fileRepository.save(fileEntity);

        // 5. loaStage 진전 — 최종본이면 FINAL_UPLOADED, 서명본이면 APPLICANT_UPLOADED.
        if (fileType == FileType.LOA_FINAL) {
            application.markLoaFinalUploaded();
        } else {
            application.markLoaApplicantUploaded();
        }

        // 6. 감사 — 사유를 description + metadata 양쪽에 남긴다.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fileType", fileType.name());
        metadata.put("reason", reason);
        metadata.put("fileSeq", saved.getFileSeq());
        auditLogService.logAsync(
                adminSeq, AuditAction.LOA_ADMIN_REPLACED, AuditCategory.ADMIN,
                "Application", String.valueOf(applicationSeq),
                "Admin replaced LoA file (" + fileType + "). Reason: " + reason,
                null, metadata, null, null,
                "POST", "/api/admin/applications/" + applicationSeq + "/loa/admin-replace", 200);

        log.info("LoA admin replace: applicationSeq={}, adminSeq={}, fileType={}, fileSeq={}",
                applicationSeq, adminSeq, fileType, saved.getFileSeq());

        return buildStatus(application);
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

        // 보관(soft-retain) 정책으로 동일 타입 파일이 여러 개일 수 있으므로
        // 리스트 순서가 아닌 fileSeq 최댓값(=최신본)을 선택한다.
        Long applicantFileSeq = applicantFiles.stream()
                .max(Comparator.comparing(FileEntity::getFileSeq))
                .map(FileEntity::getFileSeq).orElse(null);
        Long finalFileSeq = finalFiles.stream()
                .max(Comparator.comparing(FileEntity::getFileSeq))
                .map(FileEntity::getFileSeq).orElse(null);

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
