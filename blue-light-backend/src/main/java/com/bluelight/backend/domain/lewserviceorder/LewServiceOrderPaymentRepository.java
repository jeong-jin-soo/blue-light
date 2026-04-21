package com.bluelight.backend.domain.lewserviceorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Request for LEW Service 주문 결제 Repository
 */
public interface LewServiceOrderPaymentRepository extends JpaRepository<LewServiceOrderPayment, Long> {

    /**
     * 주문별 결제 내역
     */
    List<LewServiceOrderPayment> findByLewServiceOrderLewServiceOrderSeq(Long lewServiceOrderSeq);
}
