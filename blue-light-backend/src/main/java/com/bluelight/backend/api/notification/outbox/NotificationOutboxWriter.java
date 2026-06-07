package com.bluelight.backend.api.notification.outbox;

import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 알림 Outbox 적재 — idempotencyKey UNIQUE 기반 중복 방지.
 *
 * <p>{@link NotificationOutbox} 에 PENDING row 를 INSERT 한다. orchestrator 가 AFTER_COMMIT
 * 단계에서 호출하므로 본 메서드는 항상 새 트랜잭션(REQUIRES_NEW)에서 실행된다.</p>
 *
 * <p>ObjectMapper 빈은 {@link com.bluelight.backend.config.NotificationJacksonConfig} 가 등록한다
 * (Spring Boot 4 의 분리된 starter 구조에서 자동 등록되지 않을 수 있어 명시 fallback).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxWriter {

    private final NotificationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * outbox row 적재. 동일 idempotencyKey 의 row 가 이미 있으면 신규 적재 없이 기존 row 를
     * 반환한다 (중복 호출 가드).
     *
     * @return 새로 적재됐거나 기존에 있던 outbox row. 직렬화 실패 시 {@link Optional#empty()}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<NotificationOutbox> enqueue(EnqueueRequest req) {
        // 1) 중복 가드 — 이미 적재된 동일 idempotencyKey 가 있으면 skip
        Optional<NotificationOutbox> existing = outboxRepository.findByIdempotencyKey(req.idempotencyKey());
        if (existing.isPresent()) {
            log.debug("Outbox row already exists, skipping enqueue: idempotencyKey={}", req.idempotencyKey());
            return existing;
        }

        // 2) payload JSON 직렬화
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(req.payload());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for outbox: idempotencyKey={}, error={}",
                    req.idempotencyKey(), e.getMessage());
            return Optional.empty();
        }

        NotificationOutbox row = NotificationOutbox.builder()
                .idempotencyKey(req.idempotencyKey())
                .userSeq(req.userSeq())
                .channel(req.channel())
                .eventType(req.eventType())
                .templateCode(req.templateCode())
                .locale(req.locale())
                .payloadJson(payloadJson)
                .referenceType(req.referenceType())
                .referenceId(req.referenceId())
                .build();
        try {
            return Optional.of(outboxRepository.save(row));
        } catch (DataIntegrityViolationException e) {
            // 동시성 — 다른 트랜잭션이 동일 키로 INSERT 했음. 기존 row 재조회.
            log.info("Concurrent enqueue race resolved by idempotency_key UNIQUE: key={}", req.idempotencyKey());
            return outboxRepository.findByIdempotencyKey(req.idempotencyKey());
        }
    }

    /**
     * Idempotency key 산정 규칙. 동일 (eventType, refType, refId, userSeq, channel) 조합에 대해
     * 한 번만 발송되도록 보장.
     */
    public static String idempotencyKey(String eventType, String refType, Long refId, Long userSeq, NotificationChannel channel) {
        return String.join(":",
                nullSafe(eventType),
                nullSafe(refType),
                refId == null ? "-" : String.valueOf(refId),
                userSeq == null ? "-" : String.valueOf(userSeq),
                channel == null ? "-" : channel.name());
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** outbox 적재 요청 DTO. */
    public record EnqueueRequest(
            String idempotencyKey,
            Long userSeq,
            NotificationChannel channel,
            String eventType,
            String templateCode,
            String locale,
            Map<String, String> payload,
            String referenceType,
            Long referenceId
    ) {
    }
}
