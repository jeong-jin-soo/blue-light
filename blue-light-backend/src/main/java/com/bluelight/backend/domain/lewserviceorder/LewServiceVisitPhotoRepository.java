package com.bluelight.backend.domain.lewserviceorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LewServiceVisitPhotoRepository extends JpaRepository<LewServiceVisitPhoto, Long> {

    List<LewServiceVisitPhoto> findByOrderLewServiceOrderSeqOrderByUploadedAtAsc(Long orderSeq);
}
