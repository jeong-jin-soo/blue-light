package com.bluelight.backend.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationOutbox 상태 전이 단위 테스트 (PR-0A).
 *
 * <p>markSending/markSent/markFailed/markDead/markSkipped 도메인 메서드 검증.
 * attemptCount, sentAt, lastError, nextAttemptAt 상태 갱신을 확인한다.</p>
 */
@DisplayName("NotificationOutbox 상태 전이 - PR-0A")
class NotificationOutboxTest {

    private NotificationOutbox buildOutbox() {
        return NotificationOutbox.builder()
                .idempotencyKey("PAYMENT_REQUEST:APPLICATION:42:1001:EMAIL")
                .userSeq(1001L)
                .channel(NotificationChannel.EMAIL)
                .eventType("PAYMENT_REQUEST")
                .templateCode("PAYMENT_REQUEST_APPLICANT")
                .locale("en")
                .payloadJson("{\"amount\":\"185.00\"}")
                .referenceType("APPLICATION")
                .referenceId(42L)
                .build();
    }

    @Test
    @DisplayName("빌더 - 기본 상태 PENDING, attemptCount=0, sentAt/lastError null")
    void builder_defaultsToPending() {
        NotificationOutbox row = buildOutbox();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getSentAt()).isNull();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getLocale()).isEqualTo("en");
    }

    @Test
    @DisplayName("빌더 - locale 미지정 시 'en' 으로 fallback")
    void builder_localeFallbackToEnglish() {
        NotificationOutbox row = NotificationOutbox.builder()
                .idempotencyKey("TEST:REF:1:1:IN_APP")
                .userSeq(1L)
                .channel(NotificationChannel.IN_APP)
                .eventType("TEST")
                .templateCode("TEST")
                .locale(null)
                .payloadJson("{}")
                .build();

        assertThat(row.getLocale()).isEqualTo("en");
    }

    // ============================================================
    // markSending → markSent
    // ============================================================

    @Test
    @DisplayName("markSending() - SENDING 상태, attemptCount 증가")
    void markSending_incrementsAttemptCount() {
        NotificationOutbox row = buildOutbox();

        row.markSending();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENDING);
        assertThat(row.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("markSent() - SENT 상태, sentAt 기록, lastError 초기화")
    void markSent_setsTimestampAndClearsError() {
        NotificationOutbox row = buildOutbox();
        row.markSending();
        row.markFailed("transient error", LocalDateTime.now().plusMinutes(1));

        row.markSent();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getLastError()).isNull();
    }

    // ============================================================
    // markFailed - 재시도 가능
    // ============================================================

    @Test
    @DisplayName("markFailed() - FAILED 상태, lastError + nextAttemptAt 기록")
    void markFailed_recordsErrorAndRetrySchedule() {
        NotificationOutbox row = buildOutbox();
        row.markSending();
        LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(30);

        row.markFailed("provider timeout", nextAttempt);

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getLastError()).isEqualTo("provider timeout");
        assertThat(row.getNextAttemptAt()).isEqualTo(nextAttempt);
    }

    @Test
    @DisplayName("FAILED → SENDING → SENT 복구 흐름 - attemptCount 누적, 마지막 에러 클리어")
    void retryFlow_accumulatesAttemptsAndClearsErrorOnSuccess() {
        NotificationOutbox row = buildOutbox();

        row.markSending();                                                    // attempt 1
        row.markFailed("err1", LocalDateTime.now().plusSeconds(30));
        row.markSending();                                                    // attempt 2
        row.markFailed("err2", LocalDateTime.now().plusSeconds(60));
        row.markSending();                                                    // attempt 3
        row.markSent();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(row.getAttemptCount()).isEqualTo(3);
        assertThat(row.getLastError()).isNull();
        assertThat(row.getSentAt()).isNotNull();
    }

    // ============================================================
    // markDead - 영구 실패
    // ============================================================

    @Test
    @DisplayName("markDead() - DEAD 상태, nextAttemptAt 클리어 (자동 재시도 중단)")
    void markDead_clearsRetrySchedule() {
        NotificationOutbox row = buildOutbox();
        row.markSending();
        row.markFailed("err", LocalDateTime.now().plusSeconds(30));

        row.markDead("max retries exceeded");

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(row.getLastError()).isEqualTo("max retries exceeded");
        assertThat(row.getNextAttemptAt()).isNull();
    }

    // ============================================================
    // markSkipped - 사전 가드 컷
    // ============================================================

    @Test
    @DisplayName("markSkipped() - SKIPPED 상태, 사유 기록, nextAttemptAt 클리어")
    void markSkipped_recordsReason() {
        NotificationOutbox row = buildOutbox();

        row.markSkipped("user opted out");

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(row.getLastError()).isEqualTo("user opted out");
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getAttemptCount()).isZero(); // 외부 호출 없었으므로 0 유지
    }
}
