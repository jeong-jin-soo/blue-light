package com.bluelight.backend.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * application 의 active (REQUESTED/UPLOADED/REJECTED) request 개수 — B-3 rate limit 검사에 사용.
     */
    @Query("SELECT COUNT(dr) FROM DocumentRequest dr " +
           "WHERE dr.application.applicationSeq = :applicationSeq " +
           "AND dr.status IN :statuses")
    long countByApplicationAndStatusIn(@Param("applicationSeq") Long applicationSeq,
                                       @Param("statuses") Collection<DocumentRequestStatus> statuses);

    /**
     * application 내 동일 타입 + active(REQUESTED/UPLOADED) 중복 감지 — AC-R5.
     * OTHER 타입은 customLabel 까지 비교해야 하므로 서비스에서 추가 필터 적용.
     */
    @Query("SELECT dr FROM DocumentRequest dr " +
           "WHERE dr.application.applicationSeq = :applicationSeq " +
           "AND dr.documentTypeCode = :code " +
           "AND dr.status IN :statuses")
    List<DocumentRequest> findActiveByApplicationAndType(@Param("applicationSeq") Long applicationSeq,
                                                         @Param("code") String code,
                                                         @Param("statuses") Collection<DocumentRequestStatus> statuses);
}
