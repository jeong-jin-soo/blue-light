package com.bluelight.backend.domain.sldorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * SLD 전용 주문 결제 Repository
 */
public interface SldOrderPaymentRepository extends JpaRepository<SldOrderPayment, Long> {

    /**
     * 주문별 결제 내역
     */
    List<SldOrderPayment> findBySldOrderSldOrderSeq(Long sldOrderSeq);
}
