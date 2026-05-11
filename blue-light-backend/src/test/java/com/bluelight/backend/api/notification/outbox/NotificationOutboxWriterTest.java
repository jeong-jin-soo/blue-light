package com.bluelight.backend.api.notification.outbox;

import com.bluelight.backend.api.notification.outbox.NotificationOutboxWriter.EnqueueRequest;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import com.bluelight.backend.domain.notification.OutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationOutboxWriter 단위 테스트 (PR-0B).
 * idempotency 가드, 동시성 race 복구, payload 직렬화 실패 처리 검증.
 */
@DisplayName("NotificationOutboxWriter - PR-0B")
class NotificationOutboxWriterTest {

    private NotificationOutboxRepository repository;
    private ObjectMapper objectMapper;
    private NotificationOutboxWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationOutboxRepository.class);
        objectMapper = new ObjectMapper();
        writer = new NotificationOutboxWriter(repository, objectMapper);
    }

    @Test
    @DisplayName("idempotencyKey - 결정적 포맷 {event}:{refType}:{refId}:{userSeq}:{channel}")
    void idempotencyKey_format() {
        String key = NotificationOutboxWriter.idempotencyKey(
                "PAYMENT_REQUEST", "APPLICATION", 42L, 1001L, NotificationChannel.EMAIL);
        assertThat(key).isEqualTo("PAYMENT_REQUEST:APPLICATION:42:1001:EMAIL");
    }

    @Test
    @DisplayName("idempotencyKey - null 필드는 '-' 로 정규화")
    void idempotencyKey_nullsBecomeDash() {
        String key = NotificationOutboxWriter.idempotencyKey(
                "TEST", null, null, 7L, NotificationChannel.IN_APP);
        assertThat(key).isEqualTo("TEST:-:-:7:IN_APP");
    }

    @Test
    @DisplayName("enqueue - 신규 row 정상 적재")
    void enqueue_insertsNewRow() {
        when(repository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(repository.save(any(NotificationOutbox.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<NotificationOutbox> result = writer.enqueue(sampleRequest("KEY-1", 1L));

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(result.get().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(result.get().getPayloadJson()).contains("\"amount\":\"185.00\"");
        verify(repository, times(1)).save(any(NotificationOutbox.class));
    }

    @Test
    @DisplayName("enqueue - 동일 idempotencyKey row 존재 시 기존 row 반환 (멱등)")
    void enqueue_returnsExistingOnDuplicateKey() {
        NotificationOutbox existing = NotificationOutbox.builder()
                .idempotencyKey("KEY-1")
                .userSeq(1L)
                .channel(NotificationChannel.EMAIL)
                .eventType("TEST")
                .templateCode("T")
                .locale("en")
                .payloadJson("{}")
                .build();
        when(repository.findByIdempotencyKey("KEY-1")).thenReturn(Optional.of(existing));

        Optional<NotificationOutbox> result = writer.enqueue(sampleRequest("KEY-1", 1L));

        assertThat(result).hasValue(existing);
        verify(repository, never()).save(any(NotificationOutbox.class));
    }

    @Test
    @DisplayName("enqueue - 동시성 UNIQUE 위반 시 기존 row 재조회로 복구")
    void enqueue_recoversFromConcurrentInsertRace() {
        NotificationOutbox raced = NotificationOutbox.builder()
                .idempotencyKey("KEY-RACE")
                .userSeq(1L)
                .channel(NotificationChannel.EMAIL)
                .eventType("TEST")
                .templateCode("T")
                .locale("en")
                .payloadJson("{}")
                .build();

        when(repository.findByIdempotencyKey("KEY-RACE"))
                .thenReturn(Optional.empty())     // 첫 가드 통과
                .thenReturn(Optional.of(raced));  // race 복구 시 재조회
        when(repository.save(any(NotificationOutbox.class)))
                .thenThrow(new DataIntegrityViolationException("uk_outbox_idem"));

        Optional<NotificationOutbox> result = writer.enqueue(sampleRequest("KEY-RACE", 1L));

        assertThat(result).hasValue(raced);
        verify(repository, times(2)).findByIdempotencyKey("KEY-RACE");
    }

    private static EnqueueRequest sampleRequest(String key, Long userSeq) {
        return new EnqueueRequest(
                key,
                userSeq,
                NotificationChannel.EMAIL,
                "PAYMENT_REQUEST",
                "PAYMENT_REQUEST_APPLICANT",
                "en",
                Map.of("amount", "185.00"),
                "APPLICATION",
                42L);
    }
}
