package com.bluelight.backend.api.lewserviceorder;

import com.bluelight.backend.api.lewserviceorder.dto.CreateLewServiceOrderRequest;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderPaymentResponse;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderResponse;
import com.bluelight.backend.api.lewserviceorder.dto.UpdateLewServiceOrderRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrder;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderPaymentRepository;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderRepository;
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
 * Request for LEW Service 주문 서비스 — 신청자(Applicant) 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LewServiceOrderService {

    private final LewServiceOrderRepository lewServiceOrderRepository;
    private final LewServiceOrderPaymentRepository lewServiceOrderPaymentRepository;
    private final UserRepository userRepository;

    /**
     * Request for LEW Service 주문 생성
     */
    @Transactional
    public LewServiceOrderResponse createOrder(Long userSeq, CreateLewServiceOrderRequest request) {
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        LewServiceOrder order = LewServiceOrder.builder()
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
            log.info("Request for LEW Service 주문 자동 배정: orderUser={}, manager={}", userSeq, managers.get(0).getUserSeq());
        }

        lewServiceOrderRepository.save(order);
        log.info("Request for LEW Service 주문 생성: orderSeq={}, userSeq={}", order.getLewServiceOrderSeq(), userSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 내 주문 목록 조회
     */
    public List<LewServiceOrderResponse> getMyOrders(Long userSeq) {
        return lewServiceOrderRepository.findByUserUserSeqOrderByCreatedAtDesc(userSeq)
                .stream()
                .map(LewServiceOrderResponse::from)
                .toList();
    }

    /**
     * 주문 상세 조회 (소유권 검증)
     */
    public LewServiceOrderResponse getOrder(Long orderSeq, Long userSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 주문 수정 (PENDING_QUOTE 상태에서만)
     */
    @Transactional
    public LewServiceOrderResponse updateOrder(Long orderSeq, Long userSeq, UpdateLewServiceOrderRequest request) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.updateDetails(request.getApplicantNote(), request.getSketchFileSeq());
        log.info("Request for LEW Service 주문 수정: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 견적 수락
     */
    @Transactional
    public LewServiceOrderResponse acceptQuote(Long orderSeq, Long userSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.acceptQuote();
        log.info("Request for LEW Service 견적 수락: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 견적 거절
     */
    @Transactional
    public LewServiceOrderResponse rejectQuote(Long orderSeq, Long userSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.rejectQuote();
        log.info("Request for LEW Service 견적 거절: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 수정 요청 (SLD_UPLOADED 상태에서만)
     */
    @Transactional
    public LewServiceOrderResponse requestRevision(Long orderSeq, Long userSeq, String comment) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.requestRevision(comment);
        log.info("Request for LEW Service 수정 요청: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 완료 확인 (SLD_UPLOADED 상태에서 신청자가 확인)
     */
    @Transactional
    public LewServiceOrderResponse confirmCompletion(Long orderSeq, Long userSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.complete();
        log.info("Request for LEW Service 주문 완료 확인: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return LewServiceOrderResponse.from(order);
    }

    /**
     * 결제 내역 조회
     */
    public List<LewServiceOrderPaymentResponse> getPayments(Long orderSeq, Long userSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return lewServiceOrderPaymentRepository.findByLewServiceOrderLewServiceOrderSeq(orderSeq)
                .stream()
                .map(LewServiceOrderPaymentResponse::from)
                .toList();
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private LewServiceOrder findOrderOrThrow(Long orderSeq) {
        return lewServiceOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "Request for LEW Service order not found", HttpStatus.NOT_FOUND, "LEW_SERVICE_ORDER_NOT_FOUND"));
    }
}
