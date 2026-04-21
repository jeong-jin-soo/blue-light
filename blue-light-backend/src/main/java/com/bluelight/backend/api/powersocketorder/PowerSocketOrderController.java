package com.bluelight.backend.api.powersocketorder;

import com.bluelight.backend.api.powersocketorder.dto.RevisionRequestDto;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketOrderPaymentResponse;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketOrderResponse;
import com.bluelight.backend.api.powersocketorder.dto.CreatePowerSocketOrderRequest;
import com.bluelight.backend.api.powersocketorder.dto.UpdatePowerSocketOrderRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Power Socket 주문 API 컨트롤러 (신청자용)
 * - 라이센스 신청 없이 SLD 도면만 요청하는 경우
 */
@Slf4j
@RestController
@RequestMapping("/api/power-socket-orders")
@RequiredArgsConstructor
public class PowerSocketOrderController {

    private final PowerSocketOrderService powerSocketOrderService;

    /**
     * Power Socket 주문 생성
     * POST /api/power-socket-orders
     */
    @PostMapping
    public ResponseEntity<PowerSocketOrderResponse> createOrder(
            Authentication authentication,
            @Valid @RequestBody CreatePowerSocketOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Power Socket 주문 생성 요청: userSeq={}", userSeq);
        PowerSocketOrderResponse response = powerSocketOrderService.createOrder(userSeq, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내 Power Socket 주문 목록 조회
     * GET /api/power-socket-orders
     */
    @GetMapping
    public ResponseEntity<List<PowerSocketOrderResponse>> getMyOrders(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("내 Power Socket 주문 목록 조회: userSeq={}", userSeq);
        List<PowerSocketOrderResponse> orders = powerSocketOrderService.getMyOrders(userSeq);
        return ResponseEntity.ok(orders);
    }

    /**
     * Power Socket 주문 상세 조회
     * GET /api/power-socket-orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<PowerSocketOrderResponse> getOrder(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Power Socket 주문 상세 조회: userSeq={}, orderSeq={}", userSeq, id);
        PowerSocketOrderResponse response = powerSocketOrderService.getOrder(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * Power Socket 주문 수정 (PENDING_QUOTE 상태에서만)
     * PUT /api/power-socket-orders/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<PowerSocketOrderResponse> updateOrder(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdatePowerSocketOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Power Socket 주문 수정: userSeq={}, orderSeq={}", userSeq, id);
        PowerSocketOrderResponse response = powerSocketOrderService.updateOrder(id, userSeq, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 수락
     * POST /api/power-socket-orders/{id}/accept-quote
     */
    @PostMapping("/{id}/accept-quote")
    public ResponseEntity<PowerSocketOrderResponse> acceptQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Power Socket 견적 수락: userSeq={}, orderSeq={}", userSeq, id);
        PowerSocketOrderResponse response = powerSocketOrderService.acceptQuote(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 거절
     * POST /api/power-socket-orders/{id}/reject-quote
     */
    @PostMapping("/{id}/reject-quote")
    public ResponseEntity<PowerSocketOrderResponse> rejectQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Power Socket 견적 거절: userSeq={}, orderSeq={}", userSeq, id);
        PowerSocketOrderResponse response = powerSocketOrderService.rejectQuote(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 수정 요청 (SLD_UPLOADED 상태에서)
     * POST /api/power-socket-orders/{id}/request-revision
     */
    @PostMapping("/{id}/request-revision")
    public ResponseEntity<PowerSocketOrderResponse> requestRevision(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody RevisionRequestDto request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 수정 요청: userSeq={}, orderSeq={}", userSeq, id);
        PowerSocketOrderResponse response = powerSocketOrderService.requestRevision(id, userSeq, request.getComment());
        return ResponseEntity.ok(response);
    }

    /**
     * 완료 확인 (SLD_UPLOADED 상태에서 신청자가 확인)
     * POST /api/power-socket-orders/{id}/confirm
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<PowerSocketOrderResponse> confirmCompletion(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Power Socket 주문 완료 확인: userSeq={}, orderSeq={}", userSeq, id);
        PowerSocketOrderResponse response = powerSocketOrderService.confirmCompletion(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 내역 조회
     * GET /api/power-socket-orders/{id}/payments
     */
    @GetMapping("/{id}/payments")
    public ResponseEntity<List<PowerSocketOrderPaymentResponse>> getPayments(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Power Socket 결제 내역 조회: userSeq={}, orderSeq={}", userSeq, id);
        List<PowerSocketOrderPaymentResponse> payments = powerSocketOrderService.getPayments(id, userSeq);
        return ResponseEntity.ok(payments);
    }
}
