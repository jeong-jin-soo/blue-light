package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.auth.dto.LoginRequest;
import com.bluelight.backend.api.auth.dto.SignupRequest;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 인증 서비스
 * - 회원가입, 로그인 처리
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

        // 사용자 생성 (LEW는 승인 대기 상태로 시작)
        User user = User.builder()
                .email(request.getEmail())
                .password(encodedPassword)
                .name(request.getName())
                .phone(request.getPhone())
                .role(selectedRole)
                .approvedStatus(selectedRole == UserRole.LEW ? ApprovalStatus.PENDING : null)
                .pdpaConsentAt(LocalDateTime.now())
                .build();

        User savedUser = userRepository.save(user);
        log.info("회원가입 완료: userSeq={}, email={}", savedUser.getUserSeq(), savedUser.getEmail());

        // JWT 토큰 생성 및 반환
        return createTokenResponse(savedUser);
    }

    /**
     * 로그인
     *
     * @param request 로그인 요청 정보
     * @return 토큰 응답
     */
    public TokenResponse login(LoginRequest request) {
        // 이메일로 사용자 조회
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(
                        "Invalid email or password",
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_CREDENTIALS"
                ));

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(
                    "Invalid email or password",
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS"
            );
        }

        log.info("로그인 성공: userSeq={}, email={}", user.getUserSeq(), user.getEmail());

        // JWT 토큰 생성 및 반환
        return createTokenResponse(user);
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
        String accessToken = jwtTokenProvider.createToken(
                user.getUserSeq(),
                user.getEmail(),
                user.getRole().name(),
                approved
        );

        return TokenResponse.of(
                accessToken,
                jwtTokenProvider.getExpirationInSeconds(),
                user.getUserSeq(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                approved
        );
    }
}
