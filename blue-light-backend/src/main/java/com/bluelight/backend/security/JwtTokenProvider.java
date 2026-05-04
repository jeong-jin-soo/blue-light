package com.bluelight.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * JWT 토큰 생성 및 검증 유틸리티
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secretKeyString;

    @Value("${jwt.expiration}")
    private Long expiration;

    private SecretKey secretKey;

    private static final String DEV_SECRET_PREFIX = "bluelight-jwt-secret-key";

    @PostConstruct
    protected void init() {
        if (secretKeyString.startsWith(DEV_SECRET_PREFIX)) {
            log.warn("========================================");
            log.warn("WARNING: Using default JWT secret key!");
            log.warn("Set JWT_SECRET environment variable for production.");
            log.warn("========================================");
        }
        this.secretKey = Keys.hmacShaKeyFor(secretKeyString.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Access Token 생성 (단일 role — legacy 호출처 호환).
     * <p>
     * 본 오버로드는 {@code roles} claim 을 단일 원소 리스트로 자동 채워, 신규 다중 역할
     * authority 매핑(★ Concierge 강화 + 별도 수금 PR-1 D1=B)이 일관되게 동작하도록 한다.
     *
     * @param userSeq       사용자 PK
     * @param email         사용자 이메일
     * @param role          사용자 primary 역할
     * @param approved      승인 여부 (LEW만 관련)
     * @param emailVerified 이메일 인증 여부
     * @return JWT 토큰
     */
    public String createToken(Long userSeq, String email, String role, boolean approved, boolean emailVerified) {
        return createToken(userSeq, email, role,
                role != null ? Collections.singletonList(role) : Collections.emptyList(),
                approved, emailVerified);
    }

    /**
     * ★ Concierge 강화 + 별도 수금 PR-1 (D1=B) — 다중 역할을 포함하는 토큰 발급.
     * <p>
     * {@code role} (primary) 과 {@code roles} (effective set) 두 claim 을 모두 발급한다.
     * - {@code role} : legacy 클라이언트 호환 (단일 역할 기준 코드)
     * - {@code roles}: Spring Security {@code GrantedAuthority} 매핑용 (primary 포함, 중복 무시)
     * <p>
     * roles 가 null/empty 면 role 만 사용된다 — 기존 토큰과 호환.
     */
    public String createToken(Long userSeq, String email, String role, List<String> roles,
                              boolean approved, boolean emailVerified) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        // primary 누락 방지 — roles 가 비었으면 primary role 단독 클레임으로 fallback.
        List<String> safeRoles = (roles == null || roles.isEmpty())
                ? (role != null ? Collections.singletonList(role) : Collections.emptyList())
                : roles;

        return Jwts.builder()
                .subject(String.valueOf(userSeq))
                .claim("email", email)
                .claim("role", role)
                .claim("roles", safeRoles)
                .claim("approved", approved)
                .claim("emailVerified", emailVerified)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 토큰에서 사용자 ID 추출
     */
    public Long getUserSeq(String token) {
        Claims claims = parseClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 토큰에서 이메일 추출
     */
    public String getEmail(String token) {
        Claims claims = parseClaims(token);
        return claims.get("email", String.class);
    }

    /**
     * 토큰에서 역할 추출 (primary)
     */
    public String getRole(String token) {
        Claims claims = parseClaims(token);
        return claims.get("role", String.class);
    }

    /**
     * ★ Concierge 강화 + 별도 수금 PR-1 — 토큰에서 다중 역할 추출.
     * <p>
     * {@code roles} claim 이 있으면 그대로 반환, 없으면 {@code role} 단일을 1원소 리스트로 반환
     * (기존 토큰과의 하위 호환). null 안전 — 항상 빈 리스트 이상을 반환한다.
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        Claims claims = parseClaims(token);
        Object raw = claims.get("roles");
        if (raw instanceof List<?> rawList && !rawList.isEmpty()) {
            // 모든 원소를 String 으로 강제 — claim 직렬화 형태 다양성 대응.
            return rawList.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        // legacy fallback — role 단일 claim 만 있는 토큰.
        String single = claims.get("role", String.class);
        return single != null ? Collections.singletonList(single) : Collections.emptyList();
    }

    /**
     * 토큰에서 승인 여부 추출
     */
    public Boolean getApproved(String token) {
        Claims claims = parseClaims(token);
        return claims.get("approved", Boolean.class);
    }

    /**
     * 토큰에서 이메일 인증 여부 추출
     */
    public Boolean getEmailVerified(String token) {
        Claims claims = parseClaims(token);
        return claims.get("emailVerified", Boolean.class);
    }

    /**
     * 토큰 유효성 검증
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            log.debug("Invalid JWT signature: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            log.debug("Expired JWT token: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.debug("Unsupported JWT token: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.debug("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 토큰 만료 시간 (초) 반환
     */
    public Long getExpirationInSeconds() {
        return expiration / 1000;
    }

    /**
     * Claims 파싱
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
