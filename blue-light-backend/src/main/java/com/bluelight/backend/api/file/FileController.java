package com.bluelight.backend.api.file;

import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.Auditable;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.security.GenericRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * File API controller
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final GenericRateLimiter rateLimiter;

    /** 파일 업로드: 사용자당 10분 내 최대 30회 */
    private static final String RATE_TYPE_UPLOAD = "FILE_UPLOAD";
    private static final int UPLOAD_MAX = 30;
    private static final long UPLOAD_WINDOW_MIN = 10;

    /**
     * Upload a file for an application (applicant)
     * POST /api/applications/:id/files
     */
    @Auditable(action = AuditAction.FILE_UPLOADED, category = AuditCategory.APPLICATION, entityType = "File")
    @PostMapping("/api/applications/{applicationId}/files")
    public ResponseEntity<FileResponse> uploadFile(
            Authentication authentication,
            @PathVariable Long applicationId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fileType", defaultValue = "DRAWING_SLD") FileType fileType) {
        Long userSeq = (Long) authentication.getPrincipal();
        rateLimiter.checkAndRecord(RATE_TYPE_UPLOAD, String.valueOf(userSeq), UPLOAD_MAX, UPLOAD_WINDOW_MIN);
        log.info("File upload request: userSeq={}, applicationSeq={}, type={}, name={}",
                userSeq, applicationId, fileType, file.getOriginalFilename());
        FileResponse response = fileService.uploadFile(userSeq, applicationId, file, fileType);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Upload a file for an application (admin)
     * POST /api/admin/applications/:id/files
     */
    @Auditable(action = AuditAction.FILE_UPLOADED, category = AuditCategory.ADMIN, entityType = "File")
    @PostMapping("/api/admin/applications/{applicationId}/files")
    // 배정 LEW만 자기 신청에 업로드 가능 — cross-tenant 방지. ADMIN/SYSTEM_ADMIN은 전체 허용.
    // (AdminApplicationController 등과 동일한 @appSec.isAssignedLew 단일 가드)
    @PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#applicationId, authentication)")
    public ResponseEntity<FileResponse> uploadFileAsAdmin(
            @PathVariable Long applicationId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fileType", defaultValue = "LICENSE_PDF") FileType fileType) {
        log.info("Admin file upload: applicationSeq={}, type={}, name={}", applicationId, fileType, file.getOriginalFilename());
        FileResponse response = fileService.uploadFileAsAdmin(applicationId, file, fileType);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all files for an application
     * GET /api/applications/:id/files
     */
    @GetMapping("/api/applications/{applicationId}/files")
    public ResponseEntity<List<FileResponse>> getFiles(
            Authentication authentication,
            @PathVariable Long applicationId) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("Get files: userSeq={}, applicationSeq={}", userSeq, applicationId);
        List<FileResponse> files = fileService.getFilesByApplication(userSeq, role, applicationId);
        return ResponseEntity.ok(files);
    }

    /**
     * Download a file
     * GET /api/files/:fileId/download
     */
    @GetMapping("/api/files/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(
            Authentication authentication,
            @PathVariable Long fileId) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("File download: userSeq={}, fileSeq={}", userSeq, fileId);
        FileEntity fileEntity = fileService.getFileEntity(userSeq, role, fileId);
        Resource resource = fileService.downloadFile(userSeq, role, fileId);

        String encodedFilename = URLEncoder.encode(fileEntity.getOriginalFilename(), StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        MediaType mediaType = resolveMediaType(fileEntity.getOriginalFilename());

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileEntity.getOriginalFilename() + "\"; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }

    /**
     * Delete a file
     * DELETE /api/files/:fileId
     */
    @Auditable(action = AuditAction.FILE_DELETED, category = AuditCategory.APPLICATION, entityType = "File")
    @DeleteMapping("/api/files/{fileId}")
    public ResponseEntity<Void> deleteFile(
            Authentication authentication,
            @PathVariable Long fileId) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        log.info("File delete: userSeq={}, fileSeq={}", userSeq, fileId);
        fileService.deleteFile(userSeq, role, fileId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 원본 파일명의 확장자로 Content-Type을 결정한다.
     * {@link Files#probeContentType}는 플랫폼·파일 존재 여부에 따라 null을 반환해
     * octet-stream으로 떨어지면 브라우저가 .txt로 저장하는 문제가 있어, 확장자 매핑을 우선한다.
     */
    private static MediaType resolveMediaType(String filename) {
        String ext = "";
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                ext = filename.substring(dot + 1).toLowerCase();
            }
        }
        switch (ext) {
            case "pdf":  return MediaType.APPLICATION_PDF;
            case "png":  return MediaType.IMAGE_PNG;
            case "jpg":
            case "jpeg": return MediaType.IMAGE_JPEG;
            case "gif":  return MediaType.IMAGE_GIF;
            case "webp": return MediaType.parseMediaType("image/webp");
            case "svg":  return MediaType.parseMediaType("image/svg+xml");
            case "dxf":  return MediaType.parseMediaType("image/vnd.dxf");
            case "dwg":  return MediaType.parseMediaType("image/vnd.dwg");
            case "txt":  return MediaType.TEXT_PLAIN;
            case "csv":  return MediaType.parseMediaType("text/csv");
            case "doc":  return MediaType.parseMediaType("application/msword");
            case "docx": return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xls":  return MediaType.parseMediaType("application/vnd.ms-excel");
            case "xlsx": return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default:     break;
        }
        // 확장자로 못 찾으면 probeContentType 폴백, 그래도 없으면 octet-stream
        try {
            String probed = Files.probeContentType(Path.of(filename == null ? "" : filename));
            if (probed != null) {
                return MediaType.parseMediaType(probed);
            }
        } catch (Exception ignored) {}
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
