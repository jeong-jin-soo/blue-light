package com.bluelight.backend.config;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.domain.audit.Auditable;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Auditable 어노테이션 처리 AOP Aspect
 * - 컨트롤러 메서드 실행 전후로 감사 로그를 자동 기록
 * - 실패 시에도 비즈니스 로직에 영향 없음
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogService auditLogService;

    @Around("@annotation(auditable)")
    public Object auditAction(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        Object result;
        int httpStatus = 200;

        try {
            result = joinPoint.proceed();
            if (result instanceof ResponseEntity<?> re) {
                httpStatus = re.getStatusCode().value();
            }
        } catch (Throwable ex) {
            httpStatus = 500;
            logAudit(joinPoint, auditable, httpStatus, null);
            throw ex;
        }

        logAudit(joinPoint, auditable, httpStatus, result);
        return result;
    }

    private void logAudit(ProceedingJoinPoint joinPoint, Auditable auditable, int httpStatus, Object result) {
        try {
            Long userSeq = null;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long seq) {
                userSeq = seq;
            }

            String entityId = extractEntityId(joinPoint);
            Long applicationSeq = extractApplicationSeq(joinPoint, auditable.entityType(), entityId, result);
            HttpServletRequest request = getCurrentRequest();
            String ipAddress = request != null ? getClientIp(request) : null;
            String userAgent = request != null ? request.getHeader("User-Agent") : null;
            String requestMethod = request != null ? request.getMethod() : null;
            String requestUri = request != null ? request.getRequestURI() : null;

            Object requestData = extractRequestData(joinPoint);

            auditLogService.logAsync(
                    applicationSeq,
                    userSeq,
                    auditable.action(),
                    auditable.category(),
                    auditable.entityType().isEmpty() ? null : auditable.entityType(),
                    entityId,
                    auditable.description().isEmpty() ? null : auditable.description(),
                    null, requestData,
                    ipAddress, userAgent, requestMethod, requestUri, httpStatus
            );
        } catch (Exception e) {
            log.warn("감사 로그 기록 실패 (비즈니스 로직에 영향 없음)", e);
        }
    }

    /**
     * @RequestBody + @RequestParam 파라미터 추출
     * - 비밀번호 등 민감 정보는 마스킹 처리
     */
    private Object extractRequestData(ProceedingJoinPoint joinPoint) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        Parameter[] params = sig.getMethod().getParameters();
        Object[] args = joinPoint.getArgs();

        Object body = null;
        Map<String, Object> queryParams = new LinkedHashMap<>();

        for (int i = 0; i < params.length; i++) {
            if (args[i] == null) continue;

            // @RequestBody 추출
            if (params[i].isAnnotationPresent(RequestBody.class)) {
                body = args[i];
            }

            // @RequestParam 추출
            RequestParam rp = params[i].getAnnotation(RequestParam.class);
            if (rp != null) {
                String name = rp.value().isEmpty() ? params[i].getName() : rp.value();
                // 멀티파트 파일(바이너리)은 JSON 직렬화하면 깨진 JSON → audit_logs.after_value INSERT 실패.
                // 파일명만 기록한다(업로드 식별엔 충분).
                Object value = args[i];
                if (value instanceof org.springframework.web.multipart.MultipartFile mf) {
                    value = mf.getOriginalFilename();
                }
                queryParams.put(name, value);
            }
        }

        // Body가 있으면 Body 우선, 없으면 QueryParam
        if (body != null) return body;
        if (!queryParams.isEmpty()) return queryParams;
        return null;
    }

    private String extractEntityId(ProceedingJoinPoint joinPoint) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        Parameter[] params = sig.getMethod().getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < params.length; i++) {
            PathVariable pv = params[i].getAnnotation(PathVariable.class);
            if (pv != null && args[i] != null) {
                String name = pv.value().isEmpty() ? params[i].getName() : pv.value();
                if ("id".equals(name) || "applicationId".equals(name)) {
                    return String.valueOf(args[i]);
                }
            }
        }
        return null;
    }

    /**
     * 신청(Application) 타임라인 연결 seq 추출:
     * ① {@code applicationId} 경로변수가 있으면 그 값 (예: 파일 업로드 — entityType 은 File 이라도 신청에 연결)
     * ② 없고 entityType 이 "Application" 이면 {@code id} 경로변수 = applicationSeq
     */
    private Long extractApplicationSeq(ProceedingJoinPoint joinPoint, String entityType, String entityId, Object result) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        Parameter[] params = sig.getMethod().getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < params.length; i++) {
            PathVariable pv = params[i].getAnnotation(PathVariable.class);
            if (pv != null && args[i] != null) {
                String name = pv.value().isEmpty() ? params[i].getName() : pv.value();
                if ("applicationId".equals(name)) {
                    return parseLongOrNull(String.valueOf(args[i]));
                }
            }
        }
        if ("Application".equalsIgnoreCase(entityType)) {
            Long fromPath = parseLongOrNull(entityId);
            if (fromPath != null) return fromPath;
            // 생성류(POST, 경로에 id 없음): 응답 바디의 applicationSeq 폴백 (예: APPLICATION_CREATED)
            return extractApplicationSeqFromResult(result);
        }
        return null;
    }

    /** ResponseEntity 바디에 applicationSeq getter 가 있으면 그 값을 읽는다 (생성 응답 연결용). */
    private Long extractApplicationSeqFromResult(Object result) {
        Object body = result instanceof ResponseEntity<?> re ? re.getBody() : result;
        if (body == null) return null;
        try {
            var m = body.getClass().getMethod("getApplicationSeq");
            Object v = m.invoke(body);
            if (v instanceof Number n) return n.longValue();
        } catch (ReflectiveOperationException ignored) {
            // getApplicationSeq 가 없으면 연결 불가 — null 유지
        }
        return null;
    }

    private Long parseLongOrNull(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private HttpServletRequest getCurrentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
