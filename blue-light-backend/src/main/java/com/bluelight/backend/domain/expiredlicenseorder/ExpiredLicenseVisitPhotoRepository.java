package com.bluelight.backend.domain.expiredlicenseorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpiredLicenseVisitPhotoRepository extends JpaRepository<ExpiredLicenseVisitPhoto, Long> {

    List<ExpiredLicenseVisitPhoto> findByOrderExpiredLicenseOrderSeqOrderByUploadedAtAsc(Long orderSeq);
}
