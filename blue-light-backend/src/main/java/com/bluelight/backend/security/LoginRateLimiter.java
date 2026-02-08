package com.bluelight.backend.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 시도 Rate Limiter (in-memory)
 * - IP당 15분 내 최대 5회 로그인 시도 허용
 * - 서버 재시작 시 초기화
 */
@Slf4j
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 15 * 60 * 1000L; // 15분

    // IP → 시도 시각 목록
    private final ConcurrentHashMap<String, List<Long>> attempts = new ConcurrentHashMap<>();

    /**
     * 해당 IP가 차단 상태인지 확인
     */
    public boolean isBlocked(String ipAddress) {
        List<Long> timestamps = attempts.get(ipAddress);
        if (timestamps == null) return false;

        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        long recentCount = timestamps.stream()
                .filter(t -> t > cutoff)
                .count();

        return recentCount >= MAX_ATTEMPTS;
    }

    /**
     * 실패한 로그인 시도 기록
     */
    public void recordFailedAttempt(String ipAddress) {
        attempts.computeIfAbsent(ipAddress, k -> new ArrayList<>())
                .add(System.currentTimeMillis());
    }

    /**
     * 로그인 성공 시 해당 IP의 시도 기록 초기화
     */
    public void clearAttempts(String ipAddress) {
        attempts.remove(ipAddress);
    }

    /**
     * 1시간마다 만료된 항목 정리
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        Iterator<Map.Entry<String, List<Long>>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<Long>> entry = it.next();
            entry.getValue().removeIf(t -> t <= cutoff);
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
        log.debug("Rate limiter cleanup: {} IPs tracked", attempts.size());
    }
}
