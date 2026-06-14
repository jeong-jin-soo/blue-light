package com.bluelight.backend.api.loa;

import com.bluelight.backend.api.admin.AdminLoaFormTemplateController;
import com.bluelight.backend.api.admin.LoaFormTemplateService;
import com.bluelight.backend.api.admin.dto.LoaFormTemplateResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.file.FileEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * active LoA 폼 소비 API (Owner / 담당 LEW / ADMIN).
 *
 * <p>스펙: {@code loa-exchange-redesign-spec.md} §3.2 (PR2). 신청자/LEW UI 는 폼 URL 을
 * 하드코딩하지 않고(설계 원칙: 설정 우선), 이 엔드포인트로 현재 active 버전을 로드한다.</p>
 *
 * <p>PR2 는 신청별 폼 스냅샷({@code loaFormTemplateSeq})이 아직 없으므로 글로벌 active 1건을
 * 반환한다. 신청별 고정(send-form 시점) + RENEWAL 404 분기는 PR3 에서 도입된다.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LoaActiveFormController {

    private final LoaFormTemplateService loaFormTemplateService;
    private final ApplicationRepository applicationRepository;

    /**
     * 현재 신청에 적용 가능한 active LoA 폼 메타.
     * GET /api/applications/{id}/loa/active-form
     */
    @GetMapping("/api/applications/{id}/loa/active-form")
    public ResponseEntity<LoaFormTemplateResponse> getActiveForm(
            Authentication authentication,
            @PathVariable Long id) {
        authorize(authentication, id);
        return ResponseEntity.ok(loaFormTemplateService.getActiveForm());
    }

    /**
     * 신청자가 폼 PDF 다운로드.
     * GET /api/applications/{id}/loa/active-form/download
     */
    @GetMapping("/api/applications/{id}/loa/active-form/download")
    public ResponseEntity<Resource> downloadActiveForm(
            Authentication authentication,
            @PathVariable Long id) {
        authorize(authentication, id);
        FileEntity fileEntity = loaFormTemplateService.getActiveFormFile();
        Resource resource = loaFormTemplateService.loadResource(fileEntity.getFileUrl());
        return AdminLoaFormTemplateController.buildDownloadResponse(fileEntity, resource);
    }

    /** Owner / 담당 LEW / ADMIN 만 접근 (LoaService.getLoaStatus 와 동일 정책). */
    private void authorize(Authentication authentication, Long applicationSeq) {
        Long userSeq = (Long) authentication.getPrincipal();
        String role = authentication.getAuthorities().iterator().next().getAuthority();
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
        Long assignedLewSeq = application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq() : null;
        OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                application.getUser().getUserSeq(), userSeq, role, assignedLewSeq);
    }
}
