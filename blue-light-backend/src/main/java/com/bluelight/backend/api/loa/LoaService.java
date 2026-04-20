package com.bluelight.backend.api.loa;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

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

        // 10. afterCommit — N5-UploadConfirm 이메일 (7일 이의 제기 창구)
        final String applicantEmail = application.getUser().getEmail();
        final String applicantName = application.getUser().getFullName();
        final String managerName = manager.getFullName();
        final String memoFinal = memo;
        Runnable sendConfirm = () -> {
            try {
                emailService.sendConciergeLoaUploadConfirmEmail(
                        applicantEmail, applicantName, managerName, applicationSeq, memoFinal);
            } catch (Exception e) {
                log.warn("LOA upload confirm email failed (suppressed): applicationSeq={}, err={}",
                        applicationSeq, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            sendConfirm.run();
                        }
                    });
        } else {
            sendConfirm.run();
        }

        log.info("LOA signature uploaded by manager: applicationSeq={}, managerSeq={}, role={}",
                applicationSeq, managerSeq, role);

        return FileResponse.from(loaFile);
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

        List<FileEntity> loaFiles = fileRepository
                .findByApplicationApplicationSeqAndFileType(applicationSeq, FileType.OWNER_AUTH_LETTER);

        boolean loaGenerated = !loaFiles.isEmpty();
        boolean loaSigned = application.getLoaSignatureUrl() != null;
        Long loaFileSeq = loaGenerated ? loaFiles.get(loaFiles.size() - 1).getFileSeq() : null;

        return LoaStatusResponse.builder()
                .applicationSeq(applicationSeq)
                .loaGenerated(loaGenerated)
                .loaSigned(loaSigned)
                .loaSignedAt(application.getLoaSignedAt())
                .loaFileSeq(loaFileSeq)
                .applicationType(application.getApplicationType().name())
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
