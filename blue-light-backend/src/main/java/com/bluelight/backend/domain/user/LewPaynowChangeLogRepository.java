package com.bluelight.backend.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@link LewPaynowChangeLog} 저장소 — append-only 이력 조회.
 */
public interface LewPaynowChangeLogRepository extends JpaRepository<LewPaynowChangeLog, Long> {

    /** 특정 사용자의 PayNow 변경 이력을 최신순으로 조회. */
    List<LewPaynowChangeLog> findByUser_UserSeqOrderByCreatedAtDesc(Long userSeq);
}
