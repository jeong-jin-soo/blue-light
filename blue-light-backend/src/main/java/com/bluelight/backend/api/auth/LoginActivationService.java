package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Login Activation 플로우 (★ Kaki Concierge v1.5 §4.4 옵션 B, 유일).
 * <p>
 * PENDING_ACTIVATION 계정이 로그인 시도 시 "활성화 링크 재발송"을 요청하는
 * 공개 엔드포인트의 비즈니스 로직. <b>5케이스 모두 동일 응답</b>으로 이메일
 * 존재 여부 노출 방지:
 * <ul>
 *   <li>PENDING_ACTIVATION → 토큰 발급 + 이메일 발송 (실제 작업)</li>
 *   <li>ACTIVE → 무작업 (응답만 동일)</li>
 *   <li>SUSPENDED/DELETED → 무작업</li>
 *   <li>이메일 미존재 → dummy BCrypt + 무작업</li>
 * </ul>
 * 타이밍 동등성: 모든 케이스에서 {@code passwordEncoder.matches()} 1회 호출 +
 * 이메일 발송은 afterCommit 훅으로 분리하여 CPU/IO 편차 축소.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginActivationService {

    private final UserRepository userRepository;
    private final AccountSetupTokenService tokenService;
    private final EmailService emailService;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;

    @Value("${concierge.account-setup.base-url}")
    private String setupBaseUrl;

    /**
     * 미존재 이메일에도 BCrypt 연산 1회 실행 — 타이밍 동등성용 더미 해시.
     * AuthService.DUMMY_BCRYPT_HASH와 동일 상수(60자 BCrypt 표준).
     */
    private static final String DUMMY_BCRYPT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static final DateTimeFormatter EXPIRES_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'SGT'");
    private static final ZoneId SG_ZONE = ZoneId.of("Asia/Singapore");

    /**
     * 활성화 링크 발송 요청 처리.
     * <p>
     * 응답은 항상 void (컨트롤러가 200 + 고정 메시지 반환). 본 메서드는 예외를 던지지 않는다 —
     * 모든 내부 실패는 로그만 남기고 성공 응답을 유지하여 이메일 존재 여부를 숨긴다.
     */
    @Transactional
    public void requestActivation(String email, HttpServletRequest httpRequest) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        String ip = extractIp(httpRequest);
        String ua = userAgent(httpRequest);

        Optional<User> userOpt = userRepository.findByEmail(normalized);

        // 타이밍 동등성: 모든 경로에서 BCrypt.matches 1회 호출
        if (userOpt.isPresent()) {
            passwordEncoder.matches("dummy", userOpt.get().getPassword());
        } else {
            passwordEncoder.matches("dummy", DUMMY_BCRYPT_HASH);
        }

        if (userOpt.isEmpty()) {
            auditLogService.logAsync(null, AuditAction.ACCOUNT_ACTIVATION_REQUEST_NO_MATCH,
                AuditCategory.AUTH, "User", null,
                "Activation request for unknown email: " + normalized, null, null,
                ip, ua, "POST", "/api/auth/login/request-activation", 200);
            return;
        }

        User user = userOpt.get();

        if (user.getStatus() != UserStatus.PENDING_ACTIVATION) {
            // ACTIVE / SUSPENDED / DELETED — 무작업 (응답은 동일)
            auditLogService.logAsync(user.getUserSeq(),
                AuditAction.ACCOUNT_ACTIVATION_REQUEST_NO_MATCH, AuditCategory.AUTH,
                "User", user.getUserSeq().toString(),
                "Activation request ignored for status=" + user.getStatus(),
                null, null, ip, ua, "POST", "/api/auth/login/request-activation", 200);
            return;
        }

        // PENDING_ACTIVATION → 토큰 재발급 (O-17: 기존 유효 토큰 자동 revoke)
        AccountSetupToken token = tokenService.issue(
            user, AccountSetupTokenSource.LOGIN_ACTIVATION, httpRequest);

        auditLogService.logAsync(user.getUserSeq(),
            AuditAction.ACCOUNT_ACTIVATION_REQUEST_SENT, AuditCategory.AUTH,
            "User", user.getUserSeq().toString(),
            "Activation link sent via login activation flow",
            null, null, ip, ua, "POST", "/api/auth/login/request-activation", 200);

        // afterCommit 훅으로 이메일 발송 분리 (DB 롤백 시 이메일 차단)
        registerEmailSend(user.getEmail(), user.getFullName(), token);
    }

    private void registerEmailSend(String email, String name, AccountSetupToken token) {
        Runnable send = () -> {
            try {
                String url = setupBaseUrl + "/setup-account/" + token.getTokenUuid();
                String exp = token.getExpiresAt().atZone(SG_ZONE).format(EXPIRES_FMT);
                emailService.sendAccountSetupLinkEmail(email, name, url, exp);
            } catch (Exception e) {
                log.warn("Login activation email failed (suppressed): email={}, err={}",
                    email, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private static String extractIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request != null ? request.getHeader("User-Agent") : null;
    }
}
