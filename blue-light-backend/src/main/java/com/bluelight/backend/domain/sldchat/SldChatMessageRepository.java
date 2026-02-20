package com.bluelight.backend.domain.sldchat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * SLD AI 채팅 메시지 Repository
 */
public interface SldChatMessageRepository extends JpaRepository<SldChatMessage, Long> {

    /**
     * 특정 신청의 채팅 이력 조회 (생성순)
     */
    List<SldChatMessage> findByApplicationSeqOrderByCreatedAtAsc(Long applicationSeq);

    /**
     * 특정 신청의 채팅 이력 삭제 (대화 초기화)
     */
    @Modifying
    @Query("DELETE FROM SldChatMessage m WHERE m.applicationSeq = :applicationSeq")
    void deleteByApplicationSeq(Long applicationSeq);

    /**
     * 특정 신청의 채팅 메시지 수 조회
     */
    long countByApplicationSeq(Long applicationSeq);

    // ── SLD 전용 주문 관련 ──────────────────────

    /**
     * SLD 전용 주문의 채팅 이력 조회 (생성순)
     */
    List<SldChatMessage> findBySldOrderSeqOrderByCreatedAtAsc(Long sldOrderSeq);

    /**
     * SLD 전용 주문의 채팅 이력 삭제 (대화 초기화)
     */
    @Modifying
    @Query("DELETE FROM SldChatMessage m WHERE m.sldOrderSeq = :sldOrderSeq")
    void deleteBySldOrderSeq(Long sldOrderSeq);

    /**
     * SLD 전용 주문의 채팅 메시지 수 조회
     */
    long countBySldOrderSeq(Long sldOrderSeq);
}
