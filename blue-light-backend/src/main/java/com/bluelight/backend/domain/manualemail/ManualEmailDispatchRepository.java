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
     * 활성화 시 정렬된 수신자 리스트 해시 비교로 확장 예정.</p>
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
