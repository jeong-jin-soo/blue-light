package com.bluelight.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 인증 필터
 * - 요청 헤더에서 JWT 토큰을 추출하여 검증
 * - 유효한 토큰인 경우 SecurityContext에 인증 정보 설정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            Long userSeq = jwtTokenProvider.getUserSeq(token);
            String role = jwtTokenProvider.getRole(token);
            Boolean approved = jwtTokenProvider.getApproved(token);

            // 미승인 LEW는 ROLE_LEW_PENDING 권한 부여 → /api/admin/** 접근 차단
            String authority;
            if ("LEW".equals(role) && (approved == null || !approved)) {
                authority = "ROLE_LEW_PENDING";
            } else {
                authority = "ROLE_" + role;
            }

            // 권한 설정
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(authority)
            );

            // 인증 객체 생성 및 SecurityContext에 설정
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userSeq, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("Security Context에 인증 정보 설정 완료: userSeq={}, role={}, authority={}", userSeq, role, authority);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 토큰 추출 (우선순위: 1. httpOnly 쿠키, 2. Authorization 헤더)
     * - 쿠키 우선: XSS 공격 시 Authorization 헤더 조작 방지
     * - 헤더 하위 호환: 기존 클라이언트 지원 (전환 기간)
     */
    private String resolveToken(HttpServletRequest request) {
        // 1. httpOnly 쿠키에서 추출
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("bluelight_token".equals(cookie.getName())) {
                    String cookieToken = cookie.getValue();
                    if (StringUtils.hasText(cookieToken)) {
                        return cookieToken;
                    }
                }
            }
        }

        // 2. Authorization 헤더 fallback (하위 호환)
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
