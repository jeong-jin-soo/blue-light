package com.bluelight.backend.api.application;

import com.bluelight.backend.api.admin.EmaSubmissionSettings;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.EmaSubmissionStatus;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * EMA 제출 리마인더 스케줄러 (PR-E5, ema-submission-tracking-spec.md §10).
 *
 * <p>SUBMITTED/RESUBMITTED 후 {@code system_settings.ema.reminder.days}(기본 3, {@link EmaSubmissionSettings}
 * 로 조회 — 하드코딩 금지) 무변동 건을 찾아 담당 LEW 에게 IN_APP 리마인더를 발행한다.
 * {@code NotificationService.createNotification}(REQUIRES_NEW + saveAndFlush)로 오케스트레이터/outbox/템플릿
 * 무관하게 직접 발행한다(허점#3 방향 a — {@link LewAssignmentNotificationListener} 와 동일 패턴).
 *
 * <h3>멱등 (1일 1회)</h3>
 * 대상 쿼리가 {@code ema_reminder_notified_at IS NULL OR < startOfToday} 를 포함하고, 발송 후
 * {@link Application#markEmaReminderNotified()} 로 NOW() 를 기록한다({@code markExpiryNotified} 패턴).
 * 같은 날 재실행돼도 이미 발송된 건은 대상에서 제외된다. 상태가 전이되면 엔티티가 플래그를 null 로
 * 리셋하므로 새 SUBMITTED/RESUBMITTED 구간이 다시 리마인더 대상이 된다.
 *
 * <h3>스케줄</h3>
 * {@link LicenseExpiryScheduler} 와 같은 일 1회 실행(기본 새벽 2시 30분, 만료 스케줄러와 시간대 분리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmaReminderScheduler {

    /** 인앱 알림 referenceType — 프론트가 /lew/applications/{id}/review 로 라우팅하는 키. */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final ApplicationRepository applicationRepository;
    private final NotificationService notificationService;
    private final EmaSubmissionSettings emaSubmissionSettings;

    /**
     * 매일 새벽 2시 30분 실행: EMA 제출 리마인더 발송.
     */
    @Scheduled(cron = "${ema-reminder.schedule-cron:0 30 2 * * ?}")
    @SchedulerLock(name = "processEmaReminders", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    public void processEmaReminders() {
        int reminderDays = emaSubmissionSettings.loadReminderDays(); // 설정 우선 — 하드코딩 금지
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusDays(reminderDays);
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();

        List<Application> targets = applicationRepository.findEmaReminderTargets(
                ApplicationStatus.IN_PROGRESS,
                EmaSubmissionStatus.SUBMITTED,
                EmaSubmissionStatus.RESUBMITTED,
                cutoff, startOfToday);

        if (targets.isEmpty()) {
            log.debug("No EMA reminder targets (reminderDays={})", reminderDays);
            return;
        }

        log.info("Sending EMA reminders for {} application(s) (reminderDays={})", targets.size(), reminderDays);

        for (Application app : targets) {
            try {
                User lew = app.getAssignedLew();
                if (lew == null) {
                    // 배정 LEW 없으면 수신자가 없다 — 플래그도 찍지 않아 배정 후 즉시 재대상이 되게 둔다.
                    log.debug("EMA reminder skipped — no assigned LEW: applicationSeq={}", app.getApplicationSeq());
                    continue;
                }
                String applicationCode = "APP-" + String.format("%06d", app.getApplicationSeq());
                String body = applicationCode
                        + " — EMA submission has been awaiting an outcome for over " + reminderDays + " day(s)."
                        + (app.getEmaReferenceNo() != null ? " Ref: " + app.getEmaReferenceNo() : "");
                notificationService.createNotification(
                        lew.getUserSeq(),
                        NotificationType.EMA_SUBMISSION_REMINDER_LEW,
                        "EMA submission reminder",
                        body,
                        REFERENCE_TYPE_APPLICATION, app.getApplicationSeq());

                app.markEmaReminderNotified(); // 멱등 — 같은 날 재발송 차단
                log.info("EMA reminder sent: applicationSeq={}, lewSeq={}, submittedAt={}",
                        app.getApplicationSeq(), lew.getUserSeq(), app.getEmaSubmittedAt());
            } catch (RuntimeException ex) {
                // 한 건 실패가 나머지 발송을 막지 않게 격리. 플래그를 찍지 않았으므로 다음 실행에서 재시도된다.
                log.warn("Failed to send EMA reminder: applicationSeq={}, err={}",
                        app.getApplicationSeq(), ex.getMessage());
            }
        }
    }
}
