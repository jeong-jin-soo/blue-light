package com.bluelight.backend.api.notification.channel;

import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;

/**
 * 알림 채널 어댑터 — 각 채널(IN_APP/EMAIL/WHATSAPP) 의 외부 호출을 추상화한다.
 *
 * <p>{@code NotificationOutboxDispatcher} 가 outbox row 와 매칭되는 어댑터를 찾아 {@link #send}
 * 를 호출한다. 어댑터는 외부 호출 결과를 {@link SendResult} 로 반환만 하며, outbox row 의 상태
 * 갱신은 호출 측이 책임진다 (트랜잭션 경계 분리).</p>
 *
 * <h2>구현 가이드</h2>
 * <ul>
 *   <li>외부 호출(SMTP, HTTP, DB INSERT 등) 만 수행 — outbox row 수정 금지.</li>
 *   <li>예외를 던지지 말고 {@link SendResult#failure} 로 감싸 반환 — 디스패처가 재시도 판정.</li>
 *   <li>스레드 안전해야 한다 — 스케줄러가 동시 실행 가능.</li>
 * </ul>
 */
public interface NotificationChannelAdapter {

    /** 본 어댑터가 담당하는 채널. */
    NotificationChannel channel();

    /**
     * outbox row 의 메시지를 외부 채널로 발송.
     *
     * @param row     outbox row (payload_json, template_code, locale, user_seq 등을 포함)
     * @return 외부 호출 결과. 성공 시 {@link SendResult#success}, 실패 시 {@link SendResult#failure}.
     */
    SendResult send(NotificationOutbox row);

    /** 발송 결과 — 디스패처가 outbox row 의 상태 전이에 사용. */
    record SendResult(boolean success, String providerMessageId, String errorCode, String errorMessage, boolean retryable) {
        public static SendResult success(String providerMessageId) {
            return new SendResult(true, providerMessageId, null, null, false);
        }

        /** 일시적 실패 — 재시도 대상. */
        public static SendResult retryableFailure(String errorCode, String errorMessage) {
            return new SendResult(false, null, errorCode, errorMessage, true);
        }

        /** 영구 실패 — DEAD 처리 (재시도 무의미). */
        public static SendResult permanentFailure(String errorCode, String errorMessage) {
            return new SendResult(false, null, errorCode, errorMessage, false);
        }
    }
}
