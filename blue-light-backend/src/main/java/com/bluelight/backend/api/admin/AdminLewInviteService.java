package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminUserResponse;
import com.bluelight.backend.api.admin.dto.InviteLewRequest;
import com.bluelight.backend.api.auth.AccountSetupTokenService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.SignupSource;
import com.bluelight.backend.domain.user.TermsVersion;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * ADMIN LEW 초대 비즈니스 로직 (PR-1).
 * <p>
 * 컨시어지 계정 생성({@code ConciergeService} C1) + 활성화 링크 재발송
 * ({@code ConciergeManagerService.resendSetupEmail}) 패턴을 LEW 초대용으로 합성한다.
 * <ul>
 *   <li>초대: 신규 User(role=LEW, status=PENDING_ACTIVATION, approvedStatus=PENDING,
 *       signupSource=ADMIN_INVITE, 임시 비번 해시) 생성 → 셋업 토큰 발급(LEW_INVITATION) → 초대 이메일.</li>
 *   <li>D-1 자동승인은 셋업 <b>완료</b> 시점(PR-3)에 적용 — 본 단계는 PENDING 으로 둔다.</li>
 *   <li>면허/등급/PayNow 는 초대 시 받지 않음(D-2) — 셋업 화면에서 LEW 가 입력.</li>
 *   <li>이메일 본문은 PR-2 까지 컨시어지 셋업 링크 메서드를 임시 재사용한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLewInviteService {

    private final UserRepository userRepository;
    private final AccountSetupTokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Value("${concierge.account-setup.base-url}")
    private String setupBaseUrl;

    private static final DateTimeFormatter EXPIRES_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId SG_ZONE = ZoneId.of("Asia/Singapore");

    /**
     * LEW 초대 — 신규 LEW 계정 생성 + 셋업 토큰 발급 + 초대 이메일 발송.
     * D-4 이메일 중복 정책: 기존 계정이 있으면 케이스별 409.
     */
    @Transactional
    public AdminUserResponse invite(InviteLewRequest req, HttpServletRequest http) {
        // 로그인이 trim+lowercase 로 조회하므로 동일하게 정규화해 저장(본인 로그인 불가 #9 방지).
        String email = req.getEmail().trim().toLowerCase();

        userRepository.findByEmail(email).ifPresent(existing -> {
            if (existing.getRole() == UserRole.LEW) {
                throw new BusinessException(
                    "This email already belongs to a LEW account (use resend if the invite is pending)",
                    HttpStatus.CONFLICT, "EMAIL_ALREADY_LEW");
            }
            if (existing.getStatus() == UserStatus.PENDING_ACTIVATION) {
                throw new BusinessException(
                    "This email already has a pending account activation",
                    HttpStatus.CONFLICT, "EMAIL_PENDING_ACTIVATION");
            }
            throw new BusinessException(
                "This email already exists — change the existing user's role to LEW instead",
                HttpStatus.CONFLICT, "EMAIL_EXISTS_USE_CHANGE_ROLE");
        });

        LocalDateTime now = LocalDateTime.now();
        // 어떤 평문 비번과도 매칭 불가한 placeholder 해시 — 셋업 시 본인이 실제 비번 설정.
        String tempHash = passwordEncoder.encode("!PLACEHOLDER!" + UUID.randomUUID());

        User newUser = User.builder()
            .email(email)
            .password(tempHash)
            .firstName(req.getFirstName().trim())
            .lastName(req.getLastName().trim())
            .role(UserRole.LEW)
            // 셋업 완료 시 APPROVED 로 전환(D-1, PR-3). 초대 시점은 PENDING.
            .approvedStatus(ApprovalStatus.PENDING)
            .status(UserStatus.PENDING_ACTIVATION)
            .signupSource(SignupSource.ADMIN_INVITE)
            .build();
        User saved = userRepository.save(newUser);

        AccountSetupToken token = tokenService.issue(saved, AccountSetupTokenSource.LEW_INVITATION, http);
        log.info("LEW invited: userSeq={}, email={}", saved.getUserSeq(), saved.getEmail());

        dispatchInviteEmailAfterCommit(saved, token);
        return AdminUserResponse.from(saved);
    }

    /**
     * 초대 재발송 — PENDING_ACTIVATION 상태의 초대 LEW 에게만 허용(D-7).
     * 기존 활성 토큰은 발급 시 revoke 된다(O-17).
     */
    @Transactional
    public AdminUserResponse resendInvite(Long userId, HttpServletRequest http) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(
                "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (user.getRole() != UserRole.LEW || user.getSignupSource() != SignupSource.ADMIN_INVITE) {
            throw new BusinessException(
                "Only invited LEW accounts can be resent an invitation",
                HttpStatus.BAD_REQUEST, "NOT_INVITED_LEW");
        }
        if (user.getStatus() != UserStatus.PENDING_ACTIVATION) {
            throw new BusinessException(
                "Account is not pending activation",
                HttpStatus.CONFLICT, "NOT_PENDING");
        }

        AccountSetupToken token = tokenService.issue(user, AccountSetupTokenSource.LEW_INVITATION, http);
        log.info("LEW invitation resent: userSeq={}, email={}", user.getUserSeq(), user.getEmail());

        dispatchInviteEmailAfterCommit(user, token);
        return AdminUserResponse.from(user);
    }

    private void dispatchInviteEmailAfterCommit(User user, AccountSetupToken token) {
        final String email = user.getEmail();
        final String name = user.getFullName();
        final String setupUrl = setupBaseUrl + "/setup-account/" + token.getTokenUuid();
        final String expStr = token.getExpiresAt().atZone(SG_ZONE).format(EXPIRES_FMT);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeSend(email, name, setupUrl, expStr);
                }
            });
        } else {
            safeSend(email, name, setupUrl, expStr);
        }
    }

    private void safeSend(String email, String name, String url, String exp) {
        try {
            // PR-2 에서 sendLewInvitationEmail 로 교체. 그때까지 컨시어지 셋업 링크 메일 임시 재사용.
            emailService.sendAccountSetupLinkEmail(email, name, url, exp);
        } catch (Exception e) {
            log.warn("LEW invitation email failed (suppressed): email={}, err={}", email, e.getMessage());
        }
    }
}
