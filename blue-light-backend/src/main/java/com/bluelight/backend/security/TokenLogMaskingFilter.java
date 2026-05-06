package com.bluelight.backend.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL path 토큰을 MDC/access log에 평문 기록하지 않도록 마스킹하는 필터
 * (★ Kaki Concierge v1.5, 보안 리뷰 H-2 / PRD §4.4).
 *
 * <p>대상 경로 (정규식):
 * <ul>
 *   <li>{@code /api/public/account-setup/{token}} — Phase 1 PR#1 계정 활성화</li>
 *   <li>{@code /api/public/loa-sign/{token}} — Phase 2 LOA 원격 서명(경로 B)</li>
 * </ul>
 *
 * <p>마스킹 규칙: 토큰 앞 4자 + "****" + 뒤 4자. 8자 이하는 전체 "****".
 *
 * <p><b>주의</b>: 이 필터는 MDC에 masked URI를 {@code requestUri} 키로 넣을 뿐,
 * 실제 Tomcat access log 패턴 변경은 별도 PR에서 처리한다.
 * logback 패턴에 {@code %X{requestUri}}를 사용하도록 권고 (access.log/application.log 양쪽).
 *
 * <p><b>필터 순서</b>: {@link Ordered#HIGHEST_PRECEDENCE}로 등록되어
 * JwtAuthenticationFilter보다 먼저 실행되며, 요청 전체 수명 동안 MDC가 유지된다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TokenLogMaskingFilter implements Filter {

    /**
     * {@code /api/public/(account-setup|loa-sign)/{token}} 매칭.
     * group(1) = prefix, group(2) = token 본문
     */
    private static final Pattern TOKEN_PATH_PATTERN = Pattern.compile(
        "(/api/public/(?:account-setup|loa-sign)/)([^/?#]+)"
    );

    /**
     * MDC 키 — logback 패턴에서 {@code %X{requestUri}}로 참조.
     */
    private static final String MDC_MASKED_URI = "requestUri";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq)) {
            chain.doFilter(request, response);
            return;
        }

        String uri = httpReq.getRequestURI();
        String maskedUri = maskTokenInPath(uri);
        MDC.put(MDC_MASKED_URI, maskedUri);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_MASKED_URI);
        }
    }

    /**
     * URI 경로 내 토큰을 마스킹 처리.
     * - 정규식 non-match 시 원본 반환
     * - null 입력 시 null 반환
     */
    static String maskTokenInPath(String uri) {
        if (uri == null) {
            return null;
        }
        return TOKEN_PATH_PATTERN.matcher(uri).replaceAll(mr -> {
            String prefix = mr.group(1);
            String token = mr.group(2);
            String masked = maskToken(token);
            return Matcher.quoteReplacement(prefix + masked);
        });
    }

    /**
     * 토큰 마스킹 규칙:
     * - 8자 초과: 앞 4자 + "****" + 뒤 4자
     * - 8자 이하 또는 null: "****"
     */
    static String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "****";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
