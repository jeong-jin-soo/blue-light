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

    /**
     * PR-T7 P1 — 템플릿 코드 + 채널별 상태 집계 (지난 N일).
     *
     * <p>is_test=true (admin test-send) 는 운영 수치에서 제외한다 — 인박스/메트릭스가
     * 어드민 자신의 테스트로 부풀려지는 것을 차단 (스펙 §17, §5.5).
     * 반환: {@code [channel, status, count]} 행 묶음. 서비스에서 enum 매핑.</p>
     */
    @Query("SELECT o.channel, o.status, COUNT(o) " +
            "FROM NotificationOutbox o " +
            "WHERE o.templateCode = :code " +
            "  AND o.isTest = false " +
            "  AND o.createdAt >= :since " +
            "GROUP BY o.channel, o.status")
    List<Object[]> aggregateChannelStatusByTemplate(
            @Param("code") String templateCode,
            @Param("since") LocalDateTime since);

    /**
     * PR-T7 P1 — render_warnings_json 가 비어있지 않은 row 카운트 (지난 N일).
     *
     * <p>render warning 은 발송은 성공했으나 변수 치환 누락 등으로 본문이 불완전한
     * 케이스를 의미. 운영자가 카피 정정 필요 시점을 판단하는 핵심 지표 (스펙 §14).</p>
     */
    @Query("SELECT COUNT(o) " +
            "FROM NotificationOutbox o " +
            "WHERE o.templateCode = :code " +
            "  AND o.isTest = false " +
            "  AND o.createdAt >= :since " +
            "  AND o.renderWarningsJson IS NOT NULL " +
            "  AND LENGTH(TRIM(o.renderWarningsJson)) > 2")  // "{}" 빈 객체 제외
    long countRenderWarningsByTemplate(
            @Param("code") String templateCode,
            @Param("since") LocalDateTime since);
}
