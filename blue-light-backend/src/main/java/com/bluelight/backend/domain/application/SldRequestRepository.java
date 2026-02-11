package com.bluelight.backend.domain.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SldRequestRepository extends JpaRepository<SldRequest, Long> {

    Optional<SldRequest> findByApplicationApplicationSeq(Long applicationSeq);
}
