package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.AccountSetupCompleteRequest;
import com.bluelight.backend.api.auth.dto.AccountSetupStatusResponse;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.EnumParser;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.ConsentAction;
import com.bluelight.backend.domain.user.ConsentSourceContext;
import com.bluelight.backend.domain.user.ConsentType;
import com.bluelight.backend.domain.user.LewGrade;
import com.bluelight.backend.domain.user.LewPaynowChangeLog;
import com.bluelight.backend.domain.user.LewPaynowChangeLogRepository;
import com.bluelight.backend.domain.user.PaynowChangeSourceContext;
import com.bluelight.backend.domain.user.PaynowType;
import com.bluelight.backend.domain.user.PaynowValidator;
import com.bluelight.backend.domain.user.SignupSource;
import com.bluelight.backend.domain.user.TermsVersion;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserConsentLog;
import com.bluelight.backend.domain.user.UserConsentLogRepository;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserStatus;
import com.bluelight.backend.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AccountSetup 플로우 비즈니스 로직 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage A).
 * <p>
 * 토큰 검증 → 비밀번호 설정 → User.status 전이 → 토큰 사용 처리 → 감사 로그 → 자동 로그인 JWT 발급.
 * <p>
 * PENDING_ACTIVATION 계정만 활성화 대상이지만, 정책상 ACTIVE 계정이 같은 플로우를 타더라도
 * 비밀번호는 변경되도록 허용한다 (향후 "재설정 대체 경로"로도 쓸 여지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSetupService {

    private final AccountSetupTokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final UserConsentLogRepository consentLogRepository;
    private final LewPaynowChangeLogRepository paynowChangeLogRepository;

    /**
     * 토큰 상태 조회 (Setup 페이지 진입 시 1단계).
     * 토큰이 유효하지 않으면 410 GONE. 마스킹된 이메일만 응답.
     */
    @Transactional(readOnly = true)
    public AccountSetupStatusResponse getStatus(String tokenUuid) {
        AccountSetupToken token = tokenService.validate(tokenUuid);
        User user = token.getUser();
        return AccountSetupStatusResponse.builder()
            .maskedEmail(maskEmail(user.getEmail()))
            .expiresAt(token.getExpiresAt())
            .requiresLewDetails(token.getSource() == AccountSetupTokenSource.LEW_INVITATION)
            .build();
    }

    /**
     * 비밀번호 설정 완료 처리.
     * <ul>
     *   <li>토큰 usable 검증</li>
     *   <li>password == passwordConfirm 일치 검증</li>
     *   <li>비밀번호 정책 검증 (8~72자, 영문+숫자, 공백 금지)</li>
     *   <li>BCrypt 해싱 후 저장</li>
     *   <li>PENDING_ACTIVATION이면 User.activate() 호출 (status=ACTIVE, activatedAt/firstLoggedInAt)</li>
     *   <li>토큰 markUsed</li>
     *   <li>감사 로그: ACCOUNT_ACTIVATED (동기, REQUIRES_NEW)</li>
     *   <li>JWT 발급 후 TokenResponse 반환 (자동 로그인)</li>
     * </ul>
     */
    @Transactional
    public TokenResponse complete(String tokenUuid, AccountSetupCompleteRequest request,
                                  HttpServletRequest httpRequest) {
        AccountSetupToken token = tokenService.validate(tokenUuid);
        User user = token.getUser();

        // 비밀번호 확인 일치 검증
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw new BusinessException("Password confirmation mismatch",
                HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH");
        }

        // 비밀번호 정책
        validatePasswordPolicy(request.getPassword());

        // ── LEW 초대 토큰 분기 (LEW_INVITATION 일 때만) ──
        // 컨시어지/로그인 활성화 토큰은 아래를 전혀 타지 않는다 (R-7/AC-11 회귀 안전).
        boolean isLewInvitation = token.getSource() == AccountSetupTokenSource.LEW_INVITATION;
        String lewLicenceNo = null;
        LewGrade lewGrade = null;
        PaynowType paynowType = null;
        String paynowValue = null;

        if (isLewInvitation) {
            // 입력 검증(필수/형식) 오류는 10회까지 허용 후 토큰 잠금 — 별도 트랜잭션으로 증분 보존(D-9).
            try {
                if (!Boolean.TRUE.equals(request.getPdpaConsent())) {
                    throw new BusinessException("PDPA consent is required",
                        HttpStatus.BAD_REQUEST, "PDPA_CONSENT_REQUIRED");
                }
                if (request.getLewLicenceNo() == null || request.getLewLicenceNo().isBlank()) {
                    throw new BusinessException("LEW licence number is required",
                        HttpStatus.BAD_REQUEST, "LEW_LICENCE_NO_REQUIRED");
                }
                if (request.getLewGrade() == null || request.getLewGrade().isBlank()) {
                    throw new BusinessException("LEW grade is required",
                        HttpStatus.BAD_REQUEST, "LEW_GRADE_REQUIRED");
                }
                lewGrade = EnumParser.parse(LewGrade.class, request.getLewGrade(), "INVALID_LEW_GRADE");
                paynowType = EnumParser.parse(PaynowType.class, request.getPaynowType(), "INVALID_PAYNOW_TYPE");
                paynowValue = request.getPaynowValue();
                PaynowValidator.validate(paynowType, paynowValue); // 형식 오류 시 400
            } catch (BusinessException e) {
                tokenService.recordInputValidationFailure(tokenUuid); // REQUIRES_NEW — 롤백에도 보존
                throw e;
            }

            // 면허 중복은 입력 형식 오류가 아니라 충돌 → 잠금 카운트 미포함, 토큰은 살려 재시도/문의 가능(AC-7).
            lewLicenceNo = request.getLewLicenceNo().trim();
            if (userRepository.existsByLewLicenceNo(lewLicenceNo)) {
                throw new BusinessException("LEW licence number is already registered",
                    HttpStatus.CONFLICT, "DUPLICATE_LEW_LICENCE_NO");
            }
        }

        // 해싱 + 저장
        user.changePassword(passwordEncoder.encode(request.getPassword()));

        // PENDING_ACTIVATION → ACTIVE 전이 (이미 ACTIVE인 경우 멱등)
        if (user.getStatus() == UserStatus.PENDING_ACTIVATION) {
            user.activate();
        }

        // 이메일 자동 인증: 토큰 링크 클릭 자체가 이메일 소유 증명이므로 verifyEmail 동시 처리
        // (Concierge 가입자가 AccountSetup 직후 대시보드 진입 가능하도록)
        if (!user.isEmailVerified()) {
            user.verifyEmail();
        }

        // LEW 초대: 면허/등급 확정 + 자동 승인(D-1), PayNow 세팅 + 이력, PDPA/TERMS 동의 기록.
        if (isLewInvitation) {
            LocalDateTime now = LocalDateTime.now();
            user.completeInvitedLewSetup(lewLicenceNo, lewGrade);
            user.changePaynow(paynowType, paynowValue);
            // 초대 계정은 빌더에서 pdpaConsentAt 미설정 → 동의 시점에 기록(hasPdpaConsent 일관성, 자가가입/컨시어지와 동일).
            user.grantPdpaConsent(now);
            user.recordSignupConsent(now, TermsVersion.CURRENT, SignupSource.ADMIN_INVITE);

            String ip = extractIp(httpRequest);
            String ua = httpRequest != null ? httpRequest.getHeader("User-Agent") : null;
            recordConsent(user, ConsentType.PDPA, ip, ua);
            recordConsent(user, ConsentType.TERMS, ip, ua);

            paynowChangeLogRepository.save(LewPaynowChangeLog.builder()
                .user(user)
                .oldType(null).oldValue(null)
                .newType(paynowType).newValue(user.getPaynowValue())
                .changedBy(user.getUserSeq())
                .sourceContext(PaynowChangeSourceContext.ACCOUNT_SETUP)
                .ipAddress(ip).userAgent(ua)
                .build());
        }

        tokenService.markUsed(token);

        // 감사 로그 (동기, REQUIRES_NEW — REST 응답 전 영속화 보장)
        auditLogService.log(
            user.getUserSeq(), user.getEmail(), user.getRole().name(),
            AuditAction.ACCOUNT_ACTIVATED, AuditCategory.AUTH,
            "user", user.getUserSeq().toString(),
            "Account activated via setup token", null, null,
            extractIp(httpRequest),
            httpRequest != null ? httpRequest.getHeader("User-Agent") : null,
            "POST", "/api/public/account-setup/{token}", 200);

        // JWT 발급 (자동 로그인) — AuthService.createTokenResponse와 동일 시그니처
        boolean approved = user.isApproved();
        boolean emailVerified = user.isEmailVerified();
        String accessToken = jwtTokenProvider.createToken(
            user.getUserSeq(),
            user.getEmail(),
            user.getRole().name(),
            approved,
            emailVerified
        );

        log.info("Account setup complete: userSeq={}, status={}", user.getUserSeq(), user.getStatus());

        return TokenResponse.of(
            accessToken,
            jwtTokenProvider.getExpirationInSeconds(),
            user.getUserSeq(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getRole().name(),
            approved,
            emailVerified
        );
    }

    /**
     * LEW 초대 셋업 시 PDPA/TERMS GRANTED 동의를 {@link UserConsentLog} 에 기록 (감사 증적).
     * 발생 맥락은 {@link ConsentSourceContext#ADMIN_INVITE} (D-8 본인 동의).
     */
    private void recordConsent(User user, ConsentType type, String ip, String ua) {
        consentLogRepository.save(UserConsentLog.builder()
            .user(user)
            .consentType(type)
            .action(ConsentAction.GRANTED)
            .documentVersion(TermsVersion.CURRENT)
            .sourceContext(ConsentSourceContext.ADMIN_INVITE)
            .ipAddress(ip)
            .userAgent(ua)
            .build());
    }

    /**
     * 비밀번호 정책: 8~72자(BCrypt 제한), 영문+숫자 최소 1종씩, 공백 금지.
     * (기존 비밀번호 재설정과 동일 수준으로 유지)
     */
    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new BusinessException("Password must be 8~72 characters",
                HttpStatus.BAD_REQUEST, "PASSWORD_POLICY_VIOLATION");
        }
        if (!password.matches(".*[a-zA-Z].*") || !password.matches(".*[0-9].*")) {
            throw new BusinessException("Password must contain letters and numbers",
                HttpStatus.BAD_REQUEST, "PASSWORD_POLICY_VIOLATION");
        }
        if (password.matches(".*\\s.*")) {
            throw new BusinessException("Password must not contain whitespace",
                HttpStatus.BAD_REQUEST, "PASSWORD_POLICY_VIOLATION");
        }
    }

    /**
     * 이메일 마스킹 규칙:
     * - 로컬파트 2자 이상: 첫 글자 + "***" + "@도메인" (예: "ab@b.com" → "a***@b.com")
     * - 로컬파트 1자 또는 '@'가 없음: "***@도메인" (도메인 없으면 "***")
     */
    static String maskEmail(String email) {
        if (email == null) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at < 0) {
            return "***";
        }
        if (at <= 1) {
            return "***" + email.substring(at);
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String extractIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
