package com.bluelight.backend.api.document;

import com.bluelight.backend.api.document.dto.CreateDocumentRequestsRequest;
import com.bluelight.backend.api.document.dto.DocumentRequestDto;
import com.bluelight.backend.api.document.dto.DocumentRequestItemRequest;
import com.bluelight.backend.api.document.dto.VoluntaryUploadResponse;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.api.notification.NotificationService;
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
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** 활성 요청 상태 집합 — 리밋/중복 검사에 사용 */
    private static final Set<DocumentRequestStatus> ACTIVE_STATUSES =
            EnumSet.of(DocumentRequestStatus.REQUESTED,
                       DocumentRequestStatus.UPLOADED,
                       DocumentRequestStatus.REJECTED);

    /** 중복 감지 상태 집합 (AC-R5) — REQUESTED/UPLOADED */
    private static final Set<DocumentRequestStatus> DUPLICATE_DETECT_STATUSES =
            EnumSet.of(DocumentRequestStatus.REQUESTED, DocumentRequestStatus.UPLOADED);

    /** Application 당 active request 소프트 리밋 (LEW 전용, ADMIN 우회) */
    private static final int ACTIVE_REQUEST_SOFT_LIMIT = 10;

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
    // Phase 3 — LEW 배치 요청 생성
    // ----------------------------------------------------------------------

    /**
     * LEW(또는 ADMIN)가 신청자에게 서류 배치 요청을 생성한다.
     *
     * 보안/동시성:
     *   - B-3: Application row 에 SELECT ... FOR UPDATE 비관락을 걸어 active count race 차단
     *   - B-4 성격: 서비스 계층에서 assignedLew 이중 확인
     *
     * 검증 순서:
     *   1) items 비어 있으면 400 ITEMS_EMPTY (컨트롤러 @Valid 가 선차단하지만 이중 가드)
     *   2) application row lock
     *   3) assignedLew/ADMIN 권한 재검증 (B-4)
     *   4) catalog 유효성 + OTHER → customLabel 필수
     *   5) 동일 type active 중복 검사 (AC-R5)
     *   6) LEW 한정 소프트 리밋 10 (ADMIN 우회)
     *   7) 배치 insert + 감사 흔적 + 인앱 알림
     */
    @Transactional
    public List<DocumentRequestDto> createBatch(Long requestorSeq,
                                                String requestorRole,
                                                Long applicationSeq,
                                                CreateDocumentRequestsRequest request) {
        List<DocumentRequestItemRequest> items = request.getItems();
        if (items == null || items.isEmpty()) {
            throw new BusinessException("items must not be empty",
                    HttpStatus.BAD_REQUEST, "ITEMS_EMPTY");
        }

        // (2) FOR UPDATE 락
        Application application = applicationRepository.findByIdForUpdate(applicationSeq)
                .orElseThrow(() -> new BusinessException("Application not found",
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // (3) 권한 재확인 — 소유자는 생성 권한 없음 (ADMIN / assigned LEW 만)
        assertAdminOrAssignedLew(application, requestorSeq, requestorRole);

        User requester = userRepository.findById(requestorSeq)
                .orElseThrow(() -> new BusinessException("Requester not found",
                        HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // (4) 아이템 정규화 + catalog 검증 (배치 전체 롤백 원칙)
        List<ResolvedItem> resolved = new ArrayList<>(items.size());
        for (DocumentRequestItemRequest item : items) {
            DocumentTypeCatalog catalog = catalogService.requireActiveByCode(item.getDocumentTypeCode());
            String customLabel = normalizeCustomLabel(catalog, item.getCustomLabel());
            String lewNote = item.getLewNote() != null ? item.getLewNote().trim() : null;
            if (lewNote != null && lewNote.isEmpty()) lewNote = null;
            resolved.add(new ResolvedItem(catalog, customLabel, lewNote));
        }

        // (5) 중복 검사 — 배치 자체 내부 중복 + DB 기존 active
        for (int i = 0; i < resolved.size(); i++) {
            ResolvedItem a = resolved.get(i);
            for (int j = 0; j < i; j++) {
                ResolvedItem b = resolved.get(j);
                if (a.code().equals(b.code()) && Objects.equals(a.customLabel(), b.customLabel())) {
                    throw new BusinessException(
                            "Duplicate item within batch: " + a.code(),
                            HttpStatus.CONFLICT, "DUPLICATE_ACTIVE_REQUEST");
                }
            }
            List<DocumentRequest> existing = documentRequestRepository
                    .findActiveByApplicationAndType(applicationSeq, a.code(), DUPLICATE_DETECT_STATUSES);
            for (DocumentRequest dr : existing) {
                // OTHER 는 customLabel 까지 비교. 다른 타입은 code 일치만으로 중복
                if (!"OTHER".equals(a.code())
                        || Objects.equals(dr.getCustomLabel(), a.customLabel())) {
                    throw new BusinessException(
                            "Duplicate active request exists for type: " + a.code(),
                            HttpStatus.CONFLICT, "DUPLICATE_ACTIVE_REQUEST");
                }
            }
        }

        // (6) 소프트 리밋 (LEW 만, ADMIN 우회)
        if (!isAdmin(requestorRole)) {
            long currentActive = documentRequestRepository
                    .countByApplicationAndStatusIn(applicationSeq, ACTIVE_STATUSES);
            if (currentActive + items.size() > ACTIVE_REQUEST_SOFT_LIMIT) {
                throw new BusinessException(
                        "Too many active requests (limit " + ACTIVE_REQUEST_SOFT_LIMIT + ")",
                        HttpStatus.CONFLICT, "TOO_MANY_ACTIVE_REQUESTS");
            }
        }

        // (7) 저장
        List<DocumentRequestDto> result = new ArrayList<>(resolved.size());
        for (ResolvedItem item : resolved) {
            DocumentRequest dr = DocumentRequest.forLewRequest(
                    application,
                    item.code(),
                    item.customLabel(),
                    item.lewNote(),
                    requester);
            DocumentRequest saved = documentRequestRepository.save(dr);
            result.add(DocumentRequestDto.from(saved));
        }

        log.info("DocumentRequest batch created: applicationSeq={}, count={}, requestor={}, role={}",
                applicationSeq, result.size(), requestorSeq, requestorRole);

        // 인앱 알림 (Phase 3 PR#4 에서 이메일 고도화. 여기선 구조적 연결만)
        Long applicantSeq = application.getUser().getUserSeq();
        safeNotify(applicantSeq, NotificationType.DOCUMENT_REQUEST_CREATED,
                "LEW가 서류를 요청했습니다",
                "신청 #" + applicationSeq + " — " + result.size() + "건의 서류 요청이 도착했습니다.",
                "DOCUMENT_REQUEST", applicationSeq);

        return result;
    }

    // ----------------------------------------------------------------------
    // Phase 3 — 신청자 fulfill (REQUESTED/REJECTED → UPLOADED)
    // ----------------------------------------------------------------------

    /**
     * 신청자가 이전에 만들어진 DocumentRequest 에 파일을 첨부한다.
     *
     * - 소유권 검증 (신청자 본인)
     * - path 불일치 시 404 DOCUMENT_REQUEST_NOT_FOUND (AC-P2, 정보 누설 방지)
     * - 상태 전이 가드: REQUESTED/REJECTED/UPLOADED → UPLOADED
     * - 재업로드(REJECTED→UPLOADED) 는 rejection_reason 보존, reviewed_at 초기화
     */
    @Transactional
    public DocumentRequestDto fulfill(Long requestorSeq,
                                      String requestorRole,
                                      Long applicationSeq,
                                      Long docRequestId,
                                      MultipartFile file) {
        DocumentRequest dr = documentRequestRepository.findById(docRequestId)
                .orElseThrow(() -> new BusinessException("Document request not found",
                        HttpStatus.NOT_FOUND, "DOCUMENT_REQUEST_NOT_FOUND"));

        // (AC-P2) path 의 applicationSeq 와 dr.application 불일치 → 404 (정보 누설 방지)
        if (!dr.getApplication().getApplicationSeq().equals(applicationSeq)) {
            throw new BusinessException("Document request not found",
                    HttpStatus.NOT_FOUND, "DOCUMENT_REQUEST_NOT_FOUND");
        }

        Application application = dr.getApplication();
        // 신청자 본인만 fulfill 허용 (owner). ADMIN/LEW 는 파일을 대신 올리지 않는다.
        // 단 기존 validate* 유틸은 owner/admin/lew 모두 통과시키므로 신청자만 허용하도록 별도 검사.
        if (!application.getUser().getUserSeq().equals(requestorSeq)) {
            // LEW/ADMIN 이 타인 신청에 fulfill 시도 — AC-P2 규칙에 따라 404
            throw new BusinessException("Document request not found",
                    HttpStatus.NOT_FOUND, "DOCUMENT_REQUEST_NOT_FOUND");
        }

        // catalog 기반 MIME/size 재검증
        DocumentTypeCatalog catalog = catalogService.requireActiveByCode(dr.getDocumentTypeCode());
        MimeTypeValidator.validateSize(file, catalog.getMaxSizeMb());
        MimeTypeValidator.validate(file, catalog.getAcceptedMime());

        // 파일 저장 — 기존 파일은 보존(AC-AU4): soft delete 하지 않음
        Long previousFileSeq = dr.getFulfilledFile() != null ? dr.getFulfilledFile().getFileSeq() : null;

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

        // 상태 전이 (REQUESTED/REJECTED/UPLOADED → UPLOADED)
        dr.fulfill(savedFile);

        log.info("DocumentRequest fulfilled: drId={}, applicationSeq={}, previousFileSeq={}, newFileSeq={}",
                dr.getId(), applicationSeq, previousFileSeq, savedFile.getFileSeq());

        // LEW 알림 (assignedLew 있을 때만)
        if (application.getAssignedLew() != null) {
            Long lewSeq = application.getAssignedLew().getUserSeq();
            safeNotify(lewSeq, NotificationType.DOCUMENT_REQUEST_FULFILLED,
                    "신청자가 서류를 업로드했습니다",
                    "신청 #" + applicationSeq + " — " + catalog.getCode() + " 검토가 필요합니다.",
                    "DOCUMENT_REQUEST", dr.getId());
        }

        return DocumentRequestDto.from(dr, previousFileSeq);
    }

    // ----------------------------------------------------------------------
    // Phase 3 — LEW 승인 / 반려 / 취소
    // ----------------------------------------------------------------------

    @Transactional
    public DocumentRequestDto approve(Long requestorSeq, String requestorRole, Long docRequestId) {
        DocumentRequest dr = loadForReviewWithAssignedLewCheck(requestorSeq, requestorRole, docRequestId);

        User reviewer = userRepository.findById(requestorSeq)
                .orElseThrow(() -> new BusinessException("Reviewer not found",
                        HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        dr.approve(reviewer);

        log.info("DocumentRequest approved: drId={}, reviewer={}", dr.getId(), requestorSeq);

        Long applicantSeq = dr.getApplication().getUser().getUserSeq();
        safeNotify(applicantSeq, NotificationType.DOCUMENT_REQUEST_APPROVED,
                "서류가 승인되었습니다",
                "신청 #" + dr.getApplication().getApplicationSeq()
                        + " — " + dr.getDocumentTypeCode() + " 승인 완료.",
                "DOCUMENT_REQUEST", dr.getId());

        return DocumentRequestDto.from(dr);
    }

    @Transactional
    public DocumentRequestDto reject(Long requestorSeq, String requestorRole,
                                     Long docRequestId, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.trim().length() < 10) {
            throw new BusinessException("rejectionReason is required (min 10 chars)",
                    HttpStatus.BAD_REQUEST, "REJECTION_REASON_REQUIRED");
        }

        DocumentRequest dr = loadForReviewWithAssignedLewCheck(requestorSeq, requestorRole, docRequestId);

        User reviewer = userRepository.findById(requestorSeq)
                .orElseThrow(() -> new BusinessException("Reviewer not found",
                        HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        dr.reject(reviewer, rejectionReason.trim());

        log.info("DocumentRequest rejected: drId={}, reviewer={}, reasonLen={}",
                dr.getId(), requestorSeq, rejectionReason.length());

        Long applicantSeq = dr.getApplication().getUser().getUserSeq();
        safeNotify(applicantSeq, NotificationType.DOCUMENT_REQUEST_REJECTED,
                "서류가 반려되었습니다",
                "신청 #" + dr.getApplication().getApplicationSeq()
                        + " — " + dr.getDocumentTypeCode() + " 반려. 재업로드가 필요합니다.",
                "DOCUMENT_REQUEST", dr.getId());

        return DocumentRequestDto.from(dr);
    }

    @Transactional
    public DocumentRequestDto cancel(Long requestorSeq, String requestorRole, Long docRequestId) {
        DocumentRequest dr = loadForReviewWithAssignedLewCheck(requestorSeq, requestorRole, docRequestId);

        // 취소는 REQUESTED 에서만 허용 — 상태 머신이 차단하나, 명시적 409 메시지 유지
        if (dr.getStatus() != DocumentRequestStatus.REQUESTED) {
            throw new BusinessException(
                    "Only REQUESTED can be cancelled; current status=" + dr.getStatus(),
                    HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION");
        }
        dr.cancel();

        log.info("DocumentRequest cancelled: drId={}, by={}", dr.getId(), requestorSeq);
        return DocumentRequestDto.from(dr);
    }

    // ----------------------------------------------------------------------
    // 내부 헬퍼 (Phase 3)
    // ----------------------------------------------------------------------

    /**
     * reqId 로 DocumentRequest 를 로드하고 ADMIN/assignedLew 권한을 재확인한다 (B-4).
     * - 미존재 또는 권한 없음은 404 DOCUMENT_REQUEST_NOT_FOUND (정보 누설 방지)
     *   ADMIN/LEW 가 아닌 일반 사용자가 reqId 로 approve/reject/cancel 진입한 경우는
     *   컨트롤러 {@code @PreAuthorize} 에서 1차 차단되지만 다중 role 대응을 위한 안전망.
     */
    private DocumentRequest loadForReviewWithAssignedLewCheck(Long requestorSeq,
                                                              String requestorRole,
                                                              Long docRequestId) {
        DocumentRequest dr = documentRequestRepository.findById(docRequestId)
                .orElseThrow(() -> new BusinessException("Document request not found",
                        HttpStatus.NOT_FOUND, "DOCUMENT_REQUEST_NOT_FOUND"));

        Application application = dr.getApplication();
        try {
            assertAdminOrAssignedLew(application, requestorSeq, requestorRole);
        } catch (BusinessException e) {
            // 정보 누설 방지 — 403 대신 404로 변환 (AC-P2 동일 규칙)
            throw new BusinessException("Document request not found",
                    HttpStatus.NOT_FOUND, "DOCUMENT_REQUEST_NOT_FOUND");
        }
        return dr;
    }

    /**
     * ADMIN/SYSTEM_ADMIN 또는 해당 Application 에 할당된 LEW 만 허용.
     * OwnershipValidator 는 owner 도 통과시키므로 요청 생성/검토에는 직접 구현.
     */
    private void assertAdminOrAssignedLew(Application application,
                                          Long requestorSeq,
                                          String requestorRole) {
        if (isAdmin(requestorRole)) {
            return;
        }
        if ("ROLE_LEW".equals(requestorRole)) {
            if (application.getAssignedLew() != null
                    && application.getAssignedLew().getUserSeq().equals(requestorSeq)) {
                return;
            }
        }
        throw new BusinessException("Access denied",
                HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    private boolean isAdmin(String role) {
        return "ROLE_ADMIN".equals(role) || "ROLE_SYSTEM_ADMIN".equals(role);
    }

    /**
     * catalog 타입에 따라 customLabel 을 정규화한다.
     *   - OTHER: trim 후 빈 값이면 400 CUSTOM_LABEL_REQUIRED
     *   - 그 외: 무시 (null)
     */
    private String normalizeCustomLabel(DocumentTypeCatalog catalog, String raw) {
        String trimmed = raw == null ? null : raw.trim();
        if ("OTHER".equals(catalog.getCode())) {
            if (trimmed == null || trimmed.isEmpty()) {
                throw new BusinessException(
                        "customLabel is required for OTHER document type",
                        HttpStatus.BAD_REQUEST, "CUSTOM_LABEL_REQUIRED");
            }
            return trimmed;
        }
        return null;
    }

    /**
     * 인앱 알림 발송 — 실패는 삼키고 로그만 남긴다.
     * (Phase 3 PR#4 에서 이메일 + 비동기 고도화 예정)
     */
    private void safeNotify(Long recipientSeq, NotificationType type,
                            String title, String message,
                            String referenceType, Long referenceId) {
        if (recipientSeq == null) return;
        try {
            notificationService.createNotification(recipientSeq, type, title, message,
                    referenceType, referenceId);
        } catch (Exception e) {
            log.warn("Notification delivery failed (suppressed): type={}, recipient={}, err={}",
                    type, recipientSeq, e.getMessage());
        }
    }

    /**
     * 배치 생성 아이템 내부 정규화 뷰
     */
    private record ResolvedItem(DocumentTypeCatalog catalog, String customLabel, String lewNote) {
        String code() { return catalog.getCode(); }
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
