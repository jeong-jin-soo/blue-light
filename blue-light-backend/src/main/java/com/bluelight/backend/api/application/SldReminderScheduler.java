package com.bluelight.backend.api.application;

import com.bluelight.backend.api.notification.orchestrator.NotificationDispatchEvent;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.LicenseStatus;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SLD 미제출 리마인더 스케줄러.
 *
 * <p>라이선스가 발급(COMPLETED)됐으나 서비스에 SLD(={@link FileType#DRAWING_SLD} 파일)가 아직
 * 업로드되지 않은 건을, 발급 후 <b>2~3개월차</b> 구간에 <b>주 1회</b> 담당 LEW 에게 통지한다.
 * SLD 는 발급 후 3개월 이내에 EMA(ELISE)에 제출되어야 한다.</p>
 *
 * <h3>발송 채널</h3>
 * {@link NotificationDispatchEvent}(A-60)를 발행해 오케스트레이터 경로로 <b>이메일 + 인앱</b>을 함께
 * 보낸다. 인앱 딥링크는 {@code NotificationLinkResolver} 가 {@code /lew/applications/{id}#sld} 로 만든다.
 *
 * <h3>멱등 (주 1회)</h3>
 * 후보 쿼리가 {@code sld_reminder_notified_at IS NULL OR < now-6일} 을 포함하고, 발송 후
 * {@link Application#markSldReminderNotified()} 로 NOW() 를 기록한다. 스케줄 자체도 주 1회라 이중 가드.
 *
 * <h3>스케줄</h3>
 * 기본 매주 월요일 새벽 4시 (만료 2시·EMA 2:30 와 시간대 분리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SldReminderScheduler {

    /** 인앱 알림 referenceType — 프론트가 신청 상세로 라우팅하는 키. */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";
    /** A-60 템플릿 코드. */
    static final String TEMPLATE_CODE = "A-60";

    private final ApplicationRepository applicationRepository;
    private final FileRepository fileRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 매주 월요일 새벽 4시 실행: SLD 미제출 리마인더 발송 (담당 LEW, 이메일+인앱).
     */
    @Scheduled(cron = "${sld-reminder.schedule-cron:0 0 4 ? * MON}")
    @SchedulerLock(name = "processSldReminders", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    public void processSldReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMonths(2);  // 발급 후 최소 2개월 경과
        LocalDateTime windowEnd = now.minusMonths(3);     // 발급 후 최대 3개월 (이후는 종료)
        LocalDateTime dedupeBefore = now.minusDays(6);    // 최근 6일 내 발송분 제외(주1회)

        List<Application> candidates = applicationRepository.findSldReminderCandidates(
                ApplicationStatus.COMPLETED, LicenseStatus.ACTIVE, windowStart, windowEnd, dedupeBefore);

        if (candidates.isEmpty()) {
            log.debug("No SLD reminder candidates");
            return;
        }

        int sent = 0;
        for (Application app : candidates) {
            try {
                // SLD(DRAWING_SLD)가 이미 업로드된 건은 제외 — 플래그도 찍지 않는다.
                boolean hasSld = !fileRepository
                        .findByApplicationApplicationSeqAndFileType(app.getApplicationSeq(), FileType.DRAWING_SLD)
                        .isEmpty();
                if (hasSld) {
                    continue;
                }
                User lew = app.getAssignedLew();
                if (lew == null) {
                    continue; // 쿼리에서 걸러지나 방어적
                }

                Long seq = app.getApplicationSeq();
                String deadline = app.getLicenseIssuedAt() != null
                        ? app.getLicenseIssuedAt().plusMonths(3).toLocalDate().toString()
                        : "";
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("applicantName", app.getUser() != null ? app.getUser().getFullName() : "");
                payload.put("publicCode", String.valueOf(seq));
                payload.put("deadline", deadline);
                payload.put("ctaUrl", "/lew/applications/" + seq + "/review#sld"); // 이메일 링크(상대→절대화), SLD 탭

                eventPublisher.publishEvent(new NotificationDispatchEvent(
                        NotificationType.SLD_SUBMISSION_REMINDER_LEW.name(),
                        lew.getUserSeq(),
                        REFERENCE_TYPE_APPLICATION, seq,
                        TEMPLATE_CODE,
                        payload));

                app.markSldReminderNotified(); // 멱등 — 다음 주까지 재발송 차단
                sent++;
                log.info("SLD reminder dispatched: applicationSeq={}, lewSeq={}, deadline={}",
                        seq, lew.getUserSeq(), deadline);
            } catch (RuntimeException ex) {
                // 한 건 실패가 나머지를 막지 않게 격리. 플래그 미기록 → 다음 실행 재시도.
                log.warn("Failed to dispatch SLD reminder: applicationSeq={}, err={}",
                        app.getApplicationSeq(), ex.getMessage());
            }
        }

        if (sent > 0) {
            log.info("SLD reminders dispatched for {} application(s)", sent);
        }
    }
}
