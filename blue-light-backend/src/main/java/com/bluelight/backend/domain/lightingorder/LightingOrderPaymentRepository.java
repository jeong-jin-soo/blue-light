package com.bluelight.backend.domain.lightingorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Lighting Layout 주문 결제 Repository
 */
public interface LightingOrderPaymentRepository extends JpaRepository<LightingOrderPayment, Long> {

    /**
     * 주문별 결제 내역
     */
    List<LightingOrderPayment> findByLightingOrderLightingOrderSeq(Long lightingOrderSeq);
}
