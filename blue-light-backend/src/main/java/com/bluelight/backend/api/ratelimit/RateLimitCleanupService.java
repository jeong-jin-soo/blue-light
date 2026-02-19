package com.bluelight.backend.api.ratelimit;

import com.bluelight.backend.domain.ratelimit.RateLimitAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Rate Limit 시도 기록 정리 서비스
 * - 1시간마다 만료된 기록을 DB에서 삭제
 * - ShedLock으로 다중 서버에서 1대만 실행
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitCleanupService {

    private final RateLimitAttemptRepository rateLimitAttemptRepository;

    /**
     * 1시간마다 만료된 Rate Limit 기록 정리
     * - 윈도우(15분)의 4배(1시간)보다 오래된 기록 삭제
     */
    @Scheduled(fixedRate = 3600000)
    @SchedulerLock(name = "rateLimitCleanup", lockAtMostFor = "10m", lockAtLeastFor = "5m")
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        int batchSize = 1000;
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = rateLimitAttemptRepository.deleteOlderThan(cutoff, batchSize);
            totalDeleted += deleted;
        } while (deleted == batchSize);

        if (totalDeleted > 0) {
            log.info("Rate limit 기록 정리: {}건 삭제", totalDeleted);
        }
    }
}
