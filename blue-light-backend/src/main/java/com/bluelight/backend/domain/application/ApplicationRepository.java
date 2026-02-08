package com.bluelight.backend.domain.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * 특정 사용자의 신청 목록 조회 (최신순)
     */
    List<Application> findByUserUserSeqOrderByCreatedAtDesc(Long userSeq);

    /**
     * 특정 상태의 신청 목록 조회
     */
    List<Application> findByStatus(ApplicationStatus status);

    /**
     * 특정 사용자의 특정 상태 신청 목록 조회
     */
    List<Application> findByUserUserSeqAndStatus(Long userSeq, ApplicationStatus status);

    /**
     * 전체 신청 목록 페이지네이션 (Admin)
     */
    Page<Application> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 상태별 전체 신청 목록 페이지네이션 (Admin)
     */
    Page<Application> findByStatusOrderByCreatedAtDesc(ApplicationStatus status, Pageable pageable);

    /**
     * 검색: 주소, 이름, 이메일, ID로 검색 (Admin)
     */
    @Query("SELECT a FROM Application a JOIN a.user u WHERE " +
           "(LOWER(a.address) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.applicationSeq AS string) LIKE CONCAT('%', :keyword, '%')) " +
           "ORDER BY a.createdAt DESC")
    Page<Application> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 상태 + 검색 복합 (Admin)
     */
    @Query("SELECT a FROM Application a JOIN a.user u WHERE " +
           "a.status = :status AND " +
           "(LOWER(a.address) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.applicationSeq AS string) LIKE CONCAT('%', :keyword, '%')) " +
           "ORDER BY a.createdAt DESC")
    Page<Application> searchByKeywordAndStatus(@Param("keyword") String keyword, @Param("status") ApplicationStatus status, Pageable pageable);

    /**
     * 상태별 건수 (Admin dashboard)
     */
    long countByStatus(ApplicationStatus status);

    /**
     * 미할당 신청 건수
     */
    long countByAssignedLewIsNull();

    /**
     * 특정 LEW에게 할당된 신청 건수
     */
    long countByAssignedLewUserSeq(Long lewSeq);

    /**
     * 특정 LEW에게 할당된 신청 목록 (최신순, 페이지네이션)
     */
    Page<Application> findByAssignedLewUserSeqOrderByCreatedAtDesc(Long lewSeq, Pageable pageable);

    /**
     * 특정 LEW에게 할당된 + 특정 상태의 신청 목록
     */
    Page<Application> findByAssignedLewUserSeqAndStatusOrderByCreatedAtDesc(
            Long lewSeq, ApplicationStatus status, Pageable pageable);
}
