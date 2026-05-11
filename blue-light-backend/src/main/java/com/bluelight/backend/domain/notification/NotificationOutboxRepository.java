package com.bluelight.backend.domain.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 알림 Outbox Repository.
 *
 * <p>PR-0A 는 스키마/엔티티만 추가하며, 실제 적재/조회는 PR-0B 의 {@code OutboxWriter}/
 * {@code RetryScheduler} 에서 사용한다.</p>
 */
@Repository
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    /** 멱등성 가드 — 동일 키로 이미 적재되었는지 확인 (UNIQUE 제약 위반 사전 차단). */
    Optional<NotificationOutbox> findByIdempotencyKey(String idempotencyKey);

    /** 스케줄러 폴링용 — 재시도 시각이 도래한 PENDING/FAILED row. */
    @Query("SELECT o FROM NotificationOutbox o " +
            "WHERE o.status IN (com.bluelight.backend.domain.notification.OutboxStatus.PENDING, " +
            "                   com.bluelight.backend.domain.notification.OutboxStatus.FAILED) " +
            "  AND (o.nextAttemptAt IS NULL OR o.nextAttemptAt <= :now) " +
            "ORDER BY o.createdAt ASC")
    List<NotificationOutbox> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    /** 참조 엔티티(예: application_seq=123) 기준 발송 이력 조회. */
    Page<NotificationOutbox> findByReferenceTypeAndReferenceIdOrderByCreatedAtDesc(
            String referenceType, Long referenceId, Pageable pageable);

    /** 사용자 기준 최근 발송 이력. */
    Page<NotificationOutbox> findByUserSeqOrderByCreatedAtDesc(Long userSeq, Pageable pageable);
}
