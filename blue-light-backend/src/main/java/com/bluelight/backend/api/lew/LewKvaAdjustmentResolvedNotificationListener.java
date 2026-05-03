package com.bluelight.backend.api.lew;

import com.bluelight.backend.domain.notification.NotificationRepository;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.api.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PR-3 / AC-L4: ADMIN 의 직접 kVA override 로 PENDING LEW 요청이 자동 해소될 때, 요청한 LEW 에게
 * 인앱 알림을 발송하는 리스너.
 *
 * <p>요청 LEW 가 배정 LEW 와 동일하면 PR-2 의 {@code KvaOverrideNotificationListener} 가 이미
 * {@link NotificationType#KVA_ADJUSTED_BY_ADMIN_LEW} 로 알림을 발송한다. 본 리스너는 그 알림 type 을
 * 재사용하되 멱등성 가드로 중복을 방지 — 결과적으로 "요청 LEW == 배정 LEW" 케이스에서는 PR-2 listener 가
 * 먼저 발송하면 본 리스너가 스킵, 거꾸로 본 리스너가 먼저 발송하면 PR-2 가 스킵.</p>
 *
 * <p>이메일은 PR-2 의 {@code sendKvaAdjustedToLewEmail} 과 별도 발송하지 않는다 — assignedLew 는
 * 이미 PR-2 가 처리하고, 요청 LEW 가 다른 사람이라면 비용·복잡도 대비 가치가 낮다(인앱 알림으로 충분).
 * 운영 중 요청 LEW != 배정 LEW 케이스가 빈번해지면 별도 이메일 추가 검토.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LewKvaAdjustmentResolvedNotificationListener {

    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLewRequestResolved(LewKvaRequestResolvedByOverrideEvent event) {
        Long applicationSeq = event.getApplicationSeq();
        Long lewSeq = event.getRequestingLewUserSeq();
        try {
            if (lewSeq == null) {
                return;
            }
            // 멱등성 — PR-2 listener 가 이미 발송했다면 스킵.
            boolean alreadyNotified = notificationRepository
                    .existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                            lewSeq,
                            NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW,
                            REFERENCE_TYPE_APPLICATION,
                            applicationSeq);
            if (alreadyNotified) {
                log.info("LEW request resolved notification skipped (already notified by PR-2): "
                                + "applicationSeq={}, lewSeq={}, lewRequestSeq={}",
                        applicationSeq, lewSeq, event.getLewRequestAdjustmentSeq());
                return;
            }

            String title = "kVA adjusted on application #" + applicationSeq;
            String body = buildBody(event);
            try {
                notificationService.createNotification(
                        lewSeq,
                        NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW,
                        title,
                        body,
                        REFERENCE_TYPE_APPLICATION,
                        applicationSeq);
            } catch (RuntimeException ex) {
                log.warn("LEW request resolved in-app notification failed: applicationSeq={}, lewSeq={}, err={}",
                        applicationSeq, lewSeq, ex.getMessage());
            }
        } catch (RuntimeException ex) {
            log.error("LewKvaAdjustmentResolvedNotificationListener failed: applicationSeq={}, lewSeq={}, err={}",
                    applicationSeq, lewSeq, ex.getMessage(), ex);
        }
    }

    private String buildBody(LewKvaRequestResolvedByOverrideEvent event) {
        String proposed = event.getProposedKva() != null ? event.getProposedKva() + "kVA" : "—";
        String applied = event.getAppliedKva() != null ? event.getAppliedKva() + "kVA" : "—";
        if (event.getProposedKva() != null
                && event.getAppliedKva() != null
                && !event.getProposedKva().equals(event.getAppliedKva())) {
            return "Admin updated kVA to " + applied + " (your suggestion: " + proposed + ")";
        }
        return "Admin applied your requested kVA: " + applied;
    }
}
