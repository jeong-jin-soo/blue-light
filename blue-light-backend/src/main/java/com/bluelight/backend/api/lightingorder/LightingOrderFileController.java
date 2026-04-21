package com.bluelight.backend.api.lightingorder;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.lightingorder.LightingOrder;
import com.bluelight.backend.domain.lightingorder.LightingOrderRepository;
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
 * Lighting Layout 주문 파일 업로드 컨트롤러 (SldOrderFileController와 동일 구조).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LightingOrderFileController {

    private final FileStorageService fileStorageService;
    private final FileRepository fileRepository;
    private final LightingOrderRepository lightingOrderRepository;

    @PostMapping("/api/lighting-orders/{orderId}/files")
    @Transactional
    public ResponseEntity<LightingOrderFileResponse> uploadApplicantFile(
            Authentication authentication,
            @PathVariable Long orderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {

        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Lighting Layout 파일 업로드: userSeq={}, orderId={}, fileType={}, filename={}",
                userSeq, orderId, fileType, file.getOriginalFilename());

        LightingOrder order = findOrderOrThrow(orderId);

        if (!order.getUser().getUserSeq().equals(userSeq)) {
            throw new BusinessException(
                    "You do not have permission to upload files for this order",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(storeFile(order, file, fileType));
    }

    @PostMapping("/api/lighting-manager/orders/{orderId}/files")
    @PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
    @Transactional
    public ResponseEntity<LightingOrderFileResponse> uploadManagerFile(
            @PathVariable Long orderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileType") String fileType) {

        log.info("Lighting Manager 파일 업로드: orderId={}, fileType={}, filename={}",
                orderId, fileType, file.getOriginalFilename());
        LightingOrder order = findOrderOrThrow(orderId);
        return ResponseEntity.status(HttpStatus.CREATED).body(storeFile(order, file, fileType));
    }

    private LightingOrderFileResponse storeFile(LightingOrder order, MultipartFile file, String fileType) {
        validateFile(file);

        FileType parsedFileType = parseFileType(fileType);
        String subDirectory = "lighting-orders/" + order.getLightingOrderSeq();
        String storedPath = fileStorageService.store(file, subDirectory);

        FileEntity fileEntity = FileEntity.builder()
                .lightingOrder(order)
                .fileType(parsedFileType)
                .fileUrl(storedPath)
                .originalFilename(file.getOriginalFilename())
                .fileSize(file.getSize())
                .build();

        FileEntity savedFile = fileRepository.save(fileEntity);
        log.info("Lighting Layout 주문 파일 저장 완료: fileSeq={}, orderId={}, type={}",
                savedFile.getFileSeq(), order.getLightingOrderSeq(), parsedFileType);

        return LightingOrderFileResponse.from(savedFile);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("File is required", HttpStatus.BAD_REQUEST, "FILE_REQUIRED");
        }
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

    private LightingOrder findOrderOrThrow(Long orderId) {
        return lightingOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(
                        "Lighting Layout order not found", HttpStatus.NOT_FOUND,
                        "LIGHTINGORDER_NOT_FOUND"));
    }

    @Getter
    @Builder
    public static class LightingOrderFileResponse {
        private Long fileSeq;
        private String fileType;
        private String originalFilename;
        private Long fileSize;
        private LocalDateTime uploadedAt;

        public static LightingOrderFileResponse from(FileEntity entity) {
            return LightingOrderFileResponse.builder()
                    .fileSeq(entity.getFileSeq())
                    .fileType(entity.getFileType().name())
                    .originalFilename(entity.getOriginalFilename())
                    .fileSize(entity.getFileSize())
                    .uploadedAt(entity.getUploadedAt())
                    .build();
        }
    }
}
