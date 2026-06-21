package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.MimeTypeValidator;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LOA 비즈니스 로직 서비스.
 *
 * <p>LoA 는 두 트랙으로 명확히 구분된다:</p>
 * <ul>
 *   <li><b>신청자 LoA</b> — 자발 첨부 또는 LEW의 Documents 서류요청에 대한 응답.
 *       {@code OWNER_AUTH_LETTER} 파일로 저장되며 {@code loaStage} 와 무관하다(Documents 흐름에서 처리).</li>
 *   <li><b>LEW 최종 LoA</b> — LEW가 보완·확정한 최종본({@code LOA_FINAL}). {@code loaStage → FINAL_UPLOADED}.
 *       EMA 외부 제출본이며 PAID→IN_PROGRESS 게이트의 대상.</li>
 * </ul>
 *
 * <p>본 서비스는 LEW 최종본 업로드(final-upload)·ADMIN 파일 등록/교체(admin-replace)·상태 조회만 담당한다.
 * 신청자 LoA 업로드는 {@code DocumentRequestService}(자발/fulfill)가 담당한다.</p>
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

    /** LoA 파일 허용 MIME. */
    private static final String LOA_UPLOAD_MIME = "application/pdf,image/jpeg,image/png";
    /** LoA 파일 크기 상한(MB). */
    private static final int LOA_UPLOAD_MAX_MB = 20;

    /**
     * LEW 가 보완한 최종본 업로드.
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

    // ══════════════════════════════════════════════════════════════════
    //  Admin 패널 — LoA 파일 등록/교체
    //  기존 파일을 삭제하지 않고 보관(append-only) + 사유를 감사에 기록한다.
    // ══════════════════════════════════════════════════════════════════

    /**
     * ADMIN/SYSTEM_ADMIN 이 LoA 파일(신청자 서명본 또는 LEW 최종본)을 등록/교체한다.
     *
     * <p><b>기존 동일 타입 파일을 절대 삭제하지 않고 보관</b>한다. 새 {@link FileEntity}만 추가되며
     * {@code buildStatus}는 항상 fileSeq 최댓값(=최신본)을 노출한다. 사유({@code reason})는 필수이며
     * 감사 로그에 남는다. {@code LOA_FINAL} 만 {@code loaStage → FINAL_UPLOADED} 로 전이하며,
     * {@code OWNER_AUTH_LETTER}(신청자 서명본)은 단계를 바꾸지 않는다(신청자 LoA 는 Documents 트랙).</p>
     */
    @Transactional
    public LoaStatusResponse adminReplaceLoa(Long adminSeq, Long applicationSeq,
                                             FileType fileType, MultipartFile file, String reason) {
        // 1. 허용 파일 타입 검증 — LoA 에 쓰이는 2종만.
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

        // 3. MIME/크기 검증.
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

        // 5. loaStage 진전 — LEW 최종본만 FINAL_UPLOADED 로 전이(신청자 서명본은 단계 무변경).
        if (fileType == FileType.LOA_FINAL) {
            application.markLoaFinalUploaded();
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
     * 상태 응답 빌드 — loaStage(LEW 최종본 트랙) + 파일 seq 2종(신청자 서명본 / LEW 최종본).
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

        boolean loaGenerated = applicantFileSeq != null || finalFileSeq != null;
        boolean loaSigned = application.getLoaSignatureUrl() != null;

        return LoaStatusResponse.builder()
                .applicationSeq(applicationSeq)
                .applicationType(application.getApplicationType().name())
                .loaStage(application.getLoaStage() != null ? application.getLoaStage().name() : null)
                .applicantFileSeq(applicantFileSeq)
                .finalFileSeq(finalFileSeq)
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
}
