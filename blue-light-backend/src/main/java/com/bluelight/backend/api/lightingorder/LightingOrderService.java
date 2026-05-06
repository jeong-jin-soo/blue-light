package com.bluelight.backend.api.lightingorder;

import com.bluelight.backend.api.lightingorder.dto.CreateLightingOrderRequest;
import com.bluelight.backend.api.lightingorder.dto.LightingOrderPaymentResponse;
import com.bluelight.backend.api.lightingorder.dto.LightingOrderResponse;
import com.bluelight.backend.api.lightingorder.dto.UpdateLightingOrderRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.lightingorder.LightingOrder;
import com.bluelight.backend.domain.lightingorder.LightingOrderPaymentRepository;
import com.bluelight.backend.domain.lightingorder.LightingOrderRepository;
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
 * Lighting Layout 주문 서비스 — 신청자(Applicant) 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LightingOrderService {

    private final LightingOrderRepository lightingOrderRepository;
    private final LightingOrderPaymentRepository lightingOrderPaymentRepository;
    private final UserRepository userRepository;

    /**
     * Lighting Layout 주문 생성
     */
    @Transactional
    public LightingOrderResponse createOrder(Long userSeq, CreateLightingOrderRequest request) {
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        LightingOrder order = LightingOrder.builder()
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
            log.info("Lighting Layout 주문 자동 배정: orderUser={}, manager={}", userSeq, managers.get(0).getUserSeq());
        }

        lightingOrderRepository.save(order);
        log.info("Lighting Layout 주문 생성: orderSeq={}, userSeq={}", order.getLightingOrderSeq(), userSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 내 주문 목록 조회
     */
    public List<LightingOrderResponse> getMyOrders(Long userSeq) {
        return lightingOrderRepository.findByUserUserSeqOrderByCreatedAtDesc(userSeq)
                .stream()
                .map(LightingOrderResponse::from)
                .toList();
    }

    /**
     * 주문 상세 조회 (소유권 검증)
     */
    public LightingOrderResponse getOrder(Long orderSeq, Long userSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 주문 수정 (PENDING_QUOTE 상태에서만)
     */
    @Transactional
    public LightingOrderResponse updateOrder(Long orderSeq, Long userSeq, UpdateLightingOrderRequest request) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.updateDetails(request.getApplicantNote(), request.getSketchFileSeq());
        log.info("Lighting Layout 주문 수정: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 견적 수락
     */
    @Transactional
    public LightingOrderResponse acceptQuote(Long orderSeq, Long userSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.acceptQuote();
        log.info("Lighting Layout 견적 수락: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 견적 거절
     */
    @Transactional
    public LightingOrderResponse rejectQuote(Long orderSeq, Long userSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.rejectQuote();
        log.info("Lighting Layout 견적 거절: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 수정 요청 (SLD_UPLOADED 상태에서만)
     */
    @Transactional
    public LightingOrderResponse requestRevision(Long orderSeq, Long userSeq, String comment) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.requestRevision(comment);
        log.info("Lighting Layout 수정 요청: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 완료 확인 (SLD_UPLOADED 상태에서 신청자가 확인)
     */
    @Transactional
    public LightingOrderResponse confirmCompletion(Long orderSeq, Long userSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.complete();
        log.info("Lighting Layout 주문 완료 확인: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LightingOrderResponse.from(order);
    }

    /**
     * 결제 내역 조회
     */
    public List<LightingOrderPaymentResponse> getPayments(Long orderSeq, Long userSeq) {
        LightingOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return lightingOrderPaymentRepository.findByLightingOrderLightingOrderSeq(orderSeq)
                .stream()
                .map(LightingOrderPaymentResponse::from)
                .toList();
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private LightingOrder findOrderOrThrow(Long orderSeq) {
        return lightingOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "Lighting Layout order not found", HttpStatus.NOT_FOUND, "LIGHTING_ORDER_NOT_FOUND"));
    }
}
