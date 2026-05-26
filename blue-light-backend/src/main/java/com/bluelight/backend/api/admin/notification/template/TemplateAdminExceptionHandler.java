package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.LintIssueResponse;
import com.bluelight.backend.api.notification.template.lint.TemplateLintException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 알림 템플릿 Admin 모듈 전용 예외 핸들러.
 *
 * <p>{@link TemplateLintException} → 400 + lint 결과 body, 그 외 도메인 예외 → 적절한 HTTP 상태.
 * {@code GlobalExceptionHandler} 가 처리하는 일반 예외(OptimisticLockingFailure 등)는 그대로 위임.</p>
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.bluelight.backend.api.admin.notification.template")
public class TemplateAdminExceptionHandler {

    @ExceptionHandler(TemplateLintException.class)
    public ResponseEntity<Map<String, Object>> handleLint(TemplateLintException e) {
        log.warn("Template lint blocked save: {} errors", e.getResult().errors().size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Bad Request");
        body.put("code", "TEMPLATE_LINT_FAILED");
        body.put("message", e.getMessage());
        body.put("lint", LintIssueResponse.from(e.getResult()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(NotificationTemplateAdminService.TemplateNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTemplateNotFound(
            NotificationTemplateAdminService.TemplateNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(NotificationTemplateAdminService.DraftNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDraftNotFound(
            NotificationTemplateAdminService.DraftNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(NotificationTemplateAdminService.DraftOwnershipException.class)
    public ResponseEntity<Map<String, Object>> handleOwnership(
            NotificationTemplateAdminService.DraftOwnershipException e) {
        return error(HttpStatus.FORBIDDEN, "DRAFT_NOT_OWNED", e.getMessage());
    }

    @ExceptionHandler(NotificationTemplateAdminService.ChangeReasonRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleReasonRequired(
            NotificationTemplateAdminService.ChangeReasonRequiredException e) {
        return error(HttpStatus.BAD_REQUEST, "CHANGE_REASON_REQUIRED", e.getMessage());
    }

    @ExceptionHandler(NotificationTemplateAdminService.SecurityCategoryDisableNotPermittedException.class)
    public ResponseEntity<Map<String, Object>> handleSecurityDisable(
            NotificationTemplateAdminService.SecurityCategoryDisableNotPermittedException e) {
        return error(HttpStatus.FORBIDDEN, "SECURITY_CATEGORY_DISABLE_REQUIRES_SYSADMIN", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        // Draft 상태머신 가드 (PENDING 외 상태에서 edit/approve/reject 시도) → 409
        return error(HttpStatus.CONFLICT, "DRAFT_STATE_INVALID", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        // reject reviewNote 누락 등 엔티티 입력 검증 실패 → 400
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getMessage());
    }

    @ExceptionHandler(TestSendQuotaTracker.QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(TestSendQuotaTracker.QuotaExceededException e) {
        // 429 Too Many Requests — 일일 50통 한도 초과
        return error(HttpStatus.TOO_MANY_REQUESTS, "TEST_SEND_QUOTA_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(TemplateTestSendService.UnsupportedTestChannelException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedChannel(
            TemplateTestSendService.UnsupportedTestChannelException e) {
        // EMAIL 외 채널 테스트 발송 시도 → 400 (MVP 제약)
        return error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_TEST_CHANNEL", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
