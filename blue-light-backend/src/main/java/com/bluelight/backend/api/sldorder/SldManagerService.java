package com.bluelight.backend.api.sldorder;

import com.bluelight.backend.api.sldorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.sldorder.dto.SldManagerUploadDto;
import com.bluelight.backend.api.sldorder.dto.SldOrderDashboardResponse;
import com.bluelight.backend.api.sldorder.dto.SldOrderResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.sldorder.SldOrder;
import com.bluelight.backend.domain.sldorder.SldOrderPayment;
import com.bluelight.backend.domain.sldorder.SldOrderPaymentRepository;
import com.bluelight.backend.domain.sldorder.SldOrderRepository;
import com.bluelight.backend.domain.sldorder.SldOrderStatus;
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
 * SLD 전용 주문 서비스 — SLD_MANAGER 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SldManagerService {

    private final SldOrderRepository sldOrderRepository;
    private final SldOrderPaymentRepository sldOrderPaymentRepository;
    private final UserRepository userRepository;

    /**
     * 대시보드 통계 조회 (상태별 건수)
     */
    public SldOrderDashboardResponse getDashboard() {
        return SldOrderDashboardResponse.builder()
                .total(sldOrderRepository.count())
                .pendingQuote(sldOrderRepository.countByStatus(SldOrderStatus.PENDING_QUOTE))
                .quoteProposed(sldOrderRepository.countByStatus(SldOrderStatus.QUOTE_PROPOSED))
                .pendingPayment(sldOrderRepository.countByStatus(SldOrderStatus.PENDING_PAYMENT))
                .paid(sldOrderRepository.countByStatus(SldOrderStatus.PAID))
                .inProgress(sldOrderRepository.countByStatus(SldOrderStatus.IN_PROGRESS))
                .sldUploaded(sldOrderRepository.countByStatus(SldOrderStatus.SLD_UPLOADED))
                .completed(sldOrderRepository.countByStatus(SldOrderStatus.COMPLETED))
                .build();
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     */
    public Page<SldOrderResponse> getAllOrders(String status, Pageable pageable) {
        Page<SldOrder> page;
        if (status == null || status.isBlank()) {
            page = sldOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            SldOrderStatus orderStatus = parseStatus(status);
            page = sldOrderRepository.findByStatusOrderByCreatedAtDesc(orderStatus, pageable);
        }
        return page.map(SldOrderResponse::from);
    }

    /**
     * 주문 상세 조회
     */
    public SldOrderResponse getOrder(Long orderSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 견적 제안
     */
    @Transactional
    public SldOrderResponse proposeQuote(Long orderSeq, ProposeQuoteRequest request) {
        SldOrder order = findOrderOrThrow(orderSeq);
        order.proposeQuote(request.getQuoteAmount(), request.getQuoteNote());
        log.info("SLD 견적 제안: orderSeq={}, amount={}", orderSeq, request.getQuoteAmount());
        return SldOrderResponse.from(order);
    }

    /**
     * 담당 매니저 배정
     */
    @Transactional
    public SldOrderResponse assignManager(Long orderSeq, Long managerSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        User manager = userRepository.findById(managerSeq)
                .orElseThrow(() -> new BusinessException(
                        "Manager not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        if (manager.getRole() != UserRole.SLD_MANAGER) {
            throw new BusinessException(
                    "User is not an SLD Manager", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }

        order.assignManager(manager);
        log.info("SLD 매니저 배정: orderSeq={}, managerSeq={}", orderSeq, managerSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 담당 매니저 배정 해제
     */
    @Transactional
    public SldOrderResponse unassignManager(Long orderSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        order.unassignManager();
        log.info("SLD 매니저 배정 해제: orderSeq={}", orderSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * SLD 파일 업로드 완료
     */
    @Transactional
    public SldOrderResponse uploadSld(Long orderSeq, SldManagerUploadDto request) {
        SldOrder order = findOrderOrThrow(orderSeq);
        // PAID → IN_PROGRESS 자동 전환
        order.ensureInProgress();
        order.uploadSld(request.getFileSeq(), request.getManagerNote());
        log.info("SLD 업로드 완료: orderSeq={}, fileSeq={}", orderSeq, request.getFileSeq());
        return SldOrderResponse.from(order);
    }

    /**
     * 결제 확인 (관리자가 수동 확인)
     */
    @Transactional
    public SldOrderResponse confirmPayment(Long orderSeq, String transactionId, String paymentMethod) {
        SldOrder order = findOrderOrThrow(orderSeq);

        if (order.getStatus() != SldOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    "Payment confirmation is only available in PENDING_PAYMENT status. Current: " + order.getStatus(),
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }

        SldOrderPayment payment = SldOrderPayment.builder()
                .sldOrder(order)
                .amount(order.getQuoteAmount())
                .paymentMethod(paymentMethod)
                .transactionId(transactionId)
                .build();
        sldOrderPaymentRepository.save(payment);

        order.markAsPaid();
        log.info("SLD 결제 확인: orderSeq={}, amount={}, transactionId={}",
                orderSeq, order.getQuoteAmount(), transactionId);
        return SldOrderResponse.from(order);
    }

    /**
     * 주문 완료 처리 (관리자)
     */
    @Transactional
    public SldOrderResponse markComplete(Long orderSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        order.complete();
        log.info("SLD 주문 완료 처리: orderSeq={}", orderSeq);
        return SldOrderResponse.from(order);
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private SldOrder findOrderOrThrow(Long orderSeq) {
        return sldOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD order not found", HttpStatus.NOT_FOUND, "SLD_ORDER_NOT_FOUND"));
    }

    private SldOrderStatus parseStatus(String status) {
        try {
            return SldOrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid order status: " + status, HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }
}
