package com.bluelight.backend.domain.docnumber;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 문서 타입 마스터 Repository.
 * Soft delete는 엔티티의 {@code @SQLRestriction("deleted_at IS NULL")} 로 자동 필터.
 */
public interface DocumentNumberTypeRepository extends JpaRepository<DocumentNumberType, String> {

    /** 활성 타입만 조회. 비활성 또는 soft-deleted는 Optional.empty(). */
    Optional<DocumentNumberType> findByCodeAndActiveTrue(String code);

    /** prefix 중복 검증용 (Admin UI에서 신규 타입 추가 시). */
    boolean existsByPrefix(String prefix);
}
