package com.bluelight.backend.domain.file;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * FileEntity Repository
 */
@Repository
public interface FileRepository extends JpaRepository<FileEntity, Long> {

    /**
     * 특정 신청의 모든 파일 조회
     */
    List<FileEntity> findByApplicationApplicationSeq(Long applicationSeq);

    /**
     * 특정 신청의 특정 타입 파일 조회
     */
    List<FileEntity> findByApplicationApplicationSeqAndFileType(Long applicationSeq, FileType fileType);

    /**
     * 특정 Expired License 주문의 모든 파일 조회
     */
    List<FileEntity> findByExpiredLicenseOrderExpiredLicenseOrderSeq(Long expiredLicenseOrderSeq);

    /**
     * 특정 Expired License 주문의 특정 타입 파일 조회
     */
    List<FileEntity> findByExpiredLicenseOrderExpiredLicenseOrderSeqAndFileType(
            Long expiredLicenseOrderSeq, FileType fileType);
}
