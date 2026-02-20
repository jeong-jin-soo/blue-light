package com.bluelight.backend.api.sldorder;

import com.bluelight.backend.api.sldorder.dto.RevisionRequestDto;
import com.bluelight.backend.api.sldorder.dto.SldOrderPaymentResponse;
import com.bluelight.backend.api.sldorder.dto.SldOrderResponse;
import com.bluelight.backend.api.sldorder.dto.CreateSldOrderRequest;
import com.bluelight.backend.api.sldorder.dto.UpdateSldOrderRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SLD 전용 주문 API 컨트롤러 (신청자용)
 * - 라이센스 신청 없이 SLD 도면만 요청하는 경우
 */
@Slf4j
@RestController
@RequestMapping("/api/sld-orders")
@RequiredArgsConstructor
public class SldOrderController {

    private final SldOrderService sldOrderService;

    /**
     * SLD 주문 생성
     * POST /api/sld-orders
     */
    @PostMapping
    public ResponseEntity<SldOrderResponse> createOrder(
            Authentication authentication,
            @Valid @RequestBody CreateSldOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 주문 생성 요청: userSeq={}", userSeq);
        SldOrderResponse response = sldOrderService.createOrder(userSeq, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내 SLD 주문 목록 조회
     * GET /api/sld-orders
     */
    @GetMapping
    public ResponseEntity<List<SldOrderResponse>> getMyOrders(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("내 SLD 주문 목록 조회: userSeq={}", userSeq);
        List<SldOrderResponse> orders = sldOrderService.getMyOrders(userSeq);
        return ResponseEntity.ok(orders);
    }

    /**
     * SLD 주문 상세 조회
     * GET /api/sld-orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<SldOrderResponse> getOrder(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 주문 상세 조회: userSeq={}, orderSeq={}", userSeq, id);
        SldOrderResponse response = sldOrderService.getOrder(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * SLD 주문 수정 (PENDING_QUOTE 상태에서만)
     * PUT /api/sld-orders/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<SldOrderResponse> updateOrder(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateSldOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 주문 수정: userSeq={}, orderSeq={}", userSeq, id);
        SldOrderResponse response = sldOrderService.updateOrder(id, userSeq, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 수락
     * POST /api/sld-orders/{id}/accept-quote
     */
    @PostMapping("/{id}/accept-quote")
    public ResponseEntity<SldOrderResponse> acceptQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 견적 수락: userSeq={}, orderSeq={}", userSeq, id);
        SldOrderResponse response = sldOrderService.acceptQuote(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 거절
     * POST /api/sld-orders/{id}/reject-quote
     */
    @PostMapping("/{id}/reject-quote")
    public ResponseEntity<SldOrderResponse> rejectQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 견적 거절: userSeq={}, orderSeq={}", userSeq, id);
        SldOrderResponse response = sldOrderService.rejectQuote(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 수정 요청 (SLD_UPLOADED 상태에서)
     * POST /api/sld-orders/{id}/request-revision
     */
    @PostMapping("/{id}/request-revision")
    public ResponseEntity<SldOrderResponse> requestRevision(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody RevisionRequestDto request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 수정 요청: userSeq={}, orderSeq={}", userSeq, id);
        SldOrderResponse response = sldOrderService.requestRevision(id, userSeq, request.getComment());
        return ResponseEntity.ok(response);
    }

    /**
     * 완료 확인 (SLD_UPLOADED 상태에서 신청자가 확인)
     * POST /api/sld-orders/{id}/confirm
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<SldOrderResponse> confirmCompletion(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 주문 완료 확인: userSeq={}, orderSeq={}", userSeq, id);
        SldOrderResponse response = sldOrderService.confirmCompletion(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 내역 조회
     * GET /api/sld-orders/{id}/payments
     */
    @GetMapping("/{id}/payments")
    public ResponseEntity<List<SldOrderPaymentResponse>> getPayments(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 결제 내역 조회: userSeq={}, orderSeq={}", userSeq, id);
        List<SldOrderPaymentResponse> payments = sldOrderService.getPayments(id, userSeq);
        return ResponseEntity.ok(payments);
    }
}
