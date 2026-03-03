package com.bluelight.backend.domain.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * SampleFile Repository
 */
@Repository
public interface SampleFileRepository extends JpaRepository<SampleFile, Long> {

    /**
     * 카테고리 키로 샘플 파일 조회
     */
    Optional<SampleFile> findByCategoryKey(String categoryKey);
}
