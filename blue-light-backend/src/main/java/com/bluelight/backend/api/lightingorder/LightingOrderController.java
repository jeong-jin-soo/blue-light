package com.bluelight.backend.api.lightingorder;

import com.bluelight.backend.api.lightingorder.dto.RevisionRequestDto;
import com.bluelight.backend.api.lightingorder.dto.LightingOrderPaymentResponse;
import com.bluelight.backend.api.lightingorder.dto.LightingOrderResponse;
import com.bluelight.backend.api.lightingorder.dto.CreateLightingOrderRequest;
import com.bluelight.backend.api.lightingorder.dto.UpdateLightingOrderRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Lighting Layout 주문 API 컨트롤러 (신청자용)
 * - 라이센스 신청 없이 SLD 도면만 요청하는 경우
 */
@Slf4j
@RestController
@RequestMapping("/api/lighting-orders")
@RequiredArgsConstructor
public class LightingOrderController {

    private final LightingOrderService lightingOrderService;

    /**
     * Lighting Layout 주문 생성
     * POST /api/lighting-orders
     */
    @PostMapping
    public ResponseEntity<LightingOrderResponse> createOrder(
            Authentication authentication,
            @Valid @RequestBody CreateLightingOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Lighting Layout 주문 생성 요청: userSeq={}", userSeq);
        LightingOrderResponse response = lightingOrderService.createOrder(userSeq, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내 Lighting Layout 주문 목록 조회
     * GET /api/lighting-orders
     */
    @GetMapping
    public ResponseEntity<List<LightingOrderResponse>> getMyOrders(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("내 Lighting Layout 주문 목록 조회: userSeq={}", userSeq);
        List<LightingOrderResponse> orders = lightingOrderService.getMyOrders(userSeq);
        return ResponseEntity.ok(orders);
    }

    /**
     * Lighting Layout 주문 상세 조회
     * GET /api/lighting-orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<LightingOrderResponse> getOrder(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Lighting Layout 주문 상세 조회: userSeq={}, orderSeq={}", userSeq, id);
        LightingOrderResponse response = lightingOrderService.getOrder(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * Lighting Layout 주문 수정 (PENDING_QUOTE 상태에서만)
     * PUT /api/lighting-orders/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<LightingOrderResponse> updateOrder(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateLightingOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Lighting Layout 주문 수정: userSeq={}, orderSeq={}", userSeq, id);
        LightingOrderResponse response = lightingOrderService.updateOrder(id, userSeq, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 수락
     * POST /api/lighting-orders/{id}/accept-quote
     */
    @PostMapping("/{id}/accept-quote")
    public ResponseEntity<LightingOrderResponse> acceptQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Lighting Layout 견적 수락: userSeq={}, orderSeq={}", userSeq, id);
        LightingOrderResponse response = lightingOrderService.acceptQuote(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 거절
     * POST /api/lighting-orders/{id}/reject-quote
     */
    @PostMapping("/{id}/reject-quote")
    public ResponseEntity<LightingOrderResponse> rejectQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Lighting Layout 견적 거절: userSeq={}, orderSeq={}", userSeq, id);
        LightingOrderResponse response = lightingOrderService.rejectQuote(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 수정 요청 (SLD_UPLOADED 상태에서)
     * POST /api/lighting-orders/{id}/request-revision
     */
    @PostMapping("/{id}/request-revision")
    public ResponseEntity<LightingOrderResponse> requestRevision(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody RevisionRequestDto request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 수정 요청: userSeq={}, orderSeq={}", userSeq, id);
        LightingOrderResponse response = lightingOrderService.requestRevision(id, userSeq, request.getComment());
        return ResponseEntity.ok(response);
    }

    /**
     * 완료 확인 (SLD_UPLOADED 상태에서 신청자가 확인)
     * POST /api/lighting-orders/{id}/confirm
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<LightingOrderResponse> confirmCompletion(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Lighting Layout 주문 완료 확인: userSeq={}, orderSeq={}", userSeq, id);
        LightingOrderResponse response = lightingOrderService.confirmCompletion(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 내역 조회
     * GET /api/lighting-orders/{id}/payments
     */
    @GetMapping("/{id}/payments")
    public ResponseEntity<List<LightingOrderPaymentResponse>> getPayments(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Lighting Layout 결제 내역 조회: userSeq={}, orderSeq={}", userSeq, id);
        List<LightingOrderPaymentResponse> payments = lightingOrderService.getPayments(id, userSeq);
        return ResponseEntity.ok(payments);
    }
}
