package com.bluelight.backend.domain.expiredlicenseorder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpiredLicenseOrderRepository extends JpaRepository<ExpiredLicenseOrder, Long> {

    List<ExpiredLicenseOrder> findByUserUserSeqOrderByCreatedAtDesc(Long userSeq);

    Page<ExpiredLicenseOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ExpiredLicenseOrder> findByStatusOrderByCreatedAtDesc(ExpiredLicenseOrderStatus status, Pageable pageable);

    Page<ExpiredLicenseOrder> findByAssignedManagerUserSeqOrderByCreatedAtDesc(Long managerSeq, Pageable pageable);

    long countByStatus(ExpiredLicenseOrderStatus status);
}
