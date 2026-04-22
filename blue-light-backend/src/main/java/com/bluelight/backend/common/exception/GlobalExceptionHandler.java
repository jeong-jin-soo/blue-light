package com.bluelight.backend.common.exception;

import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
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
public class GlobalExceptionHandler {

    /**
     * AccessDeniedException 처리 (권한 부족)
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException e) {
        log.error("Access Denied: {}", e.getMessage());

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

        // LEW Review Form P1.C — CoF 경로(`/api/lew/applications/.../cof`)는 스펙 §9-12에 따라
        // `COF_VERSION_CONFLICT` 로 세분화한다. 그 외는 기존 공통 `STALE_STATE` 유지.
        String code = isCofLockConflict() ? "COF_VERSION_CONFLICT" : "STALE_STATE";
        String message = "COF_VERSION_CONFLICT".equals(code)
                ? "Certificate of Fitness was updated concurrently — refresh and try again."
                : "This resource was updated by someone else. Please refresh and try again.";

        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .code(code)
                .message(message)
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /** 현재 요청 URI가 CoF 편집 경로(/api/lew/applications/{id}/cof ...)인지 판정. */
    private boolean isCofLockConflict() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                String uri = sra.getRequest().getRequestURI();
                if (uri == null) return false;
                return uri.startsWith("/api/lew/applications/") && uri.contains("/cof");
            }
        } catch (Exception ignored) {
            // 요청 컨텍스트 없음 (백그라운드 스레드 등)
        }
        return false;
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
