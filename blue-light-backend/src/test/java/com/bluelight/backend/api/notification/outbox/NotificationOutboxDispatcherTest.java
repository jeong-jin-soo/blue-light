package com.bluelight.backend.api.notification.outbox;

import com.bluelight.backend.api.notification.channel.NotificationChannelAdapter;
import com.bluelight.backend.api.notification.channel.NotificationChannelAdapter.SendResult;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import com.bluelight.backend.domain.notification.OutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NotificationOutboxDispatcher 단위 테스트 (PR-0B).
 *
 * <p>어댑터 결과(성공/일시실패/영구실패/예외)에 따른 outbox 상태 전이 + 어댑터 부재/이미 처리된
 * row 스킵 검증.</p>
 */
@DisplayName("NotificationOutboxDispatcher - PR-0B")
class NotificationOutboxDispatcherTest {

    private final ExponentialBackoffPolicy backoffPolicy = new ExponentialBackoffPolicy();

    @Test
    @DisplayName("중복 어댑터 등록 - 생성 시 IllegalStateException")
    void rejects_duplicateAdaptersForSameChannel() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationChannelAdapter a = stubAdapter(NotificationChannel.EMAIL, SendResult.success("id"));
        NotificationChannelAdapter b = stubAdapter(NotificationChannel.EMAIL, SendResult.success("id"));

        assertThatThrownBy(() -> new NotificationOutboxDispatcher(repo, backoffPolicy, List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate NotificationChannelAdapter");
    }

    @Test
    @DisplayName("dispatch - 성공 시 SENT 로 전이")
    void dispatch_successMarksSent() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationOutbox row = pendingRow();
        when(repo.findById(row.getOutboxSeq())).thenReturn(Optional.of(row));

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(stubAdapter(NotificationChannel.EMAIL, SendResult.success("mid-1"))));

        dispatcher.dispatch(row.getOutboxSeq());

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("dispatch - 일시 실패 시 FAILED + nextAttemptAt 산정")
    void dispatch_retryableFailureSchedulesRetry() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationOutbox row = pendingRow();
        when(repo.findById(row.getOutboxSeq())).thenReturn(Optional.of(row));

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(stubAdapter(NotificationChannel.EMAIL,
                        SendResult.retryableFailure("TIMEOUT", "smtp timeout"))));

        dispatcher.dispatch(row.getOutboxSeq());

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getLastError()).contains("TIMEOUT").contains("smtp timeout");
        assertThat(row.getNextAttemptAt()).isNotNull();
        assertThat(row.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dispatch - 영구 실패 시 DEAD")
    void dispatch_permanentFailureMarksDead() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationOutbox row = pendingRow();
        when(repo.findById(row.getOutboxSeq())).thenReturn(Optional.of(row));

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(stubAdapter(NotificationChannel.EMAIL,
                        SendResult.permanentFailure("TEMPLATE_REJECTED", "Meta rejected"))));

        dispatcher.dispatch(row.getOutboxSeq());

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(row.getLastError()).contains("TEMPLATE_REJECTED");
    }

    @Test
    @DisplayName("dispatch - attemptCount 가 maxAttempts 도달하면 일시실패도 DEAD 로 전이")
    void dispatch_retryExhaustedBecomesDead() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationOutbox row = pendingRow();
        // attemptCount 를 maxAttempts - 1 로 만들어 다음 markSending 시 attemptCount=6 도달.
        ReflectionTestUtils.setField(row, "attemptCount", ExponentialBackoffPolicy.MAX_ATTEMPTS - 1);
        when(repo.findById(row.getOutboxSeq())).thenReturn(Optional.of(row));

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(stubAdapter(NotificationChannel.EMAIL,
                        SendResult.retryableFailure("TIMEOUT", "x"))));

        dispatcher.dispatch(row.getOutboxSeq());

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(row.getAttemptCount()).isEqualTo(ExponentialBackoffPolicy.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("dispatch - 어댑터가 예외 던지면 일시실패로 처리 (재시도 가능)")
    void dispatch_adapterExceptionTreatedAsRetryable() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationOutbox row = pendingRow();
        when(repo.findById(row.getOutboxSeq())).thenReturn(Optional.of(row));

        NotificationChannelAdapter throwing = mock(NotificationChannelAdapter.class);
        when(throwing.channel()).thenReturn(NotificationChannel.EMAIL);
        when(throwing.send(any())).thenThrow(new RuntimeException("boom"));

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(throwing));

        dispatcher.dispatch(row.getOutboxSeq());

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getLastError()).contains("UNEXPECTED_EXCEPTION").contains("boom");
    }

    @Test
    @DisplayName("dispatch - 채널 어댑터 미등록 시 SKIPPED")
    void dispatch_noAdapterMarksSkipped() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationOutbox row = pendingRow();
        ReflectionTestUtils.setField(row, "channel", NotificationChannel.WHATSAPP); // 등록 어댑터 없음
        when(repo.findById(row.getOutboxSeq())).thenReturn(Optional.of(row));

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(stubAdapter(NotificationChannel.EMAIL, SendResult.success("x"))));

        dispatcher.dispatch(row.getOutboxSeq());

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(row.getLastError()).contains("No adapter for channel WHATSAPP");
    }

    @Test
    @DisplayName("dispatch - 이미 SENT/DEAD/SKIPPED row 는 재처리하지 않음")
    void dispatch_alreadySettledRowIsNoop() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationOutbox row = pendingRow();
        row.markSending();
        row.markSent(); // 이미 SENT
        when(repo.findById(row.getOutboxSeq())).thenReturn(Optional.of(row));

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(stubAdapter(NotificationChannel.EMAIL, SendResult.success("x"))));

        // attemptCount/상태 그대로
        int attemptsBefore = row.getAttemptCount();
        dispatcher.dispatch(row.getOutboxSeq());

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getAttemptCount()).isEqualTo(attemptsBefore);
    }

    @Test
    @DisplayName("dispatch - row 미존재 시 무시 (warn 로그)")
    void dispatch_missingRowIsNoop() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        when(repo.findById(999L)).thenReturn(Optional.empty());

        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(stubAdapter(NotificationChannel.EMAIL, SendResult.success("x"))));

        dispatcher.dispatch(999L); // 예외 던지지 않음
    }

    @Test
    @DisplayName("registeredChannels - 모든 어댑터의 채널 노출")
    void registeredChannels_listsAllAdapters() {
        NotificationOutboxRepository repo = mock(NotificationOutboxRepository.class);
        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(repo, backoffPolicy,
                List.of(
                        stubAdapter(NotificationChannel.EMAIL, SendResult.success("x")),
                        stubAdapter(NotificationChannel.IN_APP, SendResult.success("x"))));

        assertThat(dispatcher.registeredChannels())
                .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
    }

    // ===== helpers =====

    private static NotificationOutbox pendingRow() {
        NotificationOutbox row = NotificationOutbox.builder()
                .idempotencyKey("TEST:APPLICATION:42:1001:EMAIL")
                .userSeq(1001L)
                .channel(NotificationChannel.EMAIL)
                .eventType("TEST")
                .templateCode("T")
                .locale("en")
                .payloadJson("{}")
                .build();
        // 가짜 PK 부여 — findById mock 매칭용
        ReflectionTestUtils.setField(row, "outboxSeq", 555L);
        return row;
    }

    private static NotificationChannelAdapter stubAdapter(NotificationChannel channel, SendResult result) {
        NotificationChannelAdapter adapter = mock(NotificationChannelAdapter.class);
        when(adapter.channel()).thenReturn(channel);
        when(adapter.send(any())).thenReturn(result);
        return adapter;
    }
}
