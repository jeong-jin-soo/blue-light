package com.bluelight.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

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
     * Access Token 생성
     *
     * @param userSeq       사용자 PK
     * @param email         사용자 이메일
     * @param role          사용자 역할
     * @param approved      승인 여부 (LEW만 관련)
     * @param emailVerified 이메일 인증 여부
     * @return JWT 토큰
     */
    public String createToken(Long userSeq, String email, String role, boolean approved, boolean emailVerified) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(String.valueOf(userSeq))
                .claim("email", email)
                .claim("role", role)
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
     * 토큰에서 역할 추출
     */
    public String getRole(String token) {
        Claims claims = parseClaims(token);
        return claims.get("role", String.class);
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
