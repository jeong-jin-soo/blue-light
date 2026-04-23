package com.bluelight.backend.api.expiredlicenseorder;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrder;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrderRepository;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Expired License 주문 파일 업로드 컨트롤러.
 * <p>참고 문서(supporting documents) 는 파일당 최대 20MB, 주문당 최대 10개까지 업로드 가능.
 * 파일 확장자/MIME 제한 없음 (임의 파일 허용).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ExpiredLicenseOrderFileController {

    /** 신청자 supporting document 최대 업로드 개수 */
    public static final int MAX_SUPPORTING_DOCS_PER_ORDER = 10;
    /** 신청자 supporting document 파일당 최대 크기 (20MB) */
    public static final long MAX_SUPPORTING_DOC_SIZE = 20L * 1024 * 1024;
    /** 매니저 visit report 파일당 최대 크기 (10MB, LEW 와 동일) */
    public static final long MAX_MANAGER_FILE_SIZE = 10L * 1024 * 1024;

    private final FileStorageService fileStorageService;
    private final FileRepository fileRepository;
    private final ExpiredLicenseOrderRepository expiredLicenseOrderRepository;

    /**
     * 신청자 — 참고 문서 업로드 (1회 호출당 1 파일, 누적 10개까지).
     * <p>fileType 은 EXPIRED_LICENSE_SUPPORTING_DOC 로 강제. 포맷/MIME 제한 없음.
     */
    @PostMapping("/api/expired-license-orders/{orderId}/files")
    @Transactional
    public ResponseEntity<ExpiredLicenseOrderFileResponse> uploadApplicantFile(
            Authentication authentication,
            @PathVariable Long orderId,
            @RequestParam("file") MultipartFile file) {

        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Expired License 참고 문서 업로드: userSeq={}, orderId={}, filename={}",
                userSeq, orderId, file.getOriginalFilename());

        ExpiredLicenseOrder order = findOrderOrThrow(orderId);

        if (!order.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException(
                    "You do not have permission to upload files for this order",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        validateSupportingDocFile(file);

        long currentCount = fileRepository
                .findByExpiredLicenseOrderExpiredLicenseOrderSeqAndFileType(
                        order.getExpiredLicenseOrderSeq(), FileType.EXPIRED_LICENSE_SUPPORTING_DOC)
                .size();
        if (currentCount >= MAX_SUPPORTING_DOCS_PER_ORDER) {
            throw new BusinessException(
                    "A maximum of " + MAX_SUPPORTING_DOCS_PER_ORDER + " supporting documents can be uploaded per order",
                    HttpStatus.BAD_REQUEST, "TOO_MANY_FILES");
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(storeSupportingDocument(order, file));
    }

    /**
     * 신청자 — 업로드한 참고 문서 삭제 (본인 소유 주문에 한함).
     */
    @DeleteMapping("/api/expired-license-orders/{orderId}/files/{fileSeq}")
    @Transactional
    public ResponseEntity<Void> deleteApplicantFile(
            Authentication authentication,
            @PathVariable Long orderId,
            @PathVariable Long fileSeq) {

        Long userSeq = (Long) authentication.getPrincipal();
        ExpiredLicenseOrder order = findOrderOrThrow(orderId);

        if (!order.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException(
                    "You do not have permission to delete files for this order",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        FileEntity fileEntity = fileRepository.findById(fileSeq)
                .orElseThrow(() -> new BusinessException(
                        "File not found", HttpStatus.NOT_FOUND, "FILE_NOT_FOUND"));

        if (fileEntity.getExpiredLicenseOrder() == null
                || !fileEntity.getExpiredLicenseOrder().getExpiredLicenseOrderSeq().equals(orderId)) {
            throw new BusinessException(
                    "File does not belong to this order", HttpStatus.BAD_REQUEST, "FILE_MISMATCH");
        }
        if (fileEntity.getFileType() != FileType.EXPIRED_LICENSE_SUPPORTING_DOC) {
            throw new BusinessException(
                    "Only supporting documents can be deleted by applicants",
                    HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE");
        }

        fileEntity.softDelete();
        fileRepository.save(fileEntity);
        log.info("Expired License 참고 문서 삭제: orderId={}, fileSeq={}", orderId, fileSeq);
        return ResponseEntity.noContent().build();
    }

    /**
     * 매니저 — 임의 fileType 업로드 (visit report 등).
     */
    @PostMapping("/api/expired-license-manager/orders/{orderId}/files")
    @PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
    @Transactional
    public ResponseEntity<ExpiredLicenseOrderFileResponse> uploadManagerFile(
            @PathVariable Long orderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {

        log.info("Expired License Manager 파일 업로드: orderId={}, fileType={}, filename={}",
                orderId, fileType, file.getOriginalFilename());
        ExpiredLicenseOrder order = findOrderOrThrow(orderId);
        validateManagerFile(file);
        FileType parsedFileType = parseFileType(fileType);

        String subDirectory = "expired-license-orders/" + order.getExpiredLicenseOrderSeq();
        String storedPath = fileStorageService.store(file, subDirectory);

        FileEntity fileEntity = FileEntity.builder()
                .expiredLicenseOrder(order)
                .fileType(parsedFileType)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();

        FileEntity savedFile = fileRepository.save(fileEntity);
        log.info("Expired License 매니저 파일 저장 완료: fileSeq={}, orderId={}, type={}",
                savedFile.getFileSeq(), order.getExpiredLicenseOrderSeq(), parsedFileType);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExpiredLicenseOrderFileResponse.from(savedFile));
    }

    private ExpiredLicenseOrderFileResponse storeSupportingDocument(ExpiredLicenseOrder order, MultipartFile file) {
        String subDirectory = "expired-license-orders/" + order.getExpiredLicenseOrderSeq() + "/supporting-docs";
        String storedPath = fileStorageService.store(file, subDirectory);

        FileEntity fileEntity = FileEntity.builder()
                .expiredLicenseOrder(order)
                .fileType(FileType.EXPIRED_LICENSE_SUPPORTING_DOC)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();

        FileEntity savedFile = fileRepository.save(fileEntity);
        log.info("Expired License 참고 문서 저장 완료: fileSeq={}, orderId={}",
                savedFile.getFileSeq(), order.getExpiredLicenseOrderSeq());
        return ExpiredLicenseOrderFileResponse.from(savedFile);
    }

    private void validateSupportingDocFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("File is required", HttpStatus.BAD_REQUEST, "FILE_REQUIRED");
        }
        if (file.getSize() > MAX_SUPPORTING_DOC_SIZE) {
            throw new BusinessException(
                    "File size must not exceed 20MB", HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE");
        }
    }

    private void validateManagerFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("File is required", HttpStatus.BAD_REQUEST, "FILE_REQUIRED");
        }
        if (file.getSize() > MAX_MANAGER_FILE_SIZE) {
            throw new BusinessException(
                    "File size must not exceed 10MB", HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE");
        }
    }

    private FileType parseFileType(String fileType) {
        try {
            return FileType.valueOf(fileType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid file type: " + fileType, HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE");
        }
    }

    private ExpiredLicenseOrder findOrderOrThrow(Long orderId) {
        return expiredLicenseOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(
                        "Expired License order not found", HttpStatus.NOT_FOUND,
                        "EXPIRED_LICENSE_ORDER_NOT_FOUND"));
    }

    @Getter
    @Builder
    public static class ExpiredLicenseOrderFileResponse {
        private Long fileSeq;
        private String fileType;
        private String originalFilename;
        private Long fileSize;
        private LocalDateTime uploadedAt;

        public static ExpiredLicenseOrderFileResponse from(FileEntity entity) {
            return ExpiredLicenseOrderFileResponse.builder()
                    .fileSeq(entity.getFileSeq())
                    .fileType(entity.getFileType().name())
                    .originalFilename(entity.getOriginalFilename())
                    .fileSize(entity.getFileSize())
                    .uploadedAt(entity.getUploadedAt())
                    .build();
        }
    }
}
