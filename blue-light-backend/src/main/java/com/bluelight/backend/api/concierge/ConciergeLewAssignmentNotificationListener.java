package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ★ Concierge 강화 + 별도 수금 PR-3 — LEW 배정 직후 인앱 알림 + 이메일 발송 리스너.
 *
 * <p>스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §3 S2/S3, §10 AC-L1/L4.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 본 알림은 ConciergeRequest.assignedLewSeq + status 전이의 부수 효과다. 외부 의존(SMTP, NotificationService
 * REQUIRES_NEW 트랜잭션) 일시 오류가 LEW 배정 트랜잭션을 롤백시켜선 안 된다 (kVA PR-3 동일 원칙).
 *
 * <h3>책임</h3>
 * <ol>
 *   <li>{@code selfAssigned=true} (D6=A): LEW 측 알림 음소거 (스펙 §3 S2 — 본인이 본인에게 할당하는데
 *       알림을 또 보내는 건 노이즈). 단 audit 은 service 가 별도로 기록.</li>
 *   <li>새 LEW 인앱 알림 ({@link NotificationType#CONCIERGE_LEW_ASSIGNED_LEW}, referenceType=CONCIERGE_REQUEST).</li>
 *   <li>새 LEW 이메일 (PDPA 최소화 — Subject 는 publicCode 만).</li>
 *   <li>{@code previousLewUserSeq} 가 non-null 이면 이전 LEW 에게 unassign 인앱 + 이메일 (스펙 §10 AC-L4).</li>
 * </ol>
 *
 * <h3>실패 격리</h3>
 * 모든 단계에 try/catch — 한 LEW 의 알림 실패가 다른 LEW 또는 결제 트랜잭션에 영향을 주지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConciergeLewAssignmentNotificationListener {

    /** 인앱 알림 referenceType (NotificationsPage 라우팅 키) */
    static final String REFERENCE_TYPE_CONCIERGE = "CONCIERGE_REQUEST";

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConciergeLewAssigned(ConciergeLewAssignedEvent event) {
        try {
            // ── 1) 새 LEW 알림 (셀프 할당이면 음소거) ──
            if (!event.isSelfAssigned()) {
                notifyNewLew(event);
            } else {
                log.info("Concierge LEW assignment notification muted (self-assign): conciergeRequestSeq={}, lewSeq={}",
                        event.getConciergeRequestSeq(), event.getNewLewUserSeq());
            }

            // ── 2) 이전 LEW unassign 알림 (재할당 시) ──
            if (event.getPreviousLewUserSeq() != null
                    && !event.getPreviousLewUserSeq().equals(event.getNewLewUserSeq())) {
                notifyPreviousLew(event);
            }
        } catch (RuntimeException ex) {
            log.error("ConciergeLewAssignmentNotificationListener failed (swallowed): conciergeRequestSeq={}, err={}",
                    event.getConciergeRequestSeq(), ex.getMessage(), ex);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 새 LEW 알림
    // ────────────────────────────────────────────────────────────

    private void notifyNewLew(ConciergeLewAssignedEvent event) {
        Long lewSeq = event.getNewLewUserSeq();
        User lew;
        try {
            lew = userRepository.findById(lewSeq).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Concierge LEW assignment — failed to load new LEW: lewSeq={}, err={}",
                    lewSeq, ex.getMessage());
            return;
        }
        if (lew == null) {
            log.warn("Concierge LEW assignment — new LEW user not found: lewSeq={}", lewSeq);
            return;
        }
        boolean reassigned = event.getPreviousLewUserSeq() != null
                && !event.getPreviousLewUserSeq().equals(lewSeq);

        // 1) 인앱 알림.
        try {
            String title = reassigned
                    ? "Concierge request re-assigned to you"
                    : "New Concierge request assigned to you";
            String body = "#" + event.getPublicCode() + " — applicant " + safe(event.getSubmitterName());
            notificationService.createNotification(
                    lewSeq,
                    NotificationType.CONCIERGE_LEW_ASSIGNED_LEW,
                    title, body,
                    REFERENCE_TYPE_CONCIERGE, event.getConciergeRequestSeq());
        } catch (RuntimeException ex) {
            log.warn("Concierge LEW assignment in-app notification failed: lewSeq={}, conciergeRequestSeq={}, err={}",
                    lewSeq, event.getConciergeRequestSeq(), ex.getMessage());
        }

        // 2) 이메일.
        try {
            String emailTo = lew.getEmail();
            if (emailTo == null || emailTo.isBlank()) {
                log.warn("Concierge LEW assignment email skipped — no email: lewSeq={}", lewSeq);
                return;
            }
            String fullName = lew.getFullName();
            if (fullName == null || fullName.isBlank()) fullName = "LEW";
            emailService.sendConciergeLewAssignedEmail(
                    emailTo, fullName, event.getPublicCode(),
                    event.getSubmitterName(), event.getSubmitterEmail(), event.getSubmitterPhone(),
                    event.getMemo(), reassigned);
        } catch (RuntimeException ex) {
            log.warn("Concierge LEW assignment email failed: lewSeq={}, conciergeRequestSeq={}, err={}",
                    lewSeq, event.getConciergeRequestSeq(), ex.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    // 이전 LEW unassign 알림
    // ────────────────────────────────────────────────────────────

    private void notifyPreviousLew(ConciergeLewAssignedEvent event) {
        Long prevSeq = event.getPreviousLewUserSeq();
        User prev;
        try {
            prev = userRepository.findById(prevSeq).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Concierge LEW unassign — failed to load previous LEW: lewSeq={}, err={}",
                    prevSeq, ex.getMessage());
            return;
        }
        if (prev == null) {
            log.warn("Concierge LEW unassign — previous LEW user not found: lewSeq={}", prevSeq);
            return;
        }

        // 1) 인앱 알림 (동일 NotificationType, 메시지 분기 — 별도 enum 추가 비용 회피).
        try {
            notificationService.createNotification(
                    prevSeq,
                    NotificationType.CONCIERGE_LEW_ASSIGNED_LEW,
                    "Concierge request reassigned",
                    "#" + event.getPublicCode() + " has been reassigned to another LEW.",
                    REFERENCE_TYPE_CONCIERGE, event.getConciergeRequestSeq());
        } catch (RuntimeException ex) {
            log.warn("Concierge LEW unassign in-app notification failed: lewSeq={}, conciergeRequestSeq={}, err={}",
                    prevSeq, event.getConciergeRequestSeq(), ex.getMessage());
        }

        // 2) 이메일.
        try {
            String emailTo = prev.getEmail();
            if (emailTo == null || emailTo.isBlank()) {
                return;
            }
            String fullName = prev.getFullName();
            if (fullName == null || fullName.isBlank()) fullName = "LEW";
            emailService.sendConciergeLewUnassignedEmail(emailTo, fullName, event.getPublicCode());
        } catch (RuntimeException ex) {
            log.warn("Concierge LEW unassign email failed: lewSeq={}, conciergeRequestSeq={}, err={}",
                    prevSeq, event.getConciergeRequestSeq(), ex.getMessage());
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
