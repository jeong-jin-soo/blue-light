package com.bluelight.backend.domain.expiredlicenseorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpiredLicenseOrderPaymentRepository extends JpaRepository<ExpiredLicenseOrderPayment, Long> {

    List<ExpiredLicenseOrderPayment> findByExpiredLicenseOrderExpiredLicenseOrderSeq(Long expiredLicenseOrderSeq);
}
