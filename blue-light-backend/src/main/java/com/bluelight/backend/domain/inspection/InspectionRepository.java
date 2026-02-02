package com.bluelight.backend.domain.inspection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Inspection Entity Repository
 */
@Repository
public interface InspectionRepository extends JpaRepository<Inspection, Long> {

    /**
     * 특정 신청의 점검 결과 조회
     */
    Optional<Inspection> findByApplicationApplicationSeq(Long applicationSeq);

    /**
     * 특정 점검자의 점검 목록 조회
     */
    List<Inspection> findByInspectorUserSeq(Long inspectorUserSeq);
}
