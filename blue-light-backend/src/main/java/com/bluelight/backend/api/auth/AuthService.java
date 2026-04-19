package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.ForgotPasswordRequest;
import com.bluelight.backend.api.auth.dto.LoginRequest;
import com.bluelight.backend.api.auth.dto.ResetPasswordRequest;
import com.bluelight.backend.api.auth.dto.SignupRequest;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.EnumParser;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.*;
import com.bluelight.backend.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 인증 서비스
 * - 회원가입, 로그인, 비밀번호 재설정 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SystemSettingRepository systemSettingRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailService emailService;
    private final AuditLogService auditLogService;

    @Value("${password-reset.token-expiry-minutes:60}")
    private int tokenExpiryMinutes;

    @Value("${password-reset.base-url:http://localhost:5174}")
    private String resetBaseUrl;

    /**
     * 미존재/DELETED 이메일에 대한 타이밍 동등성용 더미 BCrypt 해시 (60자 표준).
     * {@code passwordEncoder.matches()}가 항상 false 반환하면서 실제 BCrypt 연산 CPU 비용 발생 →
     * 응답 시간 편차 축소 (★ v1.5 §4.4 H-1 완화).
     * <p>
     * 이 해시는 실제 사용자의 비밀번호 해시와 일치할 수 없다(임의 샘플 해시).
     */
    private static final String DUMMY_BCRYPT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * 회원가입
     *
     * @param request 회원가입 요청 정보
     * @return 토큰 응답
     */
    @Transactional
    public TokenResponse signup(SignupRequest request) {
        // PDPA 동의 검증
        if (request.getPdpaConsent() == null || !request.getPdpaConsent()) {
            throw new BusinessException(
                    "You must agree to the Privacy Policy to continue",
                    HttpStatus.BAD_REQUEST,
                    "PDPA_CONSENT_REQUIRED"
            );
        }

        // 이메일 중복 검사
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email is already in use", HttpStatus.CONFLICT, "DUPLICATE_EMAIL");
        }

        // 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        // 역할 결정 (LEW 선택 시 LEW, 그 외 APPLICANT — ADMIN 가입 불가)
        UserRole selectedRole = "LEW".equalsIgnoreCase(request.getRole())
                ? UserRole.LEW : UserRole.APPLICANT;

        // LEW 가입이 닫혀 있으면 LEW 가입 차단
        if (selectedRole == UserRole.LEW && !isLewRegistrationOpen()) {
            throw new BusinessException(
                    "LEW registration is currently closed",
                    HttpStatus.BAD_REQUEST,
                    "LEW_REGISTRATION_CLOSED"
            );
        }

        // LEW 역할 선택 시 면허번호 + 등급 필수 검증
        if (selectedRole == UserRole.LEW) {
            if (request.getLewLicenceNo() == null || request.getLewLicenceNo().isBlank()) {
                throw new BusinessException(
                        "LEW licence number is required for LEW registration",
                        HttpStatus.BAD_REQUEST,
                        "LEW_LICENCE_NO_REQUIRED"
                );
            }
            if (request.getLewGrade() == null || request.getLewGrade().isBlank()) {
                throw new BusinessException(
                        "LEW grade is required for LEW registration",
                        HttpStatus.BAD_REQUEST,
                        "LEW_GRADE_REQUIRED"
                );
            }
        }

        // LEW 등급 파싱
        LewGrade lewGrade = null;
        if (selectedRole == UserRole.LEW && request.getLewGrade() != null) {
            lewGrade = EnumParser.parse(LewGrade.class, request.getLewGrade(), "INVALID_LEW_GRADE");
        }

        // 이메일 인증 활성화 여부 확인
        boolean emailVerificationEnabled = isEmailVerificationEnabled();

        // 이메일 인증 토큰 생성 (인증 활성화 시)
        String emailVerificationToken = emailVerificationEnabled ? UUID.randomUUID().toString() : null;

        // 사용자 생성 (LEW는 승인 대기 상태로 시작)
        User user = User.builder()
                .email(request.getEmail())
                .password(encodedPassword)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                // Phase 1: phone/companyName/uen/designation은 가입 시 수집하지 않는다.
                // ProfilePage에서 선택 입력 받는다 (AC-S1~S4).
                .role(selectedRole)
                .approvedStatus(selectedRole == UserRole.LEW ? ApprovalStatus.PENDING : null)
                .lewLicenceNo(selectedRole == UserRole.LEW ? request.getLewLicenceNo() : null)
                .lewGrade(lewGrade)
                .emailVerified(!emailVerificationEnabled)
                .emailVerificationToken(emailVerificationToken)
                .pdpaConsentAt(LocalDateTime.now())
                .build();

        User savedUser = userRepository.save(user);
        log.info("회원가입 완료: userSeq={}, email={}, emailVerified={}",
                savedUser.getUserSeq(), savedUser.getEmail(), savedUser.isEmailVerified());

        // 이메일 인증 메일 발송
        if (emailVerificationEnabled) {
            String verificationLink = resetBaseUrl + "/verify-email?token=" + emailVerificationToken;
            emailService.sendEmailVerificationEmail(savedUser.getEmail(), savedUser.getFullName(), verificationLink);
        }

        // JWT 토큰 생성 및 반환
        return createTokenResponse(savedUser);
    }

    /**
     * 로그인 (★ Kaki Concierge v1.5 §4.4 H-1 재설계).
     * <p>
     * <b>1단계</b> — 비밀번호 검증 선행:
     * <ul>
     *   <li>이메일 존재 여부와 관계없이 {@code passwordEncoder.matches()} 호출 (미존재 → DUMMY_BCRYPT_HASH)</li>
     *   <li>status 분기는 비밀번호 일치 후에만 수행</li>
     * </ul>
     * <b>2단계</b> — 비번 실패 시 INVALID_CREDENTIALS + 감사 로그(UNKNOWN_EMAIL 또는 BAD_PASSWORD).
     * <p>
     * <b>3단계</b> — 비번 성공 시 status 분기:
     * <ul>
     *   <li>ACTIVE → JWT 발급 + LOGIN_SUCCESS</li>
     *   <li>PENDING_ACTIVATION → 401 ACCOUNT_PENDING_ACTIVATION</li>
     *   <li>SUSPENDED → 403 ACCOUNT_SUSPENDED</li>
     *   <li>DELETED → 401 INVALID_CREDENTIALS (존재 감춤, 감사 로그는 LOGIN_FAILED_DELETED)</li>
     * </ul>
     *
     * @param request     로그인 요청 정보
     * @param httpRequest IP/User-Agent 추출용 (null 허용 — 내부 호출 테스트용)
     * @return 토큰 응답 (ACTIVE인 경우만)
     */
    @Transactional
    public TokenResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        String rawPassword = request.getPassword() == null ? "" : request.getPassword();
        String ip = extractIp(httpRequest);
        String ua = userAgent(httpRequest);

        Optional<User> userOpt = userRepository.findByEmail(email);

        // 1단계: 비밀번호 검증 (미존재 이메일도 dummy hash로 BCrypt 1회 호출 — 타이밍 동등성)
        boolean passwordMatches;
        if (userOpt.isPresent()) {
            passwordMatches = passwordEncoder.matches(rawPassword, userOpt.get().getPassword());
        } else {
            passwordEncoder.matches(rawPassword, DUMMY_BCRYPT_HASH);
            passwordMatches = false;
        }

        // 2단계: 비밀번호 실패 → 감사 로그 분기 후 INVALID_CREDENTIALS
        if (!passwordMatches) {
            if (userOpt.isEmpty()) {
                auditLogService.logAsync(null, AuditAction.LOGIN_FAILED_UNKNOWN_EMAIL, AuditCategory.AUTH,
                    "User", null, "Login attempt for unknown email: " + email, null, null,
                    ip, ua, "POST", "/api/auth/login", 401);
            } else {
                User u = userOpt.get();
                auditLogService.logAsync(u.getUserSeq(), AuditAction.LOGIN_FAILED_BAD_PASSWORD, AuditCategory.AUTH,
                    "User", u.getUserSeq().toString(), "Bad password for " + email, null, null,
                    ip, ua, "POST", "/api/auth/login", 401);
            }
            throw new BusinessException(
                "Invalid email or password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        // 3단계: 비번 성공 → status 분기
        User user = userOpt.get();
        switch (user.getStatus()) {
            case ACTIVE:
                // fall through to JWT 발급
                break;
            case PENDING_ACTIVATION:
                throw new BusinessException(
                    "Account is pending activation. Please set up your password via the activation link.",
                    HttpStatus.UNAUTHORIZED, "ACCOUNT_PENDING_ACTIVATION");
            case SUSPENDED:
                throw new BusinessException(
                    "Account has been suspended. Please contact support.",
                    HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED");
            case DELETED:
                // 존재 감춤 — 미존재처럼 응답하되 감사 로그엔 실제 userSeq 기록
                auditLogService.logAsync(user.getUserSeq(), AuditAction.LOGIN_FAILED_DELETED, AuditCategory.AUTH,
                    "User", user.getUserSeq().toString(), "Login attempt on deleted account", null, null,
                    ip, ua, "POST", "/api/auth/login", 401);
                throw new BusinessException(
                    "Invalid email or password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        log.info("로그인 성공: userSeq={}, email={}", user.getUserSeq(), user.getEmail());

        // 이메일 인증 비활성화 상태이면서 미인증 사용자 → 자동 인증 처리 (기존 로직 보존)
        if (!user.isEmailVerified() && !isEmailVerificationEnabled()) {
            user.verifyEmail();
            log.info("이메일 인증 비활성화 상태 → 기존 사용자 자동 인증: userSeq={}", user.getUserSeq());
        }

        // LOGIN_SUCCESS 감사 로그 (서비스 레이어에서 단일 책임)
        auditLogService.logAsync(user.getUserSeq(), AuditAction.LOGIN_SUCCESS, AuditCategory.AUTH,
            "User", user.getUserSeq().toString(), "Login success: " + user.getEmail(), null, null,
            ip, ua, "POST", "/api/auth/login", 200);

        return createTokenResponse(user);
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

    /**
     * 비밀번호 재설정 요청 (이메일 발송)
     * - 보안: 이메일 존재 여부와 관계없이 동일한 응답 반환
     * - Rate limiting: 동일 사용자에게 5분 내 재발송 방지
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
            // Rate limiting: 마지막 토큰이 5분 이내면 차단
            boolean rateLimited = passwordResetTokenRepository.findTopByUserOrderByCreatedAtDesc(user)
                    .map(lastToken -> lastToken.getCreatedAt() != null
                            && lastToken.getCreatedAt().plusMinutes(5).isAfter(LocalDateTime.now()))
                    .orElse(false);

            if (rateLimited) {
                log.warn("Password reset rate limited for: {}", request.getEmail());
                return; // 조용히 무시 (보안)
            }

            // UUID 기반 토큰 생성
            String token = UUID.randomUUID().toString();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .user(user)
                    .token(token)
                    .expiresAt(LocalDateTime.now().plusMinutes(tokenExpiryMinutes))
                    .build();

            passwordResetTokenRepository.save(resetToken);

            // 비밀번호 재설정 링크 생성 및 이메일 발송
            String resetLink = resetBaseUrl + "/reset-password?token=" + token;
            emailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), resetLink);

            log.info("Password reset token created for: {}", request.getEmail());
        });

        // 이메일이 없더라도 동일한 응답 (보안)
    }

    /**
     * 비밀번호 재설정 실행
     * - 토큰 유효성 검증 후 비밀번호 변경
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new BusinessException(
                        "Invalid or expired reset link",
                        HttpStatus.BAD_REQUEST,
                        "INVALID_RESET_TOKEN"
                ));

        // 토큰 유효성 확인
        if (!resetToken.isValid()) {
            throw new BusinessException(
                    "This reset link has expired or already been used",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_RESET_TOKEN"
            );
        }

        // 비밀번호 변경
        User user = resetToken.getUser();
        user.changePassword(passwordEncoder.encode(request.getNewPassword()));

        // 토큰 사용 처리
        resetToken.markAsUsed();

        log.info("Password reset completed for: userSeq={}", user.getUserSeq());
    }

    /**
     * 가입 가능한 역할 목록 조회
     * - APPLICANT는 항상 포함
     * - LEW는 lew_registration_open 설정에 따라 포함/제외
     */
    public Map<String, Object> getSignupOptions() {
        boolean lewOpen = isLewRegistrationOpen();
        List<String> availableRoles = new ArrayList<>();
        availableRoles.add("APPLICANT");
        if (lewOpen) {
            availableRoles.add("LEW");
        }
        return Map.of(
                "availableRoles", availableRoles,
                "lewRegistrationOpen", lewOpen
        );
    }

    /**
     * 이메일 인증 처리
     * - 토큰이 유효하면 emailVerified = true로 변경
     */
    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> new BusinessException(
                        "Invalid or expired verification link",
                        HttpStatus.BAD_REQUEST,
                        "INVALID_VERIFICATION_TOKEN"
                ));

        if (user.isEmailVerified()) {
            throw new BusinessException(
                    "Email is already verified",
                    HttpStatus.BAD_REQUEST,
                    "ALREADY_VERIFIED"
            );
        }

        user.verifyEmail();
        log.info("Email verified: userSeq={}, email={}", user.getUserSeq(), user.getEmail());
    }

    /**
     * 인증 이메일 재발송
     * - 이미 인증된 경우 에러
     * - 인증 활성화 설정이 꺼져있으면 자동 인증 처리
     */
    @Transactional
    public void resendVerificationEmail(Long userSeq) {
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException(
                        "User not found",
                        HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND"
                ));

        if (user.isEmailVerified()) {
            throw new BusinessException(
                    "Email is already verified",
                    HttpStatus.BAD_REQUEST,
                    "ALREADY_VERIFIED"
            );
        }

        // 인증 비활성화 상태면 자동 인증
        if (!isEmailVerificationEnabled()) {
            user.verifyEmail();
            log.info("Email auto-verified (verification disabled): userSeq={}", userSeq);
            return;
        }

        // 새 토큰 생성 및 이메일 발송
        String newToken = UUID.randomUUID().toString();
        user.setEmailVerificationToken(newToken);

        String verificationLink = resetBaseUrl + "/verify-email?token=" + newToken;
        emailService.sendEmailVerificationEmail(user.getEmail(), user.getFullName(), verificationLink);

        log.info("Verification email resent: userSeq={}, email={}", userSeq, user.getEmail());
    }

    /**
     * 이메일 인증 기능 활성화 여부 확인
     */
    private boolean isEmailVerificationEnabled() {
        return systemSettingRepository.findById("email_verification_enabled")
                .map(s -> s.toBooleanValue())
                .orElse(false); // 설정값이 없으면 기본 비활성화
    }

    /**
     * LEW 가입 허용 여부 확인
     */
    private boolean isLewRegistrationOpen() {
        return systemSettingRepository.findById("lew_registration_open")
                .map(s -> s.toBooleanValue())
                .orElse(true); // 설정값이 없으면 기본 허용
    }

    /**
     * TokenResponse 생성
     */
    private TokenResponse createTokenResponse(User user) {
        boolean approved = user.isApproved();
        boolean emailVerified = user.isEmailVerified();
        String accessToken = jwtTokenProvider.createToken(
                user.getUserSeq(),
                user.getEmail(),
                user.getRole().name(),
                approved,
                emailVerified
        );

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
}
