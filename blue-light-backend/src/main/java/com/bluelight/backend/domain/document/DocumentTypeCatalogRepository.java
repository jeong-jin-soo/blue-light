package com.bluelight.backend.domain.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DocumentTypeCatalog Repository
 */
@Repository
public interface DocumentTypeCatalogRepository extends JpaRepository<DocumentTypeCatalog, String> {

    /**
     * 활성화된 카탈로그를 display_order 오름차순으로 반환
     */
    List<DocumentTypeCatalog> findAllByActiveTrueOrderByDisplayOrderAsc();

    /**
     * 코드 + active 조회
     */
    Optional<DocumentTypeCatalog> findByCodeAndActiveTrue(String code);
}
