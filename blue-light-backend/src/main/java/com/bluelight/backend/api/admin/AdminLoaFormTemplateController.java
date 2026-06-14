package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.LoaFormTemplateResponse;
import com.bluelight.backend.domain.file.FileEntity;
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
 * Admin LoA 폼 템플릿 CRUD 컨트롤러.
 *
 * <p>스펙: {@code loa-exchange-redesign-spec.md} §3.1 (PR2). 폼 관리는 admin 책임이므로
 * LEW 는 제외하고 {@code ADMIN}/{@code SYSTEM_ADMIN} 만 허용한다.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/loa-form-templates")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
public class AdminLoaFormTemplateController {

    private final LoaFormTemplateService loaFormTemplateService;

    /** 전체 버전 목록 (active 표시, soft-deleted 제외). */
    @GetMapping
    public ResponseEntity<List<LoaFormTemplateResponse>> list() {
        return ResponseEntity.ok(loaFormTemplateService.list());
    }

    /** 신규 폼 업로드 (multipart: file + label, 옵션 activate). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LoaFormTemplateResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("label") String label,
            @RequestParam(value = "activate", defaultValue = "false") boolean activate,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Admin upload LoA form template: label={}, activate={}, file={}", label, activate,
                file != null ? file.getOriginalFilename() : null);
        LoaFormTemplateResponse response = loaFormTemplateService.upload(file, label, activate, userSeq);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** 해당 버전 활성화 (+ 기존 active 비활성화, 단일성 보장). */
    @PatchMapping("/{seq}/activate")
    public ResponseEntity<LoaFormTemplateResponse> activate(
            @PathVariable Long seq,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(loaFormTemplateService.activate(seq, userSeq));
    }

    /** soft delete. */
    @DeleteMapping("/{seq}")
    public ResponseEntity<Void> delete(
            @PathVariable Long seq,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        loaFormTemplateService.softDelete(seq, userSeq);
        return ResponseEntity.noContent().build();
    }

    /** 폼 파일 다운로드 (admin 검수용). */
    @GetMapping("/{seq}/download")
    public ResponseEntity<Resource> download(@PathVariable Long seq) {
        FileEntity fileEntity = loaFormTemplateService.getFileForTemplate(seq);
        Resource resource = loaFormTemplateService.loadResource(fileEntity.getFileUrl());
        return buildDownloadResponse(fileEntity, resource);
    }

    /** Content-Disposition + MIME 헤더로 다운로드 응답을 구성한다 (FileController 패턴 동형).
     *  active-form 소비(api.loa.LoaActiveFormController)에서도 재사용하므로 public. */
    public static ResponseEntity<Resource> buildDownloadResponse(FileEntity fileEntity, Resource resource) {
        String filename = fileEntity.getOriginalFilename() != null
                ? fileEntity.getOriginalFilename() : ("loa-form-" + fileEntity.getFileSeq() + ".pdf");
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }
}
