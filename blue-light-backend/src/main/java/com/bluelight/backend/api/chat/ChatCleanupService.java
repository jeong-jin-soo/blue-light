package com.bluelight.backend.api.chat;

import com.bluelight.backend.domain.chat.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 채팅 메시지 자동 정리 서비스 (PDPA 보유 제한 의무)
 * - 보존 기간 초과 메시지를 batch 삭제
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCleanupService {

    private final ChatMessageRepository chatMessageRepository;

    @Value("${chat.retention-days:90}")
    private int retentionDays;

    /**
     * 보존 기간 초과 채팅 메시지 자동 정리 (매일 새벽 4시)
     * - 감사 로그 정리(3시)와 시간대 분리
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupOldMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        int batchSize = 1000;

        int deleted;
        do {
            deleted = chatMessageRepository.deleteOlderThan(cutoff, batchSize);
            totalDeleted += deleted;
        } while (deleted == batchSize);

        if (totalDeleted > 0) {
            log.info("채팅 메시지 정리 완료: {}건 삭제 (보존 기간: {}일)", totalDeleted, retentionDays);
        }
    }
}
