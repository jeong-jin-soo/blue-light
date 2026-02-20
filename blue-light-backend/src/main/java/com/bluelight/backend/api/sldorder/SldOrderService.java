package com.bluelight.backend.api.sldorder;

import com.bluelight.backend.api.sldorder.dto.CreateSldOrderRequest;
import com.bluelight.backend.api.sldorder.dto.SldOrderPaymentResponse;
import com.bluelight.backend.api.sldorder.dto.SldOrderResponse;
import com.bluelight.backend.api.sldorder.dto.UpdateSldOrderRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.sldorder.SldOrder;
import com.bluelight.backend.domain.sldorder.SldOrderPaymentRepository;
import com.bluelight.backend.domain.sldorder.SldOrderRepository;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SLD 전용 주문 서비스 — 신청자(Applicant) 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SldOrderService {

    private final SldOrderRepository sldOrderRepository;
    private final SldOrderPaymentRepository sldOrderPaymentRepository;
    private final UserRepository userRepository;

    /**
     * SLD 주문 생성
     */
    @Transactional
    public SldOrderResponse createOrder(Long userSeq, CreateSldOrderRequest request) {
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        SldOrder order = SldOrder.builder()
                .user(user)
                .address(request.getAddress())
                .postalCode(request.getPostalCode())
                .buildingType(request.getBuildingType())
                .selectedKva(request.getSelectedKva())
                .applicantNote(request.getApplicantNote())
                .build();

        // SLD_MANAGER가 1명뿐이면 자동 배정
        List<User> managers = userRepository.findByRoleAndApprovedStatus(UserRole.SLD_MANAGER, ApprovalStatus.APPROVED);
        if (managers.size() == 1) {
            order.assignManager(managers.get(0));
            log.info("SLD 주문 자동 배정: orderUser={}, manager={}", userSeq, managers.get(0).getUserSeq());
        }

        sldOrderRepository.save(order);
        log.info("SLD 주문 생성: orderSeq={}, userSeq={}", order.getSldOrderSeq(), userSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 내 주문 목록 조회
     */
    public List<SldOrderResponse> getMyOrders(Long userSeq) {
        return sldOrderRepository.findByUserUserSeqOrderByCreatedAtDesc(userSeq)
                .stream()
                .map(SldOrderResponse::from)
                .toList();
    }

    /**
     * 주문 상세 조회 (소유권 검증)
     */
    public SldOrderResponse getOrder(Long orderSeq, Long userSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 주문 수정 (PENDING_QUOTE 상태에서만)
     */
    @Transactional
    public SldOrderResponse updateOrder(Long orderSeq, Long userSeq, UpdateSldOrderRequest request) {
        SldOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.updateDetails(request.getApplicantNote(), request.getSketchFileSeq());
        log.info("SLD 주문 수정: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 견적 수락
     */
    @Transactional
    public SldOrderResponse acceptQuote(Long orderSeq, Long userSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.acceptQuote();
        log.info("SLD 견적 수락: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 견적 거절
     */
    @Transactional
    public SldOrderResponse rejectQuote(Long orderSeq, Long userSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.rejectQuote();
        log.info("SLD 견적 거절: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 수정 요청 (SLD_UPLOADED 상태에서만)
     */
    @Transactional
    public SldOrderResponse requestRevision(Long orderSeq, Long userSeq, String comment) {
        SldOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.requestRevision(comment);
        log.info("SLD 수정 요청: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 완료 확인 (SLD_UPLOADED 상태에서 신청자가 확인)
     */
    @Transactional
    public SldOrderResponse confirmCompletion(Long orderSeq, Long userSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.complete();
        log.info("SLD 주문 완료 확인: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return SldOrderResponse.from(order);
    }

    /**
     * 결제 내역 조회
     */
    public List<SldOrderPaymentResponse> getPayments(Long orderSeq, Long userSeq) {
        SldOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return sldOrderPaymentRepository.findBySldOrderSldOrderSeq(orderSeq)
                .stream()
                .map(SldOrderPaymentResponse::from)
                .toList();
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private SldOrder findOrderOrThrow(Long orderSeq) {
        return sldOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD order not found", HttpStatus.NOT_FOUND, "SLD_ORDER_NOT_FOUND"));
    }
}
