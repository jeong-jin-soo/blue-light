package com.bluelight.backend.domain.concierge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ConciergeNote Repository (★ Kaki Concierge v1.5)
 */
@Repository
public interface ConciergeNoteRepository extends JpaRepository<ConciergeNote, Long> {

    /**
     * 특정 ConciergeRequest의 노트 목록 (최신순)
     * - 요청 상세 페이지 타임라인 렌더링
     */
    List<ConciergeNote> findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(Long requestSeq);
}
