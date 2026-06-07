package com.bluelight.backend.domain.notification;

/**
 * Outbox row 의 처리 상태.
 *
 * <pre>
 *   PENDING  → SENDING → SENT
 *                  ↓
 *               FAILED → (재시도) → SENDING → SENT
 *                  ↓
 *                 DEAD  (최대 재시도 초과)
 *
 *   PENDING → SKIPPED  (사용자 옵트아웃·번호 미검증·feature flag OFF 등 발송 전 가드 컷)
 * </pre>
 */
public enum OutboxStatus {
    /** 트랜잭션 안에서 적재된 직후 상태. 스케줄러 또는 즉시 디스패처가 SENDING 으로 전환. */
    PENDING,
    /** 외부 호출 진행 중 (다른 워커의 중복 처리 방지용 락 상태). */
    SENDING,
    /** 외부 호출 성공 — 채널 측 ack 수신. */
    SENT,
    /** 외부 호출 실패 — 재시도 가능 (next_attempt_at 설정 후 스케줄러가 재처리). */
    FAILED,
    /** 최대 재시도 초과 또는 영구 실패 — 더 이상 자동 재시도하지 않는다 (ADMIN 수동 처리). */
    DEAD,
    /** 발송 전 가드(옵트아웃·번호 미검증·feature flag·rate limit 등)에 의해 생략됨. */
    SKIPPED
}
