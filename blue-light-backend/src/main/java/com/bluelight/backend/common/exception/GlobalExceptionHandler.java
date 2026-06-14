package com.bluelight.backend.common.exception;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 전역 예외 처리 핸들러
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AuditLogService auditLogService;

    /**
     * AccessDeniedException 처리 (권한 부족)
     *
     * <p>★ 트랙 1.4 — 403 거부를 {@code audit_logs} 에 구조화 기록.
     * P0 SpEL 단일화({@code @PreAuthorize("@appSec.isAssignedLew(...)")}) 이후
     * 인가 거부가 컨트롤러 메서드 진입 *전*에 발생하면서 {@code @Auditable} {@code @Around}
     * 가 더이상 cross-tenant 시도를 기록하지 못하던 공백을 여기서 메운다. 침해 시도를
     * {@code action=ACCESS_DENIED} + requestUri/method/ip 로 SQL 조회 가능하게 한다.</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("Access Denied: {}", e.getMessage());
        recordAccessDeniedAudit();

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error(HttpStatus.FORBIDDEN.getReasonPhrase())
                .code("ACCESS_DENIED")
                .message("You do not have permission to access this resource")
                .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * 인가 거부를 비동기 audit 로그로 기록. 기록 실패는 응답에 영향 없음 (best-effort).
     */
    private void recordAccessDeniedAudit() {
        try {
            Long userSeq = null;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long seq) {
                userSeq = seq;
            }

            HttpServletRequest request = currentRequest();
            String ip = request != null ? clientIp(request) : null;
            String userAgent = request != null ? request.getHeader("User-Agent") : null;
            String method = request != null ? request.getMethod() : null;
            String uri = request != null ? request.getRequestURI() : null;

            auditLogService.logAsync(
                    userSeq,
                    AuditAction.ACCESS_DENIED,
                    AuditCategory.SYSTEM,
                    null,                      // entityType — URI 로 충분
                    null,                      // entityId
                    "Authorization denied (403)",
                    null, null,
                    ip, userAgent, method, uri,
                    HttpStatus.FORBIDDEN.value());
        } catch (Exception ex) {
            // 감사 기록 실패가 403 응답을 막아선 안 됨.
            log.warn("ACCESS_DENIED 감사 로그 기록 실패 (응답에는 영향 없음)", ex);
        }
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * BusinessException 처리
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        log.error("Business Exception: {}", e.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(e.getStatus().value())
                .error(e.getStatus().getReasonPhrase())
                .code(e.getCode())
                .message(e.getMessage())
                .build();

        return ResponseEntity.status(e.getStatus()).body(response);
    }

    /**
     * Validation 예외 처리
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.error("Validation Exception: {}", errors);

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .code("VALIDATION_ERROR")
                .message("Validation failed")
                .details(errors)
                .build();

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 낙관적 락 충돌 — 동시 수정 시 409 STALE_STATE.
     * B-1 블로커 해결: {@code DocumentRequest.@Version} 충돌을 사용자에게 재시도 유도.
     */
    @ExceptionHandler({
            OptimisticLockException.class,
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockingFailureException.class
    })
    public ResponseEntity<ErrorResponse> handleOptimisticLock(Exception e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .code("STALE_STATE")
                .message("This resource was updated by someone else. Please refresh and try again.")
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * 그 외 예외 처리
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected Exception: ", e);

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .code("INTERNAL_ERROR")
                .message("An internal server error occurred")
                .build();

        return ResponseEntity.internalServerError().body(response);
    }
}
