package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.auth.dto.LoginRequest;
import com.bluelight.backend.api.auth.dto.SignupRequest;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.common.exception.BusinessException;
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

        // 사용자 생성
        User user = User.builder()
                .email(request.getEmail())
                .password(encodedPassword)
                .name(request.getName())
                .phone(request.getPhone())
                .role(UserRole.APPLICANT)  // 기본 역할: 신청자
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
     * TokenResponse 생성
     */
    private TokenResponse createTokenResponse(User user) {
        String accessToken = jwtTokenProvider.createToken(
                user.getUserSeq(),
                user.getEmail(),
                user.getRole().name()
        );

        return TokenResponse.of(
                accessToken,
                jwtTokenProvider.getExpirationInSeconds(),
                user.getUserSeq(),
                user.getEmail(),
                user.getName(),
                user.getRole().name()
        );
    }
}
