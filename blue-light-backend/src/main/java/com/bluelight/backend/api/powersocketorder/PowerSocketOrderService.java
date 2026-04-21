package com.bluelight.backend.api.powersocketorder;

import com.bluelight.backend.api.powersocketorder.dto.CreatePowerSocketOrderRequest;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketOrderPaymentResponse;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketOrderResponse;
import com.bluelight.backend.api.powersocketorder.dto.UpdatePowerSocketOrderRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.powersocketorder.PowerSocketOrder;
import com.bluelight.backend.domain.powersocketorder.PowerSocketOrderPaymentRepository;
import com.bluelight.backend.domain.powersocketorder.PowerSocketOrderRepository;
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
 * Power Socket 주문 서비스 — 신청자(Applicant) 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PowerSocketOrderService {

    private final PowerSocketOrderRepository powerSocketOrderRepository;
    private final PowerSocketOrderPaymentRepository powerSocketOrderPaymentRepository;
    private final UserRepository userRepository;

    /**
     * Power Socket 주문 생성
     */
    @Transactional
    public PowerSocketOrderResponse createOrder(Long userSeq, CreatePowerSocketOrderRequest request) {
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        PowerSocketOrder order = PowerSocketOrder.builder()
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
            log.info("Power Socket 주문 자동 배정: orderUser={}, manager={}", userSeq, managers.get(0).getUserSeq());
        }

        powerSocketOrderRepository.save(order);
        log.info("Power Socket 주문 생성: orderSeq={}, userSeq={}", order.getPowerSocketOrderSeq(), userSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 내 주문 목록 조회
     */
    public List<PowerSocketOrderResponse> getMyOrders(Long userSeq) {
        return powerSocketOrderRepository.findByUserUserSeqOrderByCreatedAtDesc(userSeq)
                .stream()
                .map(PowerSocketOrderResponse::from)
                .toList();
    }

    /**
     * 주문 상세 조회 (소유권 검증)
     */
    public PowerSocketOrderResponse getOrder(Long orderSeq, Long userSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 주문 수정 (PENDING_QUOTE 상태에서만)
     */
    @Transactional
    public PowerSocketOrderResponse updateOrder(Long orderSeq, Long userSeq, UpdatePowerSocketOrderRequest request) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.updateDetails(request.getApplicantNote(), request.getSketchFileSeq());
        log.info("Power Socket 주문 수정: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 견적 수락
     */
    @Transactional
    public PowerSocketOrderResponse acceptQuote(Long orderSeq, Long userSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.acceptQuote();
        log.info("Power Socket 견적 수락: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 견적 거절
     */
    @Transactional
    public PowerSocketOrderResponse rejectQuote(Long orderSeq, Long userSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.rejectQuote();
        log.info("Power Socket 견적 거절: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 수정 요청 (SLD_UPLOADED 상태에서만)
     */
    @Transactional
    public PowerSocketOrderResponse requestRevision(Long orderSeq, Long userSeq, String comment) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.requestRevision(comment);
        log.info("SLD 수정 요청: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 완료 확인 (SLD_UPLOADED 상태에서 신청자가 확인)
     */
    @Transactional
    public PowerSocketOrderResponse confirmCompletion(Long orderSeq, Long userSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.complete();
        log.info("Power Socket 주문 완료 확인: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return PowerSocketOrderResponse.from(order);
    }

    /**
     * 결제 내역 조회
     */
    public List<PowerSocketOrderPaymentResponse> getPayments(Long orderSeq, Long userSeq) {
        PowerSocketOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return powerSocketOrderPaymentRepository.findByPowerSocketOrderPowerSocketOrderSeq(orderSeq)
                .stream()
                .map(PowerSocketOrderPaymentResponse::from)
                .toList();
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private PowerSocketOrder findOrderOrThrow(Long orderSeq) {
        return powerSocketOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "Power Socket order not found", HttpStatus.NOT_FOUND, "POWER_SOCKET_ORDER_NOT_FOUND"));
    }
}
