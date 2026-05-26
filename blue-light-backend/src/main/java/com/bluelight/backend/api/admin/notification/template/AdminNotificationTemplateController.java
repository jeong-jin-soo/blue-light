package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.CatalogEntryResponse;
import com.bluelight.backend.api.admin.notification.template.dto.CreateDraftRequest;
import com.bluelight.backend.api.admin.notification.template.dto.DisableTemplateRequest;
import com.bluelight.backend.api.admin.notification.template.dto.HistoryItemResponse;
import com.bluelight.backend.api.admin.notification.template.dto.NotificationTemplateDetailResponse;
import com.bluelight.backend.api.admin.notification.template.dto.NotificationTemplateDraftResponse;
import com.bluelight.backend.api.admin.notification.template.dto.NotificationTemplateListItemResponse;
import com.bluelight.backend.api.admin.notification.template.dto.ReviewDraftRequest;
import com.bluelight.backend.api.admin.notification.template.dto.TemplatePreviewRequest;
import com.bluelight.backend.api.admin.notification.template.dto.TemplatePreviewResponse;
import com.bluelight.backend.api.admin.notification.template.dto.TemplateTestSendRequest;
import com.bluelight.backend.api.admin.notification.template.dto.TemplateTestSendResponse;
import com.bluelight.backend.api.admin.notification.template.dto.UpdateDraftRequest;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.bluelight.backend.domain.notification.TemplateDraftStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Admin 콘솔 알림 템플릿 관리 API — PR-T3.
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §6, §9.</p>
 *
 * <p><b>RBAC 매트릭스 (§3.2)</b>
 * <ul>
 *   <li>list/get: NOTIFICATION_MANAGER, SYSTEM_ADMIN, ADMIN/LEW/CM/SM (read-only)</li>
 *   <li>draft CRUD: NOTIFICATION_MANAGER, SYSTEM_ADMIN</li>
 *   <li>approve/reject: SYSTEM_ADMIN 만 (D-1 2-step)</li>
 *   <li>enable/disable: NOTIFICATION_MANAGER, SYSTEM_ADMIN (SECURITY/PAYMENT disable 은 SA only, H-S3)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/notification-templates")
@RequiredArgsConstructor
public class AdminNotificationTemplateController {

    private final NotificationTemplateAdminService adminService;
    private final DraftReviewService reviewService;
    private final TemplatePreviewService previewService;
    private final TemplateTestSendService testSendService;

    // ============================================================
    // 템플릿 조회
    // ============================================================

    @GetMapping
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN','ADMIN','LEW','SLD_MANAGER','CONCIERGE_MANAGER')")
    public ResponseEntity<Page<NotificationTemplateListItemResponse>> listTemplates(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) NotificationCategory category,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        int validPage = Math.max(0, page);
        int validSize = Math.min(Math.max(1, size), 200);
        Pageable pageable = PageRequest.of(validPage, validSize);

        // D-5 — read 권한 범위 필터: NM/SA 제외 역할은 자기 역할 수신 템플릿만
        String roleFilter = resolveRecipientRoleFilter(auth, role);

