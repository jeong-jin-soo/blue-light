package com.bluelight.backend.api.sldorder;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.sldorder.SldOrder;
import com.bluelight.backend.domain.sldorder.SldOrderRepository;
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

/**
 * SLD 전용 주문 파일 업로드 컨트롤러
 * - 신청자 스케치 업로드: POST /api/sld-orders/{orderId}/files
 * - SLD Manager 파일 업로드: POST /api/sld-manager/orders/{orderId}/files
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SldOrderFileController {

    private final FileStorageService fileStorageService;
    private final FileRepository fileRepository;
    private final SldOrderRepository sldOrderRepository;

    /**
     * 신청자 파일 업로드 (스케치 등)
     * POST /api/sld-orders/{orderId}/files
     */
    @PostMapping("/api/sld-orders/{orderId}/files")
    @Transactional
    public ResponseEntity<SldOrderFileResponse> uploadApplicantFile(
            Authentication authentication,
            @PathVariable Long orderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {

        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 주문 신청자 파일 업로드: userSeq={}, orderId={}, fileType={}, filename={}",
                userSeq, orderId, fileType, file.getOriginalFilename());

        SldOrder order = findOrderOrThrow(orderId);

        // 소유권 검증
        if (!order.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException(
                    "You do not have permission to upload files for this order",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(storeFile(order, file, fileType));
    }

    /**
     * SLD Manager 파일 업로드
     * POST /api/sld-manager/orders/{orderId}/files
     */
    @PostMapping("/api/sld-manager/orders/{orderId}/files")
    @PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
    @Transactional
    public ResponseEntity<SldOrderFileResponse> uploadManagerFile(
            @PathVariable Long orderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {

        log.info("SLD Manager 파일 업로드: orderId={}, fileType={}, filename={}",
                orderId, fileType, file.getOriginalFilename());

        SldOrder order = findOrderOrThrow(orderId);
        return ResponseEntity.status(HttpStatus.CREATED).body(storeFile(order, file, fileType));
    }

    // ── Private helpers ──────────────────────────────────────

    private SldOrderFileResponse storeFile(SldOrder order, MultipartFile file, String fileType) {
        validateFile(file);

        FileType parsedFileType = parseFileType(fileType);
        String subDirectory = "sld-orders/" + order.getSldOrderSeq();
        String storedPath = fileStorageService.store(file, subDirectory);

        FileEntity fileEntity = FileEntity.builder()
                .sldOrder(order)
                .fileType(parsedFileType)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();

        FileEntity savedFile = fileRepository.save(fileEntity);
        log.info("SLD 주문 파일 저장 완료: fileSeq={}, orderId={}, type={}",
                savedFile.getFileSeq(), order.getSldOrderSeq(), parsedFileType);

        return SldOrderFileResponse.from(savedFile);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(
                    "File is required", HttpStatus.BAD_REQUEST, "FILE_REQUIRED");
        }

        // 최대 10MB
        if (file.getSize() > 10 * 1024 * 1024) {
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

    private SldOrder findOrderOrThrow(Long orderId) {
        return sldOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(
                        "SLD order not found", HttpStatus.NOT_FOUND, "SLD_ORDER_NOT_FOUND"));
    }

    // ── Response DTO (inner class) ──────────────────────────

    @Getter
    @Builder
    public static class SldOrderFileResponse {
        private Long fileSeq;
        private String fileType;
        private String originalFilename;
        private Long fileSize;
        private LocalDateTime uploadedAt;

        public static SldOrderFileResponse from(FileEntity entity) {
            return SldOrderFileResponse.builder()
                    .fileSeq(entity.getFileSeq())
                    .fileType(entity.getFileType().name())
                    .originalFilename(entity.getOriginalFilename())
                    .fileSize(entity.getFileSize())
                    .uploadedAt(entity.getUploadedAt())
                    .build();
        }
    }
}
