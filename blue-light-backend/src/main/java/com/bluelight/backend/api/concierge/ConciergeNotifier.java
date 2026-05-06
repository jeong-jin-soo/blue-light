package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Concierge 신청 관련 알림 오케스트레이터 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * <p>
 * <ul>
 *   <li>N1  : 신청자에게 접수 확인 + 계정 설정 링크 (신규 C1 / PENDING C3)</li>
 *   <li>N1-Alt: 신청자에게 접수 확인 + 이미 활성 계정 연결 (C2)</li>
 *   <li>N2  : Admin/Concierge Manager 모두에게 신규 접수 알림 (이메일 + 인앱)</li>
 * </ul>
 * <p>
 * 알림 발송은 반드시 트랜잭션 커밋 이후({@code afterCommit})에 일어나도록 보장한다 —
 * 롤백된 상태가 수신자에게 통보되는 사고 차단 (9584c6c 교훈).
 * 개별 발송 실패는 catch-and-log로 격리하여 비즈니스 트랜잭션 무결성을 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConciergeNotifier {

    private final NotificationService notificationService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final SystemSettingRepository systemSettingRepository;

    @Value("${concierge.account-setup.base-url}")
    private String setupBaseUrl;

    private static final DateTimeFormatter EXPIRES_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'SGT'");
    private static final ZoneId SG_ZONE = ZoneId.of("Asia/Singapore");

    /**
     * 신청 제출 성공 시 호출 — afterCommit 훅 등록.
     *
     * @param conciergeRequestSeq  저장된 ConciergeRequest의 seq
     * @param applicantEmail       신청자 이메일 (정규화된 소문자)
     * @param applicantFullName    신청자 이름
     * @param publicCode           공개 코드 (C-YYYY-NNNN)
     * @param setupTokenUuid       AccountSetup 토큰 UUID (C2 케이스는 null)
     * @param expiresAt            토큰 만료 시각 (C2는 null)
     * @param resolverCase         C1/C2/C3 중 하나
     */
    public void notifySubmitted(Long conciergeRequestSeq,
                                 String applicantEmail,
                                 String applicantFullName,
                                 String publicCode,
                                 String setupTokenUuid,
                                 LocalDateTime expiresAt,
                                 ConciergeCaseResolver.Case resolverCase) {
        afterCommit(() -> {
            safeSendApplicantEmail(applicantEmail, applicantFullName, setupTokenUuid, expiresAt, resolverCase);
            safeCreateApplicantNotification(conciergeRequestSeq, applicantEmail, publicCode);
            safeNotifyStaff(conciergeRequestSeq, publicCode, applicantFullName, applicantEmail);
        });
    }

    // ─── 신청자 이메일 (N1 / N1-Alt) ─────────────────────────────

    private void safeSendApplicantEmail(String email, String name, String token,
                                         LocalDateTime expiresAt,
                                         ConciergeCaseResolver.Case c) {
        if (!hasEmail(email)) return;
        try {
            if (c == ConciergeCaseResolver.Case.C2_EXISTING_ACTIVE) {
                emailService.sendConciergeRequestReceivedExistingUserEmail(email, name);
            } else {
                // C1, C3 — 활성화 링크 포함 N1
                String setupUrl = setupBaseUrl + "/setup-account/" + token;
                String expStr = expiresAt == null ? "" :
                    expiresAt.atZone(SG_ZONE).format(EXPIRES_FMT);
                emailService.sendConciergeRequestReceivedEmail(email, name, setupUrl, expStr);
            }
        } catch (Exception e) {
            log.warn("Concierge applicant email send failed (suppressed): email={}, case={}, err={}",
                email, c, e.getMessage());
        }
    }

    // ─── 신청자 인앱 알림 ─────────────────────────────

    private void safeCreateApplicantNotification(Long conciergeRequestSeq, String email, String publicCode) {
        try {
            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) return;
            notificationService.createNotification(
                user.getUserSeq(),
                NotificationType.CONCIERGE_REQUEST_SUBMITTED,
                "Concierge request received",
                "Your Kaki Concierge request (" + publicCode
                    + ") has been received. A manager will contact you within 24 hours.",
                "CONCIERGE_REQUEST", conciergeRequestSeq);
        } catch (Exception e) {
            log.warn("Concierge applicant in-app notification failed (suppressed): email={}, err={}",
                email, e.getMessage());
        }
    }

    // ─── 스태프 이메일 + 인앱 (N2) ─────────────────────────────

    private void safeNotifyStaff(Long conciergeRequestSeq, String publicCode,
                                  String applicantName, String applicantEmail) {
        List<User> staff;
        try {
            staff = userRepository.findByRoleInAndStatus(
                List.of(UserRole.ADMIN, UserRole.CONCIERGE_MANAGER),
                UserStatus.ACTIVE);
        } catch (Exception e) {
            log.warn("Concierge staff lookup failed (suppressed): err={}", e.getMessage());
            return;
        }

        for (User s : staff) {
            try {
                if (hasEmail(s.getEmail())) {
                    emailService.sendConciergeStaffNewRequestEmail(
                        s.getEmail(), s.getFullName(), publicCode, applicantName, applicantEmail);
                }
            } catch (Exception e) {
                log.warn("Concierge staff email failed (suppressed): userSeq={}, err={}",
                    s.getUserSeq(), e.getMessage());
            }
            try {
                notificationService.createNotification(
                    s.getUserSeq(),
                    NotificationType.CONCIERGE_REQUEST_SUBMITTED,
                    "New Kaki Concierge request",
                    publicCode + " — " + applicantName + " (" + applicantEmail + ")",
                    "CONCIERGE_REQUEST", conciergeRequestSeq);
            } catch (Exception e) {
                log.warn("Concierge staff in-app notification failed (suppressed): userSeq={}, err={}",
                    s.getUserSeq(), e.getMessage());
            }
        }
    }

    // ─── Quote 이메일 (Phase 1.5) ─────────────────────────────

    /**
     * 견적 이메일 발송 — 매니저가 통화 후 수수료 + 일정 + 메모를 기록하면 호출된다.
     * afterCommit 훅으로 발송하여 트랜잭션 롤백 시 메일 미발송 보장.
     */
    public void notifyQuoteSent(Long conciergeRequestSeq,
                                 String applicantEmail,
                                 String applicantName,
                                 String publicCode,
                                 BigDecimal quotedAmount,
                                 LocalDateTime callScheduledAt,
                                 String managerNote,
                                 String verificationPhrase) {
        // PayNow 설정값을 tx 내에서 fetch — afterCommit 훅에선 DB 세션이 닫힘
        String paynowUen = readSetting("payment_paynow_uen");
        String paynowName = readSetting("payment_paynow_name");

        afterCommit(() -> {
            if (!hasEmail(applicantEmail)) return;
            try {
                emailService.sendConciergeQuoteEmail(
                    applicantEmail, applicantName, publicCode,
                    quotedAmount, callScheduledAt, managerNote,
                    verificationPhrase, paynowUen, paynowName);
            } catch (Exception e) {
                log.warn("Concierge quote email send failed (suppressed): email={}, publicCode={}, err={}",
                    applicantEmail, publicCode, e.getMessage());
            }
            // 인앱 알림도 병행 발송
            try {
                User user = userRepository.findByEmail(applicantEmail).orElse(null);
                if (user != null) {
                    notificationService.createNotification(
                        user.getUserSeq(),
                        NotificationType.CONCIERGE_REQUEST_SUBMITTED,
                        "Quote ready for your concierge request",
                        "We have emailed you payment details for " + publicCode + ".",
                        "CONCIERGE_REQUEST", conciergeRequestSeq);
                }
            } catch (Exception e) {
                log.warn("Concierge quote in-app notification failed (suppressed): email={}, err={}",
                    applicantEmail, e.getMessage());
            }
        });
    }

    private String readSetting(String key) {
        try {
            return systemSettingRepository.findById(key)
                .map(s -> s.getSettingValue())
                .orElse(null);
        } catch (Exception e) {
            log.warn("SystemSetting lookup failed for {}: {}", key, e.getMessage());
            return null;
        }
    }

    // ─── 내부 헬퍼 ─────────────────────────────

    /**
     * afterCommit 훅 등록. 활성 트랜잭션이 없으면 즉시 실행 (테스트/예외 경로 견고성).
     */
    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private static boolean hasEmail(String email) {
        return email != null && !email.isBlank();
    }
}
