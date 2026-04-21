package com.bluelight.backend.api.powersocketorder;

import com.bluelight.backend.api.powersocketorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketManagerUploadDto;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketOrderDashboardResponse;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketOrderResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.powersocketorder.PowerSocketOrder;
import com.bluelight.backend.domain.powersocketorder.PowerSocketOrderPayment;
import com.bluelight.backend.domain.powersocketorder.PowerSocketOrderPaymentRepository;
import com.bluelight.backend.domain.powersocketorder.PowerSocketOrderRepository;
import com.bluelight.backend.domain.powersocketorder.PowerSocketOrderStatus;
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
 * Power Socket 주문 서비스 — SLD_MANAGER 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PowerSocketManagerService {

    private final PowerSocketOrderRepository powerSocketOrderRepository;
    private final PowerSocketOrderPaymentRepository powerSocketOrderPaymentRepository;
    private final UserRepository userRepository;

    /**
     * 대시보드 통계 조회 (상태별 건수)
     */
    public PowerSocketOrderDashboardResponse getDashboard() {
        return PowerSocketOrderDashboardResponse.builder()
                .total(powerSocketOrderRepository.count())
                .pendingQuote(powerSocketOrderRepository.countByStatus(PowerSocketOrderStatus.PENDING_QUOTE))
                .quoteProposed(powerSocketOrderRepository.countByStatus(PowerSocketOrderStatus.QUOTE_PROPOSED))
                .pendingPayment(powerSocketOrderRepository.countByStatus(PowerSocketOrderStatus.PENDING_PAYMENT))
                .paid(powerSocketOrderRepository.countByStatus(PowerSocketOrderStatus.PAID))
                .inProgress(powerSocketOrderRepository.countByStatus(PowerSocketOrderStatus.IN_PROGRESS))
                .deliverableUploaded(powerSocketOrderRepository.countByStatus(PowerSocketOrderStatus.SLD_UPLOADED))
                .completed(powerSocketOrderRepository.countByStatus(PowerSocketOrderStatus.COMPLETED))
                .build();
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     */
    public Page<PowerSocketOrderResponse> getAllOrders(String status, Pageable pageable) {
        Page<PowerSocketOrder> page;
        if (status == null || status.isBlank()) {
            page = powerSocketOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            PowerSocketOrderStatus orderStatus = parseStatus(status);
            page = powerSocketOrderRepository.findByStatusOrderByCreatedAtDesc(orderStatus, pageable);
        }
        return page.map(PowerSocketOrderResponse::from);
    }

    /**
     * 주문 상세 조회
     */
    public PowerSocketOrderResponse getOrder(Long orderSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 견적 제안
     */
    @Transactional
    public PowerSocketOrderResponse proposeQuote(Long orderSeq, ProposeQuoteRequest request) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        order.proposeQuote(request.getQuoteAmount(), request.getQuoteNote());
        log.info("Power Socket 견적 제안: orderSeq={}, amount={}", orderSeq, request.getQuoteAmount());
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 담당 매니저 배정
     */
    @Transactional
    public PowerSocketOrderResponse assignManager(Long orderSeq, Long managerSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        User manager = userRepository.findById(managerSeq)
                .orElseThrow(() -> new BusinessException(
                        "Manager not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        UserRole role = manager.getRole();
        if (role != UserRole.SLD_MANAGER && role != UserRole.ADMIN && role != UserRole.SYSTEM_ADMIN) {
            throw new BusinessException(
                    "User is not an PowerSocket Manager", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }

        order.assignManager(manager);
        log.info("PowerSocket 매니저 배정: orderSeq={}, managerSeq={}", orderSeq, managerSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 담당 매니저 배정 해제
     */
    @Transactional
    public PowerSocketOrderResponse unassignManager(Long orderSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        order.unassignManager();
        log.info("PowerSocket 매니저 배정 해제: orderSeq={}", orderSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * Power Socket 파일 업로드 완료
     */
    @Transactional
    public PowerSocketOrderResponse uploadSld(Long orderSeq, PowerSocketManagerUploadDto request) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        // PAID → IN_PROGRESS 자동 전환
        order.ensureInProgress();
        order.uploadSld(request.getFileSeq(), request.getManagerNote());
        log.info("Power Socket 업로드 완료: orderSeq={}, fileSeq={}", orderSeq, request.getFileSeq());
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 결제 확인 (관리자가 수동 확인)
     */
    @Transactional
    public PowerSocketOrderResponse confirmPayment(Long orderSeq, String transactionId, String paymentMethod) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);

        if (order.getStatus() != PowerSocketOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    "Payment confirmation is only available in PENDING_PAYMENT status. Current: " + order.getStatus(),
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }

        PowerSocketOrderPayment payment = PowerSocketOrderPayment.builder()
                .powerSocketOrder(order)
                .amount(order.getQuoteAmount())
                .paymentMethod(paymentMethod)
                .transactionId(transactionId)
                .build();
        powerSocketOrderPaymentRepository.save(payment);

        order.markAsPaid();
        log.info("Power Socket 결제 확인: orderSeq={}, amount={}, transactionId={}",
                orderSeq, order.getQuoteAmount(), transactionId);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 주문 완료 처리 (관리자)
     */
    @Transactional
    public PowerSocketOrderResponse markComplete(Long orderSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        order.complete();
        log.info("Power Socket 주문 완료 처리: orderSeq={}", orderSeq);
        return PowerSocketOrderResponse.from(order);
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private PowerSocketOrder findOrderOrThrow(Long orderSeq) {
        return powerSocketOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "Power Socket order not found", HttpStatus.NOT_FOUND, "POWER_SOCKET_ORDER_NOT_FOUND"));
    }

    private PowerSocketOrderStatus parseStatus(String status) {
        try {
            return PowerSocketOrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid order status: " + status, HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }
}
