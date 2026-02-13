package com.bluelight.backend.api.file;

import com.bluelight.backend.api.file.dto.FileResponse;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileType;
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
import java.util.List;

/**
 * File API controller
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * Upload a file for an application (applicant)
     * POST /api/applications/:id/files
     */
    @PostMapping("/api/applications/{applicationId}/files")
    public ResponseEntity<FileResponse> uploadFile(
            Authentication authentication,
            @PathVariable Long applicationId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fileType", defaultValue = "DRAWING_SLD") FileType fileType) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("File upload request: userSeq={}, applicationSeq={}, type={}, name={}",
                userSeq, applicationId, fileType, file.getOriginalFilename());
        FileResponse response = fileService.uploadFile(userSeq, applicationId, file, fileType);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Upload a file for an application (admin)
     * POST /api/admin/applications/:id/files
     */
    @PostMapping("/api/admin/applications/{applicationId}/files")
    @PreAuthorize("hasAnyRole('ADMIN', 'LEW')")
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
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileEntity.getOriginalFilename() + "\"; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }

    /**
     * Delete a file
     * DELETE /api/files/:fileId
     */
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
}
