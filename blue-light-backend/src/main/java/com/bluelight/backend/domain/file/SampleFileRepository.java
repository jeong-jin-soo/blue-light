package com.bluelight.backend.domain.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SampleFile Repository
 */
@Repository
public interface SampleFileRepository extends JpaRepository<SampleFile, Long> {

    /**
     * 카테고리 키로 샘플 파일 목록 조회 (정렬순)
     */
    List<SampleFile> findByCategoryKeyOrderBySortOrderAsc(String categoryKey);

    /**
     * 카테고리 키로 샘플 파일 수 조회
     */
    long countByCategoryKey(String categoryKey);

    /**
     * 카테고리 키로 전체 삭제
     */
    void deleteByCategoryKey(String categoryKey);
}
