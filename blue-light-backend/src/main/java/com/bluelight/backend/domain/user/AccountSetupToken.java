package com.bluelight.backend.domain.user;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 계정 활성화 토큰 (★ Kaki Concierge v1.5, H-3 + O-17 반영)
 * <p>
 * 컨시어지 신청으로 자동 생성된 계정의 최초 비밀번호 설정을 위해 발급되는 일회성 토큰.
 * LoaSigningToken과 대칭 구조를 가지되 활성화 전용 의미론을 갖는다.
 *
 * 불변식:
 * 1. 한 User에 대해 {@link #isUsable()} = true인 토큰은 최대 1개 (O-17)
 *    — 신규 발급 시 서비스 레이어가 기존 유효 토큰을 revoke() 처리 후 INSERT
 * 2. failedAttempts >= MAX_FAILED_ATTEMPTS(5) → lockedAt 자동 세팅 (H-3)
 * 3. usedAt이 null이 아니면 재사용 불가
 * 4. 48시간 TTL (서비스 레이어가 expiresAt = now + 48h로 발급)
 */
@Entity
@Table(name = "account_setup_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE account_setup_tokens SET deleted_at = NOW(6) WHERE token_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class AccountSetupToken extends BaseEntity {

    /**
     * H-3: 5회 실패 누적 시 잠금
     */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_seq")
    private Long tokenSeq;

    /**
     * URL path에 노출되는 UUID v4 또는 SecureRandom base64url 문자열
     */
    @Column(name = "token_uuid", nullable = false, unique = true, length = 36, updatable = false)
    private String tokenUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_seq", nullable = false, updatable = false)
    private User user;

    /**
     * 발급 경로 (CONCIERGE_ACCOUNT_SETUP / LOGIN_ACTIVATION)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 40, updatable = false)
    private AccountSetupTokenSource source;

    /**
     * 만료 시점 (기본 createdAt + 48h)
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 사용 완료 시점 — 1회성 보장
     */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /**
     * O-17: 새 토큰 발급 시 기존 토큰 즉시 무효화
     */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * H-3: 실패 시도 누적 카운트
     */
    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    /**
     * H-3: MAX_FAILED_ATTEMPTS 초과 시 잠금 시점
     */
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /**
     * 발급 요청 IP (IPv6 max 45자)
     */
    @Column(name = "requesting_ip", length = 45)
    private String requestingIp;

    /**
     * 발급 요청 User-Agent
     */
    @Column(name = "requesting_user_agent", length = 500)
    private String requestingUserAgent;

    @Builder
    public AccountSetupToken(String tokenUuid, User user, AccountSetupTokenSource source,
                             LocalDateTime expiresAt, String requestingIp, String requestingUserAgent) {
        this.tokenUuid = tokenUuid;
        this.user = user;
        this.source = source;
        this.expiresAt = expiresAt;
        this.requestingIp = requestingIp;
        this.requestingUserAgent = requestingUserAgent;
        this.failedAttempts = 0;
    }

    /**
     * 토큰이 사용 가능한 상태인지 확인
     * - usedAt null (미사용)
     * - revokedAt null (무효화되지 않음)
     * - lockedAt null (잠기지 않음)
     * - 만료 이전
     */
    public boolean isUsable() {
        LocalDateTime now = LocalDateTime.now();
        return usedAt == null
            && revokedAt == null
            && lockedAt == null
            && now.isBefore(expiresAt);
    }

    /**
     * 토큰 사용 완료 처리 (1회성)
     *
     * @throws IllegalStateException 이미 사용됐거나 만료/잠금된 경우
     */
    public void markUsed() {
        if (!isUsable()) {
            throw new IllegalStateException("Token is not usable");
        }
        this.usedAt = LocalDateTime.now();
    }

    /**
     * 토큰 무효화 (O-17: 신규 발급 시 기존 유효 토큰 invalidate)
     * - 이미 사용됐거나 이미 revoke된 경우 no-op
     */
    public void revoke() {
        if (this.revokedAt == null && this.usedAt == null) {
            this.revokedAt = LocalDateTime.now();
        }
    }

    /**
     * 실패 시도 기록 — 5회 누적 시 자동 잠금 (H-3)
     */
    public void recordFailedAttempt() {
        this.failedAttempts = (this.failedAttempts == null ? 0 : this.failedAttempts) + 1;
        if (this.failedAttempts >= MAX_FAILED_ATTEMPTS) {
            this.lockedAt = LocalDateTime.now();
        }
    }
}
