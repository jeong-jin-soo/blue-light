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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

            // ★ Concierge 강화 + 별도 수금 PR-1 (D1=B): 다중 역할 authority 매핑.
            // roles claim 이 있으면 모든 역할을 ROLE_* 권한으로 부여. 없으면 legacy 단일 role 사용.
            List<String> roleNames = jwtTokenProvider.getRoles(token);
            Set<SimpleGrantedAuthority> authoritySet = new LinkedHashSet<>();

            // 미승인 LEW 가드: primary role 이 LEW 이고 approved=false 면 모든 LEW authority 를
            // ROLE_LEW_PENDING 으로 강등 — 다른 secondary role(예: SLD_MANAGER)은 그대로 살리되
            // /api/admin/** 등 LEW 가 가야 할 경로는 차단된다.
            boolean lewPending = "LEW".equals(role) && (approved == null || !approved);

            for (String r : roleNames) {
                if (r == null || r.isBlank()) continue;
                if ("LEW".equals(r) && lewPending) {
                    authoritySet.add(new SimpleGrantedAuthority("ROLE_LEW_PENDING"));
                } else {
                    authoritySet.add(new SimpleGrantedAuthority("ROLE_" + r));
                }
            }
            // 안전망: roles 가 비어있고 legacy role 만 있으면 그것으로 fallback (LEW_PENDING 가드 동일 적용).
            if (authoritySet.isEmpty() && role != null && !role.isBlank()) {
                if (lewPending) {
                    authoritySet.add(new SimpleGrantedAuthority("ROLE_LEW_PENDING"));
                } else {
                    authoritySet.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }

            List<SimpleGrantedAuthority> authorities = new ArrayList<>(authoritySet);

            // 인증 객체 생성 및 SecurityContext에 설정
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userSeq, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("Security Context에 인증 정보 설정 완료: userSeq={}, primaryRole={}, authorities={}",
                    userSeq, role, authorities);
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
