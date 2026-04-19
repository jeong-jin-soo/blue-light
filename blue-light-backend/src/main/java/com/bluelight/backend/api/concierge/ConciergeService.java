package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.AccountSetupTokenService;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestCreateRequest;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestCreateResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.ConsentAction;
import com.bluelight.backend.domain.user.ConsentSourceContext;
import com.bluelight.backend.domain.user.ConsentType;
import com.bluelight.backend.domain.user.SignupSource;
import com.bluelight.backend.domain.user.TermsVersion;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserConsentLog;
import com.bluelight.backend.domain.user.UserConsentLogRepository;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Concierge 신청 접수 서비스 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * <p>
 * <b>트랜잭션 원자성</b>: User + UserConsentLog 4~5건 + ConciergeRequest + AccountSetupToken이
 * 모두 한 트랜잭션으로 저장된다. DB 영속화 실패 시 전체 롤백.
 * <p>
 * <b>afterCommit 분리</b>: 이메일/인앱 알림은 {@link ConciergeNotifier}가 커밋 이후 발송하므로
 * 알림 실패로 DB 롤백되지 않는다.
 * <p>
 * <b>동시성 가드</b>: 동일 이메일 동시 신청으로 {@link DataIntegrityViolationException} 발생 시
 * 한 번만 재조회 + 재분기 (무한 루프 방지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConciergeService {

    private final ConciergeRequestRepository conciergeRepository;
    private final UserRepository userRepository;
    private final UserConsentLogRepository consentLogRepository;
    private final AccountSetupTokenService tokenService;
    private final PublicCodeGenerator publicCodeGenerator;
    private final ConciergeNotifier notifier;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public ConciergeRequestCreateResponse submitRequest(ConciergeRequestCreateRequest req,
                                                         HttpServletRequest httpRequest) {
        String email = normalizeEmail(req.getEmail());
        String ip = extractIp(httpRequest);
        String ua = httpRequest != null ? httpRequest.getHeader("User-Agent") : null;

        // 1. 기존 User 조회 → 케이스 판정
        User existing = userRepository.findByEmail(email).orElse(null);
        ConciergeCaseResolver.Case resolverCase = ConciergeCaseResolver.resolve(existing);

        // 거부 케이스
        rejectIfBlocking(resolverCase);

        // User resolve or create (동시성 재시도 포함)
        User applicant;
        try {
            applicant = resolveOrCreateApplicant(existing, resolverCase, req, email, ip, ua);
        } catch (DataIntegrityViolationException dup) {
            // 동시 신청 race: 재조회 후 케이스 재분기 (한 번만 재시도)
            log.warn("Concierge concurrent signup detected for {}, retrying once", email);
            User refetched = userRepository.findByEmail(email).orElse(null);
            ConciergeCaseResolver.Case retry = ConciergeCaseResolver.resolve(refetched);
            rejectIfBlocking(retry);
            resolverCase = retry;
            applicant = refetched;
            if (applicant == null) {
                // race 후 재조회도 null이면 진짜 다른 원인 — 재시도 포기
                throw new BusinessException("Concurrent signup failed",
                    HttpStatus.CONFLICT, "SIGNUP_RACE");
            }
        }

        // 2. ConciergeRequest 생성
        LocalDateTime now = LocalDateTime.now();
        ConciergeRequest cr = ConciergeRequest.builder()
            .publicCode(publicCodeGenerator.generate())
            .submitterName(req.getFullName())
            .submitterEmail(email)
            .submitterPhone(req.getMobileNumber())
            .memo(req.getMemo())
            .applicantUser(applicant)
            .pdpaConsentAt(now)
            .termsConsentAt(now)
            .signupConsentAt(now)
            .delegationConsentAt(now)
            .marketingOptIn(req.isMarketingOptIn())
            .build();
        cr = conciergeRepository.save(cr);

        // 3. 동의 로그 기록 (PDPA/TERMS/SIGNUP/DELEGATION 4종 + 선택 MARKETING)
        String termsVersion = req.getTermsVersion() != null ? req.getTermsVersion() : TermsVersion.CURRENT;
        recordConsent(applicant, ConsentType.PDPA, termsVersion, ip, ua);
        recordConsent(applicant, ConsentType.TERMS, termsVersion, ip, ua);
        recordConsent(applicant, ConsentType.SIGNUP, termsVersion, ip, ua);
        recordConsent(applicant, ConsentType.DELEGATION, termsVersion, ip, ua);
        if (req.isMarketingOptIn()) {
            recordConsent(applicant, ConsentType.MARKETING, termsVersion, ip, ua);
            applicant.optInMarketing(now);
        }

        // 4. AccountSetup 토큰 발급 (C1, C3만 — C2는 이미 ACTIVE 계정)
        AccountSetupToken setupToken = null;
        if (resolverCase == ConciergeCaseResolver.Case.C1_NEW_SIGNUP
            || resolverCase == ConciergeCaseResolver.Case.C3_EXISTING_PENDING) {
            setupToken = tokenService.issue(applicant,
                AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP, httpRequest);
            auditLogService.log(
                applicant.getUserSeq(), applicant.getEmail(), applicant.getRole().name(),
                AuditAction.ACCOUNT_SETUP_TOKEN_ISSUED, AuditCategory.AUTH,
                "user", applicant.getUserSeq().toString(),
                "Concierge account setup token issued", null, null,
                ip, ua, "POST", "/api/public/concierge/request", 200);
        }

        // 5. 감사 로그 (메인 — Concierge 접수)
        auditLogService.log(
            applicant.getUserSeq(), applicant.getEmail(), applicant.getRole().name(),
            AuditAction.CONCIERGE_REQUEST_SUBMITTED, AuditCategory.APPLICATION,
            "concierge_request", cr.getConciergeRequestSeq().toString(),
            "Concierge request submitted: " + cr.getPublicCode() + " case=" + resolverCase.name(),
            null, null, ip, ua, "POST", "/api/public/concierge/request", 201);

        // 6. afterCommit 훅 — 이메일/알림
        notifier.notifySubmitted(
            cr.getConciergeRequestSeq(),
            applicant.getEmail(),
            applicant.getFullName(),
            cr.getPublicCode(),
            setupToken != null ? setupToken.getTokenUuid() : null,
            setupToken != null ? setupToken.getExpiresAt() : null,
            resolverCase);

        // 7. 응답 조립
        boolean existingUser = resolverCase != ConciergeCaseResolver.Case.C1_NEW_SIGNUP;
        boolean accountSetupRequired = setupToken != null;
        String message = buildResponseMessage(resolverCase);

        return ConciergeRequestCreateResponse.builder()
            .publicCode(cr.getPublicCode())
            .status(cr.getStatus().name())
            .existingUser(existingUser)
            .accountSetupRequired(accountSetupRequired)
            .message(message)
            .build();
    }

    // ─── 내부 헬퍼 ─────────────────────────────

    private void rejectIfBlocking(ConciergeCaseResolver.Case resolverCase) {
        if (resolverCase == ConciergeCaseResolver.Case.C4_EXISTING_INELIGIBLE) {
            throw new BusinessException(
                "This email is not eligible for concierge service. Please contact support.",
                HttpStatus.CONFLICT, "ACCOUNT_NOT_ELIGIBLE");
        }
        if (resolverCase == ConciergeCaseResolver.Case.C5_STAFF_BLOCKED) {
            throw new BusinessException(
                "Staff accounts cannot use concierge service. Please contact administrator.",
                HttpStatus.UNPROCESSABLE_ENTITY, "STAFF_EMAIL_NOT_ALLOWED");
        }
    }

    /**
     * C1: 신규 User 생성 — status=PENDING_ACTIVATION, signupSource=CONCIERGE_REQUEST, 임시 비번 해시.
     * C2/C3: 기존 User 반환.
     */
    private User resolveOrCreateApplicant(User existing, ConciergeCaseResolver.Case resolverCase,
                                           ConciergeRequestCreateRequest req, String email,
                                           String ip, String ua) {
        if (resolverCase == ConciergeCaseResolver.Case.C1_NEW_SIGNUP) {
            // 임시 해시 (어떤 평문 비번과도 매칭 불가한 placeholder)
            String randomPlaceholder = "!PLACEHOLDER!" + UUID.randomUUID();
            String tempHash = passwordEncoder.encode(randomPlaceholder);
            String[] split = splitName(req.getFullName());
            LocalDateTime now = LocalDateTime.now();

            User newUser = User.builder()
                .email(email)
                .password(tempHash)
                .firstName(split[0])
                .lastName(split[1])
                .phone(req.getMobileNumber())
                .role(UserRole.APPLICANT)
                .status(UserStatus.PENDING_ACTIVATION)
                .signupSource(SignupSource.CONCIERGE_REQUEST)
                .pdpaConsentAt(now)
                .build();
            User saved = userRepository.save(newUser);

            // 회원가입 동의 기록 (signupConsentAt + termsVersion + source 통합)
            String termsVersion = req.getTermsVersion() != null ? req.getTermsVersion() : TermsVersion.CURRENT;
            saved.recordSignupConsent(now, termsVersion, SignupSource.CONCIERGE_REQUEST);

            auditLogService.log(
                saved.getUserSeq(), saved.getEmail(), saved.getRole().name(),
                AuditAction.CONCIERGE_ACCOUNT_AUTO_CREATED, AuditCategory.AUTH,
                "user", saved.getUserSeq().toString(),
                "Auto-created via Concierge request", null, null,
                ip, ua, "POST", "/api/public/concierge/request", 201);
            return saved;
        }
        // C2/C3: 기존 User 연결
        auditLogService.log(
            existing.getUserSeq(), existing.getEmail(), existing.getRole().name(),
            AuditAction.CONCIERGE_EXISTING_USER_LINKED, AuditCategory.AUTH,
            "user", existing.getUserSeq().toString(),
            "Existing user linked via Concierge request, case=" + resolverCase.name(),
            null, null, ip, ua, "POST", "/api/public/concierge/request", 200);
        return existing;
    }

    private void recordConsent(User user, ConsentType type, String version, String ip, String ua) {
        UserConsentLog entry = UserConsentLog.builder()
            .user(user)
            .consentType(type)
            .action(ConsentAction.GRANTED)
            .documentVersion(version)
            .sourceContext(ConsentSourceContext.CONCIERGE_REQUEST)
            .ipAddress(ip)
            .userAgent(ua)
            .build();
        consentLogRepository.save(entry);

        auditLogService.log(
            user.getUserSeq(), user.getEmail(), user.getRole().name(),
            AuditAction.USER_CONSENT_RECORDED, AuditCategory.DATA_PROTECTION,
            "user_consent_log", null,
            "Consent recorded: type=" + type + " version=" + version,
            null, null, ip, ua, "POST", "/api/public/concierge/request", 200);
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /**
     * 풀네임 → [firstName, lastName] 분리.
     * - 공백 없음: firstName=전체, lastName=""
     * - 공백 있음: 첫 공백 기준 앞/뒤 (두 번째 공백 이후는 lastName으로 묶음)
     */
    private static String[] splitName(String fullName) {
        if (fullName == null) {
            return new String[]{"", ""};
        }
        String trimmed = fullName.trim();
        int sp = trimmed.indexOf(' ');
        if (sp < 0) {
            return new String[]{trimmed, ""};
        }
        return new String[]{trimmed.substring(0, sp), trimmed.substring(sp + 1).trim()};
    }

    private static String buildResponseMessage(ConciergeCaseResolver.Case c) {
        return switch (c) {
            case C1_NEW_SIGNUP ->
                "Your concierge request is received. An account setup link has been sent to your email.";
            case C2_EXISTING_ACTIVE ->
                "Your concierge request is received. We linked it to your existing LicenseKaki account.";
            case C3_EXISTING_PENDING ->
                "Your concierge request is received. A new account setup link has been sent to your email.";
            default -> "";
        };
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
