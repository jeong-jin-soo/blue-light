package com.bluelight.backend.api.auth;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenRepository;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AccountSetupToken 발급/검증/무효화 서비스 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage A).
 *
 * <ul>
 *   <li>O-17: 유저당 유효 토큰 1개만 유지 — 신규 발급 시 기존 활성 토큰 revoke</li>
 *   <li>H-3: 5회 실패 시 토큰 잠금 (엔티티 {@link AccountSetupToken#recordFailedAttempt} 내부 처리)</li>
 *   <li>TTL: 48시간 (고정, TOKEN_TTL_HOURS 상수)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSetupTokenService {

    private final AccountSetupTokenRepository tokenRepository;

    private static final int TOKEN_TTL_HOURS = 48;

    /**
     * 신규 토큰 발급. 기존 유효 토큰이 있으면 모두 revoke 후 새로 발급 (O-17).
     *
     * @param user    대상 사용자
     * @param source  발급 맥락 (CONCIERGE_ACCOUNT_SETUP / LOGIN_ACTIVATION)
     * @param request HttpServletRequest (IP, UA 기록용, nullable)
     * @return 발급된 토큰 엔티티 (영속화됨)
     */
    @Transactional
    public AccountSetupToken issue(User user, AccountSetupTokenSource source,
                                   HttpServletRequest request) {
        // O-17: 기존 유효 토큰 모두 revoke
        List<AccountSetupToken> activeTokens = tokenRepository.findActiveTokensByUser(user.getUserSeq());
        for (AccountSetupToken old : activeTokens) {
            old.revoke();
        }

        String ip = request != null ? extractIp(request) : null;
        String ua = request != null ? request.getHeader("User-Agent") : null;

        AccountSetupToken token = AccountSetupToken.builder()
            .tokenUuid(UUID.randomUUID().toString())
            .user(user)
            .source(source)
            .expiresAt(LocalDateTime.now().plusHours(TOKEN_TTL_HOURS))
            .requestingIp(ip)
            .requestingUserAgent(ua)
            .build();

        AccountSetupToken saved = tokenRepository.save(token);
        log.info("AccountSetupToken issued: userSeq={}, source={}, revokedOld={}",
            user.getUserSeq(), source, activeTokens.size());
        return saved;
    }

    /**
     * 토큰 UUID 검증.
     * <p>
     * - 존재하지 않으면 410 GONE(TOKEN_INVALID)
     * - 사용됨/잠김/무효화/만료 시 410 GONE + 세부 코드 (TOKEN_ALREADY_USED / TOKEN_LOCKED / TOKEN_REVOKED / TOKEN_EXPIRED)
     * <p>
     * 실패 시도 기록(H-3)은 Service 호출자에서 {@link #recordFailure(AccountSetupToken)}로 명시 호출.
     * 단순 조회 경로(GET status)와 쓰기 경로(POST complete)를 구분하기 위함.
     *
     * @return 유효한 토큰 엔티티 (아직 markUsed 안 됨)
     */
    @Transactional(readOnly = true)
    public AccountSetupToken validate(String tokenUuid) {
        AccountSetupToken token = tokenRepository.findByTokenUuid(tokenUuid)
            .orElseThrow(() -> new BusinessException(
                "Invalid setup token", HttpStatus.GONE, "TOKEN_INVALID"));

        if (!token.isUsable()) {
            String reason = token.getUsedAt() != null ? "TOKEN_ALREADY_USED"
                : token.getLockedAt() != null ? "TOKEN_LOCKED"
                : token.getRevokedAt() != null ? "TOKEN_REVOKED"
                : "TOKEN_EXPIRED";
            throw new BusinessException("Setup token is not usable", HttpStatus.GONE, reason);
        }
        return token;
    }

    /**
     * 토큰 사용 완료 처리 (비밀번호 설정 성공 시 호출).
     */
    @Transactional
    public void markUsed(AccountSetupToken token) {
        token.markUsed();
    }

    /**
     * 실패 시도 기록. 5회 누적 시 자동 잠금 (H-3).
     */
    @Transactional
    public void recordFailure(AccountSetupToken token) {
        token.recordFailedAttempt();
    }

    private static String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