        Page<NotificationTemplate> rows = adminService.searchTemplates(
                code, channel, locale, enabled, category, roleFilter, pageable);
        return ResponseEntity.ok(rows.map(NotificationTemplateListItemResponse::from));
    }

    @GetMapping("/{templateSeq}")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN','ADMIN','LEW','SLD_MANAGER','CONCIERGE_MANAGER')")
    public ResponseEntity<NotificationTemplateDetailResponse> getTemplate(
            @PathVariable Long templateSeq,
            Authentication auth) {
        NotificationTemplate template = adminService.findTemplate(templateSeq)
                .orElseThrow(() -> new NotificationTemplateAdminService.TemplateNotFoundException(templateSeq));
        ensureReadAuthorized(template, auth);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"" + template.getVersion() + "\"")
                .body(NotificationTemplateDetailResponse.from(template));
    }

    // ============================================================
    // Draft CRUD (NM/SA)
    // ============================================================

    @PostMapping("/drafts")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<NotificationTemplateDraftResponse> createDraft(
            @Valid @RequestBody CreateDraftRequest request,
            Authentication auth) {
        Long actor = principalUserSeq(auth);
        NotificationTemplateDraft draft = adminService.createDraft(request.toInput(), actor);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(NotificationTemplateDraftResponse.from(draft));
    }

    @PatchMapping("/drafts/{draftSeq}")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<NotificationTemplateDraftResponse> editDraft(
            @PathVariable Long draftSeq,
            @Valid @RequestBody UpdateDraftRequest request,
            Authentication auth) {
        Long actor = principalUserSeq(auth);
        NotificationTemplateDraft existing = adminService.findDraft(draftSeq)
                .orElseThrow(() -> new NotificationTemplateAdminService.DraftNotFoundException(draftSeq));
        NotificationTemplateDraft updated = adminService.editDraft(
                draftSeq,
                request.toInput(existing.getTemplateSeq(), existing.getTemplateCode(),
                        existing.getChannel(), existing.getLocale()),
                actor);
        return ResponseEntity.ok(NotificationTemplateDraftResponse.from(updated));
    }

    @PostMapping("/drafts/{draftSeq}/withdraw")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<Void> withdrawDraft(
            @PathVariable Long draftSeq,
            Authentication auth) {
        adminService.withdrawDraft(draftSeq, principalUserSeq(auth));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/drafts/{draftSeq}")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<NotificationTemplateDraftResponse> getDraft(
            @PathVariable Long draftSeq) {
        NotificationTemplateDraft draft = adminService.findDraft(draftSeq)
                .orElseThrow(() -> new NotificationTemplateAdminService.DraftNotFoundException(draftSeq));
        return ResponseEntity.ok(NotificationTemplateDraftResponse.from(draft));
    }

    @GetMapping("/drafts")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<Page<NotificationTemplateDraftResponse>> listDrafts(
            @RequestParam(defaultValue = "PENDING") TemplateDraftStatus status,
            @RequestParam(required = false) Boolean myOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        int validPage = Math.max(0, page);
        int validSize = Math.min(Math.max(1, size), 200);
        Pageable pageable = PageRequest.of(validPage, validSize);
        Page<NotificationTemplateDraft> rows;
        if (Boolean.TRUE.equals(myOnly)) {
            rows = adminService.listMyDrafts(principalUserSeq(auth), status, pageable);
        } else {
            rows = adminService.listDraftsByStatus(status, pageable);
        }
        return ResponseEntity.ok(rows.map(NotificationTemplateDraftResponse::from));
    }

    // ============================================================
    // Approve / Reject (SA only — D-1)
    // ============================================================

    @PostMapping("/drafts/{draftSeq}/approve")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<NotificationTemplateDetailResponse> approveDraft(
            @PathVariable Long draftSeq,
            @Valid @RequestBody(required = false) ReviewDraftRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String note = request != null ? request.reviewNote() : null;
        NotificationTemplate result = reviewService.approve(
                draftSeq, principalUserSeq(auth), note, clientIp(httpRequest));
        return ResponseEntity.ok(NotificationTemplateDetailResponse.from(result));
    }

    @PostMapping("/drafts/{draftSeq}/reject")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> rejectDraft(
            @PathVariable Long draftSeq,
            @Valid @RequestBody ReviewDraftRequest request,
            Authentication auth) {
        reviewService.reject(draftSeq, principalUserSeq(auth), request.reviewNote());
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // Enable / Disable (H-S3 SECURITY/PAYMENT 가드)
    // ============================================================

    @PostMapping("/{templateSeq}/enable")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<Void> enableTemplate(
            @PathVariable Long templateSeq,
            @Valid @RequestBody(required = false) DisableTemplateRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String reason = request != null ? request.changeReason() : null;
        adminService.enableTemplate(templateSeq, reason, principalUserSeq(auth), clientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{templateSeq}/disable")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<Void> disableTemplate(
            @PathVariable Long templateSeq,
            @Valid @RequestBody DisableTemplateRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        adminService.disableTemplate(
                templateSeq,
                request.changeReason(),
                principalUserSeq(auth),
                clientIp(httpRequest),
                hasRole(auth, "SYSTEM_ADMIN"));
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // Catalog + History (PR-T5)
    // ============================================================

    @GetMapping("/catalog")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN','ADMIN','LEW','SLD_MANAGER','CONCIERGE_MANAGER')")
    public ResponseEntity<List<CatalogEntryResponse>> listCatalog() {
        return ResponseEntity.ok(adminService.listCatalog().stream()
                .map(CatalogEntryResponse::from)
                .toList());
    }

    @GetMapping("/catalog/{templateCode}")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN','ADMIN','LEW','SLD_MANAGER','CONCIERGE_MANAGER')")
    public ResponseEntity<CatalogEntryResponse> getCatalog(@PathVariable String templateCode) {
        return adminService.findCatalog(templateCode)
                .map(c -> ResponseEntity.ok(CatalogEntryResponse.from(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{templateSeq}/history")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN','ADMIN','LEW','SLD_MANAGER','CONCIERGE_MANAGER')")
    public ResponseEntity<Page<HistoryItemResponse>> getHistory(
            @PathVariable Long templateSeq,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            Authentication auth) {
        // D-5 — non-NM/SA 는 본인 역할 수신 템플릿의 history 만 볼 수 있도록 가드
        NotificationTemplate template = adminService.findTemplate(templateSeq)
                .orElseThrow(() -> new NotificationTemplateAdminService.TemplateNotFoundException(templateSeq));
        ensureReadAuthorized(template, auth);

        int validPage = Math.max(0, page);
        int validSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(validPage, validSize);
        return ResponseEntity.ok(adminService.listHistory(templateSeq, pageable)
                .map(HistoryItemResponse::from));
    }

    // ============================================================
    // Preview + Test-send (PR-T4)
    // ============================================================

    @PostMapping("/{templateSeq}/preview")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<TemplatePreviewResponse> preview(
            @PathVariable Long templateSeq,
            @Valid @RequestBody TemplatePreviewRequest request) {
        return ResponseEntity.ok(previewService.preview(templateSeq, request.payloadOrEmpty()));
    }

    @PostMapping("/{templateSeq}/test-send")
    @PreAuthorize("hasAnyRole('NOTIFICATION_MANAGER','SYSTEM_ADMIN')")
    public ResponseEntity<TemplateTestSendResponse> testSend(
            @PathVariable Long templateSeq,
            @Valid @RequestBody TemplateTestSendRequest request,
            Authentication auth) {
        return ResponseEntity.ok(testSendService.sendTestToSelf(
                templateSeq, principalUserSeq(auth), request.payloadOrEmpty()));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static Long principalUserSeq(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    private static boolean hasRole(Authentication auth, String role) {
        if (auth == null || auth.getAuthorities() == null) return false;
        String target = "ROLE_" + role;
        return auth.getAuthorities().stream().anyMatch(a -> target.equals(a.getAuthority()));
    }

    /**
     * D-5 — read 권한 범위 필터.
     * NM/SA 는 모든 템플릿 열람 가능 (요청 role 파라미터 그대로 사용).
     * 그 외 역할(ADMIN/LEW/SLD_MANAGER/CONCIERGE_MANAGER)은 자기 역할이 recipient_roles 에 포함된 row 만.
     */
    private static String resolveRecipientRoleFilter(Authentication auth, String requestedRole) {
        if (hasRole(auth, "NOTIFICATION_MANAGER") || hasRole(auth, "SYSTEM_ADMIN")) {
            return requestedRole;
        }
        // 첫 번째 권한을 자기 역할로 사용 (RoleHierarchy 미사용 가정)
        return auth.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .orElse(requestedRole);
    }

    /** Detail 권한 가드 — D-5 read 범위 일치 확인. */
    private static void ensureReadAuthorized(NotificationTemplate template, Authentication auth) {
        if (hasRole(auth, "NOTIFICATION_MANAGER") || hasRole(auth, "SYSTEM_ADMIN")) return;
        String mine = auth.getAuthorities().stream().findFirst()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).orElse("");
        String recipientRoles = template.getRecipientRoles();
        if (recipientRoles == null || !recipientRoles.contains(mine)) {
            throw new NotificationTemplateAdminService.TemplateNotFoundException(template.getTemplateSeq());
        }
    }

    private static String clientIp(HttpServletRequest req) {
        if (req == null) return null;
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 첫 IP만 사용 (proxy chain)
            return forwarded.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        return Optional.ofNullable(real).filter(s -> !s.isBlank()).orElse(req.getRemoteAddr());
    }
}
