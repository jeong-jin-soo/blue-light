package com.bluelight.backend.api.lewserviceorder;

import com.bluelight.backend.api.lewserviceorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceManagerUploadDto;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderDashboardResponse;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrder;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderPayment;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderPaymentRepository;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderRepository;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderStatus;
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
 * Request for LEW Service 주문 서비스 — SLD_MANAGER 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LewServiceManagerService {

    private final LewServiceOrderRepository lewServiceOrderRepository;
    private final LewServiceOrderPaymentRepository lewServiceOrderPaymentRepository;
    private final UserRepository userRepository;

    /**
     * 대시보드 통계 조회 (상태별 건수)
     */
    public LewServiceOrderDashboardResponse getDashboard() {
        return LewServiceOrderDashboardResponse.builder()
                .total(lewServiceOrderRepository.count())
                .pendingQuote(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.PENDING_QUOTE))
                .quoteProposed(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.QUOTE_PROPOSED))
                .pendingPayment(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.PENDING_PAYMENT))
                .paid(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.PAID))
                .inProgress(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.IN_PROGRESS))
                .deliverableUploaded(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.SLD_UPLOADED))
                .completed(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.COMPLETED))
                .build();
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     */
    public Page<LewServiceOrderResponse> getAllOrders(String status, Pageable pageable) {
        Page<LewServiceOrder> page;
        if (status == null || status.isBlank()) {
            page = lewServiceOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            LewServiceOrderStatus orderStatus = parseStatus(status);
            page = lewServiceOrderRepository.findByStatusOrderByCreatedAtDesc(orderStatus, pageable);
        }
        return page.map(LewServiceOrderResponse::from);
    }

    /**
     * 주문 상세 조회
     */
    public LewServiceOrderResponse getOrder(Long orderSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 견적 제안
     */
    @Transactional
    public LewServiceOrderResponse proposeQuote(Long orderSeq, ProposeQuoteRequest request) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        order.proposeQuote(request.getQuoteAmount(), request.getQuoteNote());
        log.info("Request for LEW Service 견적 제안: orderSeq={}, amount={}", orderSeq, request.getQuoteAmount());
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 담당 매니저 배정
     */
    @Transactional
    public LewServiceOrderResponse assignManager(Long orderSeq, Long managerSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        User manager = userRepository.findById(managerSeq)
                .orElseThrow(() -> new BusinessException(
                        "Manager not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        UserRole role = manager.getRole();
        if (role != UserRole.SLD_MANAGER && role != UserRole.ADMIN && role != UserRole.SYSTEM_ADMIN) {
            throw new BusinessException(
                    "User is not an LewService Manager", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }

        order.assignManager(manager);
        log.info("LewService 매니저 배정: orderSeq={}, managerSeq={}", orderSeq, managerSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 담당 매니저 배정 해제
     */
    @Transactional
    public LewServiceOrderResponse unassignManager(Long orderSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        order.unassignManager();
        log.info("LewService 매니저 배정 해제: orderSeq={}", orderSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * Request for LEW Service 파일 업로드 완료
     */
    @Transactional
    public LewServiceOrderResponse uploadSld(Long orderSeq, LewServiceManagerUploadDto request) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        // PAID → IN_PROGRESS 자동 전환
        order.ensureInProgress();
        order.uploadSld(request.getFileSeq(), request.getManagerNote());
        log.info("Request for LEW Service 업로드 완료: orderSeq={}, fileSeq={}", orderSeq, request.getFileSeq());
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 결제 확인 (관리자가 수동 확인)
     */
    @Transactional
    public LewServiceOrderResponse confirmPayment(Long orderSeq, String transactionId, String paymentMethod) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);

        if (order.getStatus() != LewServiceOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    "Payment confirmation is only available in PENDING_PAYMENT status. Current: " + order.getStatus(),
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }

        LewServiceOrderPayment payment = LewServiceOrderPayment.builder()
                .lewServiceOrder(order)
                .amount(order.getQuoteAmount())
                .paymentMethod(paymentMethod)
                .transactionId(transactionId)
                .build();
        lewServiceOrderPaymentRepository.save(payment);

        order.markAsPaid();
        log.info("Request for LEW Service 결제 확인: orderSeq={}, amount={}, transactionId={}",
                orderSeq, order.getQuoteAmount(), transactionId);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 주문 완료 처리 (관리자)
     */
    @Transactional
    public LewServiceOrderResponse markComplete(Long orderSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        order.complete();
        log.info("Request for LEW Service 주문 완료 처리: orderSeq={}", orderSeq);
        return LewServiceOrderResponse.from(order);
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private LewServiceOrder findOrderOrThrow(Long orderSeq) {
        return lewServiceOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "Request for LEW Service order not found", HttpStatus.NOT_FOUND, "LEW_SERVICE_ORDER_NOT_FOUND"));
    }

    private LewServiceOrderStatus parseStatus(String status) {
        try {
            return LewServiceOrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid order status: " + status, HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }
}
