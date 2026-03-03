package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SampleFileResponse;
import com.bluelight.backend.domain.file.SampleFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 샘플 파일 API 컨트롤러
 * - 관리자: 업로드/삭제 (POST/DELETE /api/admin/sample-files/{categoryKey})
 * - 일반 사용자: 조회/다운로드 (GET /api/sample-files, /api/sample-files/{categoryKey}/download)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SampleFileController {

    private final SampleFileService sampleFileService;

    // ── Admin Endpoints ──────────────────────────────

    /**
     * 샘플 파일 업로드/교체
     * POST /api/admin/sample-files/{categoryKey}
     */
    @PostMapping("/api/admin/sample-files/{categoryKey}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<SampleFileResponse> uploadSampleFile(
            @PathVariable String categoryKey,
            @RequestParam("file") MultipartFile file) {
        log.info("Admin upload sample file: category={}, filename={}", categoryKey, file.getOriginalFilename());
        SampleFileResponse response = sampleFileService.upload(categoryKey, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 샘플 파일 삭제
     * DELETE /api/admin/sample-files/{categoryKey}
     */
    @DeleteMapping("/api/admin/sample-files/{categoryKey}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, String>> deleteSampleFile(@PathVariable String categoryKey) {
        log.info("Admin delete sample file: category={}", categoryKey);
        sampleFileService.delete(categoryKey);
        return ResponseEntity.ok(Map.of("message", "Sample file deleted successfully"));
    }

    // ── Public Endpoints (authenticated) ──────────────────────────────

    /**
     * 전체 샘플 파일 목록
     * GET /api/sample-files
     */
    @GetMapping("/api/sample-files")
    public ResponseEntity<List<SampleFileResponse>> getSampleFiles() {
        List<SampleFileResponse> files = sampleFileService.getAll();
        return ResponseEntity.ok(files);
    }

    /**
     * 샘플 파일 다운로드
     * GET /api/sample-files/{categoryKey}/download
     */
    @GetMapping("/api/sample-files/{categoryKey}/download")
    public ResponseEntity<Resource> downloadSampleFile(@PathVariable String categoryKey) {
        log.info("Download sample file: category={}", categoryKey);
        SampleFile entity = sampleFileService.getEntity(categoryKey);
        Resource resource = sampleFileService.download(categoryKey);

        String encodedFilename = URLEncoder.encode(
                entity.getOriginalFilename(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");

        // Detect MIME type from filename; fall back to octet-stream
        String mimeType = null;
        try {
            mimeType = Files.probeContentType(Path.of(entity.getOriginalFilename()));
        } catch (Exception ignored) {}
        MediaType mediaType = mimeType != null ? MediaType.parseMediaType(mimeType) : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + entity.getOriginalFilename() +
                                "\"; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }
}
