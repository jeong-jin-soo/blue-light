package com.bluelight.backend.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler — 낙관적 락 예외의 경로별 에러코드 변환 단위 테스트 (P1.C).
 *
 * <p>스펙 §9-12: CoF 편집 경로에서 {@link ObjectOptimisticLockingFailureException}가 발생하면
 * 응답 코드가 {@code COF_VERSION_CONFLICT}로 변환되어야 한다. 그 외 경로는 기존 공통
 * {@code STALE_STATE} 유지.</p>
 *
 * <p>RequestContextHolder에 가짜 ServletRequestAttributes를 주입하고 handler 메서드를 직접 호출한다.
 * MockMvc 풀스택 없이도 URI 분기 로직을 검증할 수 있다.</p>
 */
@DisplayName("GlobalExceptionHandler - CoF optimistic lock mapping (P1.C)")
class GlobalExceptionHandlerCofLockTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void stubRequestUri(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("PUT /api/lew/applications/{id}/cof 에서 충돌 → COF_VERSION_CONFLICT (409)")
    void cof_put_maps_to_cof_version_conflict() {
        stubRequestUri("/api/lew/applications/1/cof");

        ResponseEntity<ErrorResponse> res = handler.handleOptimisticLock(
            new ObjectOptimisticLockingFailureException("CoF", 1L));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getCode()).isEqualTo("COF_VERSION_CONFLICT");
        assertThat(res.getBody().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("POST /api/lew/applications/{id}/cof/finalize 에서 충돌 → COF_VERSION_CONFLICT")
    void cof_finalize_maps_to_cof_version_conflict() {
        stubRequestUri("/api/lew/applications/1/cof/finalize");

        ResponseEntity<ErrorResponse> res = handler.handleOptimisticLock(
            new ObjectOptimisticLockingFailureException("CoF", 1L));

        assertThat(res.getBody().getCode()).isEqualTo("COF_VERSION_CONFLICT");
    }

    @Test
    @DisplayName("CoF 외 경로에서 충돌 → STALE_STATE (기존 동작 유지)")
    void non_cof_path_keeps_stale_state() {
        stubRequestUri("/api/applications/5/payments");

        ResponseEntity<ErrorResponse> res = handler.handleOptimisticLock(
            new ObjectOptimisticLockingFailureException("Application", 5L));

        assertThat(res.getBody().getCode()).isEqualTo("STALE_STATE");
    }

    @Test
    @DisplayName("/api/lew/applications/{id} (CoF 없는 경로)에서 충돌 → STALE_STATE")
    void lew_non_cof_path_keeps_stale_state() {
        stubRequestUri("/api/lew/applications/1");

        ResponseEntity<ErrorResponse> res = handler.handleOptimisticLock(
            new ObjectOptimisticLockingFailureException("Application", 1L));

        assertThat(res.getBody().getCode()).isEqualTo("STALE_STATE");
    }

    @Test
    @DisplayName("요청 컨텍스트 없을 때 (스케줄러 등)에도 fallback STALE_STATE")
    void no_request_context_falls_back_to_stale_state() {
        // RequestContextHolder에 아무것도 안 넣음

        ResponseEntity<ErrorResponse> res = handler.handleOptimisticLock(
            new ObjectOptimisticLockingFailureException("X", 1L));

        assertThat(res.getBody().getCode()).isEqualTo("STALE_STATE");
    }
}
