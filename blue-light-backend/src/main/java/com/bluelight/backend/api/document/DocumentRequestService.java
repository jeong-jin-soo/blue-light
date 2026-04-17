package com.bluelight.backend.api.document;

import com.bluelight.backend.api.document.dto.DocumentRequestDto;
import com.bluelight.backend.api.document.dto.VoluntaryUploadResponse;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.MimeTypeValidator;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.document.DocumentRequest;
import com.bluelight.backend.domain.document.DocumentRequestRepository;
import com.bluelight.backend.domain.document.DocumentRequestStatus;
import com.bluelight.backend.domain.document.DocumentTypeCatalog;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * DocumentRequest 자발적 업로드/조회/삭제 서비스
 *
 * Phase 2 범위: 신청자 자발적 업로드 흐름만 활성화.
 * Phase 3에서 LEW 요청 생성 + fulfill + 승인/반려 추가 예정.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentRequestService {

    private final ApplicationRepository applicationRepository;
    private final DocumentRequestRepository documentRequestRepository;
    private final DocumentTypeCatalogService catalogService;
    private final FileStorageService fileStorageService;
    private final FileRepository fileRepository;

    /**
     * Document Catalog code → FileEntity.fileType 매핑.
     * Catalog는 운영 메타이고 FileType은 다른 도메인(SLD/Inspection 등)에서도 쓰이므로
     * 동일 enum으로 합치지 않고 명시적 매핑 유지.
     */
    private static final Map<String, FileType> CODE_TO_FILE_TYPE = Map.of(
            "SP_ACCOUNT",         FileType.SP_ACCOUNT_DOC,
            "LOA",                FileType.OWNER_AUTH_LETTER,
            "MAIN_BREAKER_PHOTO", FileType.SITE_PHOTO,
            "SLD_FILE",           FileType.DRAWING_SLD,
            "SKETCH",             FileType.SKETCH_SLD,
            "PAYMENT_RECEIPT",    FileType.PAYMENT_RECEIPT,
            "OTHER",              FileType.SITE_PHOTO // OTHER는 일반 첨부 — SITE_PHOTO를 일반 보관 슬롯으로 재활용
    );

    // ----------------------------------------------------------------------
    // 자발적 업로드 (Phase 2)
    // ----------------------------------------------------------------------

    /**
     * 신청자가 신청서에 자발적으로 서류를 업로드한다.
     *
     * 절차:
     *   1) Application 소유권 검증 (소유자 / 담당 LEW / ADMIN)
     *   2) 카탈로그 유효성 검증 (UNKNOWN_DOCUMENT_TYPE)
     *   3) OTHER 타입이면 customLabel 필수 (CUSTOM_LABEL_REQUIRED)
     *   4) MIME / 크기 검증 (INVALID_FILE_TYPE / FILE_TOO_LARGE)
     *   5) 파일 저장 + FileEntity 생성
     *   6) DocumentRequest(status=UPLOADED, requestedBy=null) 생성
     */
    @Transactional
    public VoluntaryUploadResponse createVoluntaryUpload(
            Long requestorSeq,
            String requestorRole,
            Long applicationSeq,
            String documentTypeCode,
            String customLabel,
            MultipartFile file) {

        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"));

        // (1) Ownership / LEW / Admin
        Long assignedLewSeq = application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq()
                : null;
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                application.getUser().getUserSeq(),
                requestorSeq,
                requestorRole,
                assignedLewSeq);

        // (2) Catalog 검증
        DocumentTypeCatalog catalog = catalogService.requireActiveByCode(documentTypeCode);

        // (3) OTHER → customLabel 필수
        String trimmedLabel = customLabel == null ? null : customLabel.trim();
        if ("OTHER".equals(catalog.getCode())) {
            if (trimmedLabel == null || trimmedLabel.isEmpty()) {
                throw new BusinessException(
                        "customLabel is required for OTHER document type",
                        HttpStatus.BAD_REQUEST,
                        "CUSTOM_LABEL_REQUIRED");
            }
        } else {
            // OTHER 외에는 customLabel 무시 (혼란 방지를 위해 null 정규화)
            trimmedLabel = null;
        }

        // (4) MIME / 크기 검증
        MimeTypeValidator.validateSize(file, catalog.getMaxSizeMb());
        MimeTypeValidator.validate(file, catalog.getAcceptedMime());

        // (5) 파일 저장
        String subDir = "applications/" + applicationSeq;
        String storedPath = fileStorageService.store(file, subDir);

        FileEntity fileEntity = FileEntity.builder()
                .application(application)
                .fileType(CODE_TO_FILE_TYPE.getOrDefault(catalog.getCode(), FileType.SITE_PHOTO))
                .fileUrl(storedPath)
                .originalFilename(sanitizeFilename(file.getOriginalFilename()))
                .fileSize(file.getSize())
                .build();
        FileEntity savedFile = fileRepository.save(fileEntity);

        // (6) DocumentRequest 생성
        DocumentRequest dr = DocumentRequest.forVoluntaryUpload(
                application, catalog.getCode(), trimmedLabel, savedFile);
        DocumentRequest saved = documentRequestRepository.save(dr);

        log.info("Voluntary document uploaded: drId={}, applicationSeq={}, code={}, fileSeq={}, size={}",
                saved.getId(), applicationSeq, catalog.getCode(), savedFile.getFileSeq(), file.getSize());

        return VoluntaryUploadResponse.from(saved);
    }

    // ----------------------------------------------------------------------
    // 조회
    // ----------------------------------------------------------------------

    public List<DocumentRequestDto> listForApplication(
            Long requestorSeq, String requestorRole, Long applicationSeq, DocumentRequestStatus statusFilter) {

        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"));

        Long assignedLewSeq = application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq()
                : null;
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                application.getUser().getUserSeq(), requestorSeq, requestorRole, assignedLewSeq);

        List<DocumentRequest> rows = (statusFilter == null)
                ? documentRequestRepository.findByApplicationApplicationSeqOrderByCreatedAtAsc(applicationSeq)
                : documentRequestRepository.findByApplicationApplicationSeqAndStatusOrderByCreatedAtAsc(
                        applicationSeq, statusFilter);

        return rows.stream().map(DocumentRequestDto::from).toList();
    }

    // ----------------------------------------------------------------------
    // 삭제 (자발적 업로드 본인 삭제)
    // ----------------------------------------------------------------------

    @Transactional
    public void deleteVoluntary(Long requestorSeq, String requestorRole, Long applicationSeq, Long docRequestId) {
        DocumentRequest dr = documentRequestRepository.findById(docRequestId)
                .orElseThrow(() -> new BusinessException(
                        "Document request not found",
                        HttpStatus.NOT_FOUND,
                        "DOCUMENT_REQUEST_NOT_FOUND"));

        if (!dr.getApplication().getApplicationSeq().equals(applicationSeq)) {
            throw new BusinessException(
                    "Document request does not belong to this application",
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_REQUEST_MISMATCH");
        }

        Application application = dr.getApplication();
        Long assignedLewSeq = application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq()
                : null;
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                application.getUser().getUserSeq(), requestorSeq, requestorRole, assignedLewSeq);

        // 자발적 업로드만 삭제 허용 (Phase 3 LEW 요청은 별도 정책)
        if (dr.getStatus() != DocumentRequestStatus.UPLOADED || dr.getRequestedBy() != null) {
            throw new BusinessException(
                    "Only voluntary uploads can be deleted in Phase 2",
                    HttpStatus.CONFLICT,
                    "DOCUMENT_REQUEST_NOT_DELETABLE");
        }

        FileEntity file = dr.getFulfilledFile();
        if (file != null) {
            try {
                fileStorageService.delete(file.getFileUrl());
            } catch (Exception e) {
                log.warn("Failed to delete file from storage (continuing soft delete): fileSeq={}, msg={}",
                        file.getFileSeq(), e.getMessage());
            }
            fileRepository.delete(file);
        }
        documentRequestRepository.delete(dr);

        log.info("Voluntary document deleted: drId={}, applicationSeq={}", docRequestId, applicationSeq);
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    /**
     * 파일명에 포함된 제어문자 / 경로 구분자 / CR-LF 제거 (B-3 §2.2-3)
     */
    static String sanitizeFilename(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        // (1) basename — 경로 traversal 차단 (../../etc/passwd → passwd)
        int slashIdx = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        if (slashIdx >= 0 && slashIdx + 1 < trimmed.length()) {
            trimmed = trimmed.substring(slashIdx + 1);
        }
        // (2) 잔여 제어문자 / CR-LF는 언더스코어로 치환 (HTTP response splitting 방지)
        String cleaned = trimmed.replaceAll("[\\p{Cntrl}\\r\\n]", "_");
        return cleaned.isEmpty() ? null : cleaned;
    }
}
