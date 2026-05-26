package com.bluelight.backend.api.admin.notification.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admin 테스트 발송 일일 쿼터 추적 — 메모리 내 카운터.
 *
 * <p>키: (adminUserSeq, SGT date). 자정에 자연 만료 (다음날 lookup 시 새 키 사용).
 * 별도 cleanup 없이도 메모리 사용은 active admin 수 × 약 30 bytes 수준.</p>
 *
 * <p>운영 다중 인스턴스에서는 인스턴스별 카운터 — 정확한 50/일 limit 가 아니라 인스턴스 수 × 50.
 * MVP 허용 범위. 정확한 글로벌 카운터는 Redis 도입 시 (P1).</p>
 */
@Component
@Slf4j
public class TestSendQuotaTracker {

    public static final int DEFAULT_DAILY_MAX = 50;

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final int dailyMax;

    public TestSendQuotaTracker() {
        this(DEFAULT_DAILY_MAX);
    }

    /** 테스트용 생성자. */
    public TestSendQuotaTracker(int dailyMax) {
        this.dailyMax = dailyMax;
    }

    /** quota 차감 시도 — 성공 시 새 사용량, 초과 시 {@link QuotaExceededException}. */
    public int tryConsume(Long adminUserSeq) {
        String key = key(adminUserSeq, LocalDate.now(ZoneId.of("Asia/Singapore")));
        AtomicInteger counter = counters.computeIfAbsent(key, k -> new AtomicInteger(0));
        int newValue = counter.incrementAndGet();
        if (newValue > dailyMax) {
            // 초과한 increment 를 되돌림 (다음 호출자 카운트 정확성)
            counter.decrementAndGet();
            throw new QuotaExceededException(adminUserSeq, dailyMax);
        }
        return newValue;
    }

    public int currentUsage(Long adminUserSeq) {
        String key = key(adminUserSeq, LocalDate.now(ZoneId.of("Asia/Singapore")));
        AtomicInteger counter = counters.get(key);
        return counter == null ? 0 : counter.get();
    }

    public int dailyMax() {
        return dailyMax;
    }

    private static String key(Long adminUserSeq, LocalDate date) {
        return adminUserSeq + ":" + date;
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(Long userSeq, int dailyMax) {
            super("Admin " + userSeq + " 의 일일 테스트 발송 한도(" + dailyMax + "통) 초과.");
        }
    }
}
