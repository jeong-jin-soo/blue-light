package com.bluelight.backend.domain.breach;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 데이터 유출 통보 Repository
 */
@Repository
public interface DataBreachRepository extends JpaRepository<DataBreachNotification, Long> {

    /**
     * 전체 유출 통보 목록 (최신순)
     */
    Page<DataBreachNotification> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 상태별 유출 통보 목록
     */
    Page<DataBreachNotification> findByStatusOrderByCreatedAtDesc(BreachStatus status, Pageable pageable);

    /**
     * 미통보 유출 건 (PDPC 통보 전)
     */
    List<DataBreachNotification> findByPdpcNotifiedAtIsNullOrderByCreatedAtAsc();

    /**
     * 활성 유출 건 (해결 전)
     */
    List<DataBreachNotification> findByStatusNotOrderByCreatedAtDesc(BreachStatus status);
}
