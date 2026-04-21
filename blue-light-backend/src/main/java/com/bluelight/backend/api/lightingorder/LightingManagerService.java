package com.bluelight.backend.api.lightingorder;

import com.bluelight.backend.api.lightingorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.lightingorder.dto.LightingManagerUploadDto;
import com.bluelight.backend.api.lightingorder.dto.LightingOrderDashboardResponse;
import com.bluelight.backend.api.lightingorder.dto.LightingOrderResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.lightingorder.LightingOrder;
import com.bluelight.backend.domain.lightingorder.LightingOrderPayment;
import com.bluelight.backend.domain.lightingorder.LightingOrderPaymentRepository;
import com.bluelight.backend.domain.lightingorder.LightingOrderRepository;
import com.bluelight.backend.domain.lightingorder.LightingOrderStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lighting Layout 주문 서비스 — SLD_MANAGER 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LightingManagerService {

    private final LightingOrderRepository lightingOrderRepository;
    private final LightingOrderPaymentRepository lightingOrderPaymentRepository;
    private final UserRepository userRepository;

    /**
     * 대시보드 통계 조회 (상태별 건수)
     */
    public LightingOrderDashboardResponse getDashboard() {
        return LightingOrderDashboardResponse.builder()
                .total(lightingOrderRepository.count())
                .pendingQuote(lightingOrderRepository.countByStatus(LightingOrderStatus.PENDING_QUOTE))
                .quoteProposed(lightingOrderRepository.countByStatus(LightingOrderStatus.QUOTE_PROPOSED))
                .pendingPayment(lightingOrderRepository.countByStatus(LightingOrderStatus.PENDING_PAYMENT))
                .paid(lightingOrderRepository.countByStatus(LightingOrderStatus.PAID))
                .inProgress(lightingOrderRepository.countByStatus(LightingOrderStatus.IN_PROGRESS))
                .sldUploaded(lightingOrderRepository.countByStatus(LightingOrderStatus.SLD_UPLOADED))
                .completed(lightingOrderRepository.countByStatus(LightingOrderStatus.COMPLETED))
                .build();
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     */
    public Page<LightingOrderResponse> getAllOrders(String status, Pageable pageable) {
        Page<LightingOrder> page;
        if (status == null || status.isBlank()) {
            page = lightingOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            LightingOrderStatus orderStatus = parseStatus(status);
            page = lightingOrderRepository.findByStatusOrderByCreatedAtDesc(orderStatus, pageable);
        }
        return page.map(LightingOrderResponse::from);
    }

    /**
     * 주문 상세 조회
     */
    public LightingOrderResponse getOrder(Long orderSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 견적 제안
     */
    @Transactional
    public LightingOrderResponse proposeQuote(Long orderSeq, ProposeQuoteRequest request) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        order.proposeQuote(request.getQuoteAmount(), request.getQuoteNote());
        log.info("Lighting Layout 견적 제안: orderSeq={}, amount={}", orderSeq, request.getQuoteAmount());
        return LightingOrderResponse.from(order);
    }

    /**
     * 담당 매니저 배정
     */
    @Transactional
    public LightingOrderResponse assignManager(Long orderSeq, Long managerSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        User manager = userRepository.findById(managerSeq)
                .orElseThrow(() -> new BusinessException(
                        "Manager not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (manager.getRole() != UserRole.SLD_MANAGER) {
            throw new BusinessException(
                    "User is not an Lighting Manager", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }

        order.assignManager(manager);
        log.info("Lighting 매니저 배정: orderSeq={}, managerSeq={}", orderSeq, managerSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 담당 매니저 배정 해제
     */
    @Transactional
    public LightingOrderResponse unassignManager(Long orderSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        order.unassignManager();
        log.info("Lighting 매니저 배정 해제: orderSeq={}", orderSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * Lighting Layout 파일 업로드 완료
     */
    @Transactional
    public LightingOrderResponse uploadSld(Long orderSeq, LightingManagerUploadDto request) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        // PAID → IN_PROGRESS 자동 전환
        order.ensureInProgress();
        order.uploadSld(request.getFileSeq(), request.getManagerNote());
        log.info("Lighting Layout 업로드 완료: orderSeq={}, fileSeq={}", orderSeq, request.getFileSeq());
        return LightingOrderResponse.from(order);
    }

    /**
     * 결제 확인 (관리자가 수동 확인)
     */
    @Transactional
    public LightingOrderResponse confirmPayment(Long orderSeq, String transactionId, String paymentMethod) {
        LightingOrder order = findOrderOrThrow(orderSeq);

        if (order.getStatus() != LightingOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    "Payment confirmation is only available in PENDING_PAYMENT status. Current: " + order.getStatus(),
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }

        LightingOrderPayment payment = LightingOrderPayment.builder()
                .lightingOrder(order)
                .amount(order.getQuoteAmount())
                .paymentMethod(paymentMethod)
                .transactionId(transactionId)
                .build();
        lightingOrderPaymentRepository.save(payment);

        order.markAsPaid();
        log.info("Lighting Layout 결제 확인: orderSeq={}, amount={}, transactionId={}",
                orderSeq, order.getQuoteAmount(), transactionId);
        return LightingOrderResponse.from(order);
    }

    /**
     * 주문 완료 처리 (관리자)
     */
    @Transactional
    public LightingOrderResponse markComplete(Long orderSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        order.complete();
        log.info("Lighting Layout 주문 완료 처리: orderSeq={}", orderSeq);
        return LightingOrderResponse.from(order);
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private LightingOrder findOrderOrThrow(Long orderSeq) {
        return lightingOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "Lighting Layout order not found", HttpStatus.NOT_FOUND, "LIGHTING_ORDER_NOT_FOUND"));
    }

    private LightingOrderStatus parseStatus(String status) {
        try {
            return LightingOrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid order status: " + status, HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }
}
