package com.bluelight.backend.api.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 챗봇 요청 Rate Limiter (in-memory)
 * - 익명: IP당 15분 내 최대 20회
 * - 인증: IP당 15분 내 최대 40회
 */
@Slf4j
@Component
public class ChatRateLimiter {

    private static final int MAX_ATTEMPTS_ANONYMOUS = 20;
    private static final int MAX_ATTEMPTS_AUTHENTICATED = 40;
    private static final long WINDOW_MS = 15 * 60 * 1000L; // 15분

    private final ConcurrentHashMap<String, List<Long>> attempts = new ConcurrentHashMap<>();

    /**
     * 해당 IP가 차단 상태인지 확인
     */
    public boolean isBlocked(String ipAddress, boolean authenticated) {
        List<Long> timestamps = attempts.get(ipAddress);
        if (timestamps == null) return false;

        int limit = authenticated ? MAX_ATTEMPTS_AUTHENTICATED : MAX_ATTEMPTS_ANONYMOUS;
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        long recentCount = timestamps.stream()
                .filter(t -> t > cutoff)
                .count();

        return recentCount >= limit;
    }

    /**
     * 요청 시도 기록
     */
    public void recordAttempt(String ipAddress) {
        attempts.computeIfAbsent(ipAddress, k -> new ArrayList<>())
                .add(System.currentTimeMillis());
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
        log.debug("Chat rate limiter cleanup: {} IPs tracked", attempts.size());
    }
}
