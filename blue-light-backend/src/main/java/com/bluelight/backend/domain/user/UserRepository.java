package com.bluelight.backend.domain.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * User Entity Repository
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 이메일로 사용자 조회
     */
    Optional<User> findByEmail(String email);

    /**
     * 이메일 존재 여부 확인
     */
    boolean existsByEmail(String email);

    /**
     * 이메일 인증 토큰으로 사용자 조회
     */
    Optional<User> findByEmailVerificationToken(String emailVerificationToken);

    /**
     * 역할 + 승인 상태로 사용자 목록 조회 (예: 승인된 LEW 목록)
     */
    List<User> findByRoleAndApprovedStatus(UserRole role, ApprovalStatus approvedStatus);

    /**
     * 전체 사용자 목록 페이지네이션 (Admin)
     */
    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 역할별 사용자 목록 페이지네이션 (Admin)
     */
    Page<User> findByRoleOrderByCreatedAtDesc(UserRole role, Pageable pageable);

    /**
     * 검색: 이름, 이메일, 회사명, UEN, ID로 검색 (Admin)
     */
    @Query("SELECT u FROM User u WHERE " +
           "(LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.companyName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.uen) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(u.userSeq AS string) LIKE CONCAT('%', :keyword, '%')) " +
           "ORDER BY u.createdAt DESC")
    Page<User> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 역할 + 검색 복합 (Admin)
     */
    @Query("SELECT u FROM User u WHERE " +
           "u.role = :role AND " +
           "(LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.companyName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.uen) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(u.userSeq AS string) LIKE CONCAT('%', :keyword, '%')) " +
           "ORDER BY u.createdAt DESC")
    Page<User> searchByKeywordAndRole(@Param("keyword") String keyword, @Param("role") UserRole role, Pageable pageable);

    /**
     * 역할별 사용자 수 (Admin dashboard)
     */
    long countByRole(UserRole role);

    /**
     * 역할 + 승인상태별 사용자 수 (Admin dashboard)
     */
    long countByRoleAndApprovedStatus(UserRole role, ApprovalStatus approvedStatus);
}
