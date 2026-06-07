package com.bluelight.backend.common.exception;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 트랙 1.4 — AccessDeniedException 발생 시 audit_logs 기록 검증.
 *
 * <p>P0 SpEL 단일화 이후 @PreAuthorize 가 컨트롤러 메서드 진입 전 거부하면서
 * @Auditable @Around 가 cross-tenant 시도를 기록하지 못하던 공백을
 * GlobalExceptionHandler 가 메우는지 검증.</p>
 */
@DisplayName("GlobalExceptionHandler - ACCESS_DENIED 감사 기록 (트랙 1.4)")
class GlobalExceptionHandlerAccessDeniedAuditTest {

    private AuditLogService auditLogService;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        handler = new GlobalExceptionHandler(auditLogService);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void givenRequest(String method, String uri, String ip, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        request.setRemoteAddr(ip);
        if (userAgent != null) request.addHeader("User-Agent", userAgent);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void givenAuthenticatedUser(Long userSeq) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userSeq, null, List.of()));
    }

    @Test
    @DisplayName("403 거부 → ACCESS_DENIED 감사 기록 (userSeq + URI + method + IP)")
    void recordsAuditWithFullContext() {
        givenAuthenticatedUser(200L);
        givenRequest("PATCH", "/api/admin/applications/123/status", "203.0.113.7", "Mozilla/5.0");

        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"));

        // 응답은 기존 403 그대로
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getCode()).isEqualTo("ACCESS_DENIED");

        // audit 기록 호출 검증
        verify(auditLogService).logAsync(
                eq(200L),
                eq(AuditAction.ACCESS_DENIED),
                eq(AuditCategory.SYSTEM),
                isNull(),                                       // entityType
                isNull(),                                       // entityId
                eq("Authorization denied (403)"),
                isNull(), isNull(),                             // before/after
                eq("203.0.113.7"),
                eq("Mozilla/5.0"),
                eq("PATCH"),
                eq("/api/admin/applications/123/status"),
                eq(403));
    }

    @Test
    @DisplayName("X-Forwarded-For 가 있으면 첫 IP 를 클라이언트 IP 로 기록")
    void usesXForwardedForFirstIp() {
        givenAuthenticatedUser(200L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/admin/applications/9/sld-request");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.5, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        handler.handleAccessDeniedException(new AccessDeniedException("denied"));

        verify(auditLogService).logAsync(
                eq(200L), eq(AuditAction.ACCESS_DENIED), eq(AuditCategory.SYSTEM),
                isNull(), isNull(), eq("Authorization denied (403)"),
                isNull(), isNull(),
                eq("198.51.100.5"),      // 첫 IP
                isNull(),                // User-Agent 없음
                eq("GET"),
                eq("/api/admin/applications/9/sld-request"),
                eq(403));
    }

    @Test
    @DisplayName("미인증(principal 비-Long) 거부 → userSeq=null 로 기록 (응답 정상)")
    void recordsNullUserSeqWhenUnauthenticated() {
        // principal 이 String "anonymousUser" 인 경우
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));
        givenRequest("POST", "/api/admin/notification-templates/1/test-send", "192.0.2.1", null);

        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(auditLogService).logAsync(
                isNull(),                // userSeq null
                eq(AuditAction.ACCESS_DENIED), eq(AuditCategory.SYSTEM),
                isNull(), isNull(), eq("Authorization denied (403)"),
                isNull(), isNull(),
                eq("192.0.2.1"), isNull(), eq("POST"),
                eq("/api/admin/notification-templates/1/test-send"),
                eq(403));
    }

    @Test
    @DisplayName("audit 기록이 예외를 던져도 403 응답은 정상 반환 (best-effort)")
    void auditFailureDoesNotBreakResponse() {
        givenAuthenticatedUser(200L);
        givenRequest("GET", "/api/admin/x", "10.0.0.1", null);
        // logAsync 가 throw 하도록
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(auditLogService).logAsync(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"));

        // 감사 기록 실패에도 403 정상
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("RequestContext 없음(비-HTTP) → null 컨텍스트로 기록, 응답 정상")
    void handlesMissingRequestContext() {
        givenAuthenticatedUser(200L);
        // RequestContextHolder 설정 안 함

        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(auditLogService).logAsync(
                eq(200L), eq(AuditAction.ACCESS_DENIED), eq(AuditCategory.SYSTEM),
                isNull(), isNull(), eq("Authorization denied (403)"),
                isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(),  // ip/ua/method/uri 모두 null
                eq(403));
    }

    @Test
    @DisplayName("SecurityContext 비어있어도(인증 null) 기록 + 응답 정상")
    void handlesNullAuthentication() {
        givenRequest("DELETE", "/api/admin/y", "10.0.0.2", null);
        // SecurityContext 인증 설정 안 함 → auth null

        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(auditLogService).logAsync(
                isNull(), eq(AuditAction.ACCESS_DENIED), eq(AuditCategory.SYSTEM),
                isNull(), isNull(), eq("Authorization denied (403)"),
                isNull(), isNull(),
                eq("10.0.0.2"), isNull(), eq("DELETE"), eq("/api/admin/y"),
                eq(403));
    }
}
