package com.bluelight.backend.api.application;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 면허 만료 자동 처리 스케줄러
 * - 만료 임박 알림 이메일 발송 (기본 30일 전)
 * - 만료일 경과 시 자동 EXPIRED 상태 전환
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseExpiryScheduler {

    private final ApplicationRepository applicationRepository;
    private final EmailService emailService;

    @Value("${license-expiry.warning-days-before:30}")
    private int warningDaysBefore;

    /**
     * 매일 새벽 2시 실행: 만료 알림 + 자동 만료 처리
     */
    @Scheduled(cron = "${license-expiry.schedule-cron:0 0 2 * * ?}")
    @SchedulerLock(name = "processLicenseExpiry", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    public void processLicenseExpiry() {
        log.info("License expiry scheduler started");

        LocalDate today = LocalDate.now();

        // Step 1: 만료 임박 알림 발송
        sendExpiryWarnings(today);

        // Step 2: 만료일 경과 → EXPIRED 전환
        expireOverdueLicenses(today);

        log.info("License expiry scheduler completed");
    }

    /**
     * Step 1: 만료 임박 알림 이메일 발송
     * - 조건: COMPLETED + 만료일 <= today + warningDays + 아직 미알림
     */
    private void sendExpiryWarnings(LocalDate today) {
        LocalDate warningDate = today.plusDays(warningDaysBefore);

        List<Application> targets = applicationRepository
                .findByStatusAndLicenseExpiryDateLessThanEqualAndExpiryNotifiedAtIsNull(
                        ApplicationStatus.COMPLETED, warningDate);

        if (targets.isEmpty()) {
            log.debug("No expiry warning targets found");
            return;
        }

        log.info("Sending expiry warnings for {} application(s)", targets.size());

        for (Application app : targets) {
            try {
                int daysRemaining = (int) ChronoUnit.DAYS.between(today, app.getLicenseExpiryDate());

                emailService.sendLicenseExpiryWarningEmail(
                        app.getUser().getEmail(),
                        app.getUser().getFullName(),
                        app.getLicenseNumber(),
                        app.getAddress(),
                        app.getLicenseExpiryDate(),
                        daysRemaining
                );

                app.markExpiryNotified();
                log.info("Expiry warning sent: applicationSeq={}, expiryDate={}, daysRemaining={}",
                        app.getApplicationSeq(), app.getLicenseExpiryDate(), daysRemaining);
            } catch (Exception e) {
                log.error("Failed to send expiry warning: applicationSeq={}",
                        app.getApplicationSeq(), e);
            }
        }
    }

    /**
     * Step 2: 만료일 경과 → EXPIRED 자동 전환
     * - 조건: COMPLETED + 만료일 < today
     */
    private void expireOverdueLicenses(LocalDate today) {
        List<Application> expired = applicationRepository
                .findByStatusAndLicenseExpiryDateBefore(ApplicationStatus.COMPLETED, today);

        if (expired.isEmpty()) {
            log.debug("No expired applications found");
            return;
        }

        log.info("Expiring {} application(s)", expired.size());

        for (Application app : expired) {
            app.markAsExpired();
            log.info("Application expired: applicationSeq={}, expiryDate={}",
                    app.getApplicationSeq(), app.getLicenseExpiryDate());
        }
    }
}
