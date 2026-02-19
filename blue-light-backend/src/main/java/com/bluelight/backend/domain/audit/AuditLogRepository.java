package com.bluelight.backend.domain.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:category IS NULL OR a.actionCategory = :category) AND " +
           "(:action IS NULL OR a.action = :action) AND " +
           "(:userSeq IS NULL OR a.userSeq = :userSeq) AND " +
           "(:entityType IS NULL OR a.entityType = :entityType) AND " +
           "(:entityId IS NULL OR a.entityId = :entityId) AND " +
           "(:startDate IS NULL OR a.createdAt >= :startDate) AND " +
           "(:endDate IS NULL OR a.createdAt <= :endDate) AND " +
           "(:search IS NULL OR " +
           "  LOWER(a.userEmail) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "  LOWER(a.description) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "  LOWER(a.entityId) LIKE CONCAT('%', :search, '%')) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> searchAuditLogs(
            @Param("category") AuditCategory category,
            @Param("action") AuditAction action,
            @Param("userSeq") Long userSeq,
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("search") String search,
            Pageable pageable);

    /**
     * 보존 기간 초과 로그 batch 삭제
     */
    @Modifying
    @Query(value = "DELETE FROM audit_logs WHERE created_at < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    /**
     * 삭제된 사용자의 감사 로그 개인정보 익명화 (PDPA Right to Erasure)
     * - user_email → 'deleted@anonymized'
     * - ip_address → null
     * - user_agent → null
     * - description 내 이메일 참조 → 'Deleted User'로 치환
     * - before_value, after_value → null (PII 포함 가능)
     */
    @Modifying
    @Query(value = """
            UPDATE audit_logs SET
                user_email = 'deleted@anonymized',
                ip_address = NULL,
                user_agent = NULL,
                description = REPLACE(description, :email, 'Deleted User'),
                before_value = NULL,
                after_value = NULL
            WHERE user_seq = :userSeq
            """, nativeQuery = true)
    int anonymizeByUserSeq(@Param("userSeq") Long userSeq, @Param("email") String email);
}
