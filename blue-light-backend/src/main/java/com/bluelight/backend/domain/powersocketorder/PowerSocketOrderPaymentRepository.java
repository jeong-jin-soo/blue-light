package com.bluelight.backend.domain.powersocketorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Power Socket 주문 결제 Repository
 */
public interface PowerSocketOrderPaymentRepository extends JpaRepository<PowerSocketOrderPayment, Long> {

    /**
     * 주문별 결제 내역
     */
    List<PowerSocketOrderPayment> findByPowerSocketOrderPowerSocketOrderSeq(Long powerSocketOrderSeq);
}
