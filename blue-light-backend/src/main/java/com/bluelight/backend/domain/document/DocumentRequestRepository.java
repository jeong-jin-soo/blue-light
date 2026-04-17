package com.bluelight.backend.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * DocumentRequest Repository
 */
@Repository
public interface DocumentRequestRepository extends JpaRepository<DocumentRequest, Long> {

    /**
     * 신청서 단위 전체 조회 (생성 시각 오름차순)
     */
    List<DocumentRequest> findByApplicationApplicationSeqOrderByCreatedAtAsc(Long applicationSeq);

    /**
     * 신청서 + 상태 필터 조회
     */
    List<DocumentRequest> findByApplicationApplicationSeqAndStatusOrderByCreatedAtAsc(
            Long applicationSeq, DocumentRequestStatus status);
}
