package com.bluelight.backend.domain.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Application Entity Repository
 */
@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {

    /**
     * 특정 사용자의 신청 목록 조회
     */
    List<Application> findByUserUserSeq(Long userSeq);

    /**
     * 특정 상태의 신청 목록 조회
     */
    List<Application> findByStatus(ApplicationStatus status);

    /**
     * 특정 사용자의 특정 상태 신청 목록 조회
     */
    List<Application> findByUserUserSeqAndStatus(Long userSeq, ApplicationStatus status);
}
