package com.bluelight.backend.domain.manualemail;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ADMIN 수동 이메일 발송 row Repository.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5.2 발송 이력 목록.</p>
 *
 * <p>이력 페이지네이션은 {@code created_at DESC} 가 아니라 {@code dispatched_at DESC} 정렬을
 * 기본으로 한다 — 운영자 입장에서 "실제 발송된 시각" 이 더 직관적이며, PENDING 상태(SMTP 미시도)
 * 는 dispatchedAt=null 으로 정렬 끝에 떨어진다.</p>
 */
@Repository
public interface ManualEmailDispatchRepository extends JpaRepository<ManualEmailDispatch, Long> {

    /**
     * 멱등성 가드 (스펙 §6 AC-A9, D3=B): 30초 이내 동일 (sender + 수신자 + subject + bodyText)
     * 발송 row 가 존재하는지 검사. 본문 비교는 정확 일치를 사용한다 — 해시 충돌 위험을 회피하고
     * MySQL VARCHAR/TEXT 동등 비교가 인덱스가 없어도 30초 윈도우 내라면 스캔 비용이 무시할 수준.
     *
     * <p>PR-1 은 단일 수신자만 처리하므로 recipientEmail 단일 필드만 검사. PR-2 에서 다수 수신자
     * 활성화 시 정렬된 수신자 리스트 해시 비교({@link #findRecentDuplicateByHash})로 확장된다.
     * 본 메서드는 PR-1 기존 테스트 호환을 위해 유지하되 실제 호출 경로에서는 더 이상 사용되지
     * 않는다.</p>
     */
    @Query("""
            SELECT m FROM ManualEmailDispatch m
            WHERE m.senderUserSeq = :senderSeq
              AND m.recipientEmail = :recipientEmail
              AND m.subject = :subject
              AND m.bodyText = :bodyText
              AND m.createdAt >= :since
            ORDER BY m.createdAt DESC
            """)
    List<ManualEmailDispatch> findRecentDuplicate(
            @Param("senderSeq") Long senderSeq,
            @Param("recipientEmail") String recipientEmail,
            @Param("subject") String subject,
            @Param("bodyText") String bodyText,
            @Param("since") LocalDateTime since,
            Pageable limit);

    /**
     * PR-2 멱등성 가드 (D3=B 확장) — 정렬된 수신자 리스트 + subject + bodyText 의 SHA-256 해시를
     * 단일 컬럼({@code recipient_hash}) 비교로 검사한다. 단일/다수 수신자 양쪽에서 동일한 코드 경로.
     *
     * <p>인덱스: {@code idx_manual_email_recipient_hash (sender_user_seq, recipient_hash, created_at DESC)}
     * — 본 lookup 의 카디널리티를 만족.</p>
     */
    @Query("""
            SELECT m FROM ManualEmailDispatch m
            WHERE m.senderUserSeq = :senderSeq
              AND m.recipientHash = :recipientHash
              AND m.createdAt >= :since
            ORDER BY m.createdAt DESC
            """)
    List<ManualEmailDispatch> findRecentDuplicateByHash(
            @Param("senderSeq") Long senderSeq,
            @Param("recipientHash") String recipientHash,
            @Param("since") LocalDateTime since,
            Pageable limit);

    /**
     * PR-4 Daily cap 카운트 (스펙 §8.4 / AC-A12, D5=B).
     *
     * <p>발송 ADMIN 의 오늘(SGT 자정 기준 since~until) 발송된 수신자 수 합계를 단일 쿼리로 반환.
     * FAILED 상태 row 는 cap 에서 제외 — SMTP 실패는 ADMIN 의도적 행위 결과가 아니므로 한도를
     * 소진시키지 않는다. PENDING/SENT/PARTIAL_FAILED 는 모두 합산 (PENDING 도 ADMIN 의 발송
     * 의도 건수이므로 cap 에 포함, 동시 다발 spam 방지).</p>
     *
     * <p>합계 산정 기준: 본 PR 은 row 단위가 아니라 row 의 {@code recipientCount}(=
     * {@code sent_count + failed_count} 또는 PENDING 시 {@code recipientCount}) 합계를
     * cap 과 비교한다. 그러나 PENDING row 는 sent_count/failed_count 가 0 이라 그대로 합산
     * 하면 cap 우회가 가능 — 따라서 PENDING 은 row 1건당 인접 send list 길이를 cap 에 반영
     * 하기 위해 별도 산식이 필요하다. 본 메서드는 단순/안전한 정책으로 row 1건당 최대
     * 수신자 수를 (sent + failed) 또는 row 1 (PENDING fallback) 으로 계산한다.</p>
     *
     * <p>SQL: {@code SUM(GREATEST(sent_count + failed_count, CASE status WHEN 'PENDING' THEN 1 ELSE 0 END))}.
     * — 단, JPQL 은 GREATEST 미지원 → MySQL 함수 대신 CASE 식으로 표현.</p>
     *
     * <p>FAILED 상태(전체 실패)는 사용자 의도와 무관한 SMTP 다운이므로 cap 에서 빼는 정책 —
     * 운영 단순화를 위해 PENDING/SENT/PARTIAL_FAILED 만 합산한다.</p>
     */
    @Query("""
            SELECT COALESCE(SUM(
                CASE
                    WHEN m.dispatchStatus = com.bluelight.backend.domain.manualemail.DispatchStatus.PENDING
                        THEN CASE WHEN (m.sentCount + m.failedCount) > 0
                                  THEN (m.sentCount + m.failedCount)
                                  ELSE 1 END
                    WHEN m.dispatchStatus = com.bluelight.backend.domain.manualemail.DispatchStatus.SENT
                        THEN m.sentCount
                    WHEN m.dispatchStatus = com.bluelight.backend.domain.manualemail.DispatchStatus.PARTIAL_FAILED
                        THEN (m.sentCount + m.failedCount)
                    ELSE 0
                END
            ), 0)
            FROM ManualEmailDispatch m
            WHERE m.senderUserSeq = :senderSeq
              AND m.createdAt >= :sinceMidnight
              AND m.createdAt < :untilNextMidnight
            """)
    long sumDailyRecipientCountByCreatedBy(
            @Param("senderSeq") Long senderSeq,
            @Param("sinceMidnight") LocalDateTime sinceMidnight,
            @Param("untilNextMidnight") LocalDateTime untilNextMidnight);

    /**
     * 전체 이력 페이지네이션 — 필터 4종(sender/dateRange/status/relatedApplication)을
     * 옵션 파라미터로 받아 단일 쿼리로 처리. null 인 파라미터는 해당 조건을 무시한다.
     */
    @Query("""
            SELECT m FROM ManualEmailDispatch m
            WHERE (:senderSeq IS NULL OR m.senderUserSeq = :senderSeq)
              AND (:status IS NULL OR m.dispatchStatus = :status)
              AND (:relatedApplicationSeq IS NULL OR m.relatedApplicationSeq = :relatedApplicationSeq)
              AND (:from IS NULL OR m.createdAt >= :from)
              AND (:to IS NULL OR m.createdAt <= :to)
            ORDER BY
              CASE WHEN m.dispatchedAt IS NULL THEN 1 ELSE 0 END,
              m.dispatchedAt DESC,
              m.createdAt DESC
            """)
    Page<ManualEmailDispatch> searchHistory(
            @Param("senderSeq") Long senderSeq,
            @Param("status") DispatchStatus status,
            @Param("relatedApplicationSeq") Long relatedApplicationSeq,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
