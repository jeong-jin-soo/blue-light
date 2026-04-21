package com.bluelight.backend.api.lewserviceorder;

import com.bluelight.backend.api.lewserviceorder.dto.RevisionRequestDto;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderPaymentResponse;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderResponse;
import com.bluelight.backend.api.lewserviceorder.dto.CreateLewServiceOrderRequest;
import com.bluelight.backend.api.lewserviceorder.dto.UpdateLewServiceOrderRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Request for LEW Service 주문 API 컨트롤러 (신청자용)
 * - 라이센스 신청 없이 SLD 도면만 요청하는 경우
 */
@Slf4j
@RestController
@RequestMapping("/api/lew-service-orders")
@RequiredArgsConstructor
public class LewServiceOrderController {

    private final LewServiceOrderService lewServiceOrderService;

    /**
     * Request for LEW Service 주문 생성
     * POST /api/lew-service-orders
     */
    @PostMapping
    public ResponseEntity<LewServiceOrderResponse> createOrder(
            Authentication authentication,
            @Valid @RequestBody CreateLewServiceOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Request for LEW Service 주문 생성 요청: userSeq={}", userSeq);
        LewServiceOrderResponse response = lewServiceOrderService.createOrder(userSeq, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내 Request for LEW Service 주문 목록 조회
     * GET /api/lew-service-orders
     */
    @GetMapping
    public ResponseEntity<List<LewServiceOrderResponse>> getMyOrders(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("내 Request for LEW Service 주문 목록 조회: userSeq={}", userSeq);
        List<LewServiceOrderResponse> orders = lewServiceOrderService.getMyOrders(userSeq);
        return ResponseEntity.ok(orders);
    }

    /**
     * Request for LEW Service 주문 상세 조회
     * GET /api/lew-service-orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<LewServiceOrderResponse> getOrder(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Request for LEW Service 주문 상세 조회: userSeq={}, orderSeq={}", userSeq, id);
        LewServiceOrderResponse response = lewServiceOrderService.getOrder(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * Request for LEW Service 주문 수정 (PENDING_QUOTE 상태에서만)
     * PUT /api/lew-service-orders/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<LewServiceOrderResponse> updateOrder(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateLewServiceOrderRequest request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Request for LEW Service 주문 수정: userSeq={}, orderSeq={}", userSeq, id);
        LewServiceOrderResponse response = lewServiceOrderService.updateOrder(id, userSeq, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 수락
     * POST /api/lew-service-orders/{id}/accept-quote
     */
    @PostMapping("/{id}/accept-quote")
    public ResponseEntity<LewServiceOrderResponse> acceptQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Request for LEW Service 견적 수락: userSeq={}, orderSeq={}", userSeq, id);
        LewServiceOrderResponse response = lewServiceOrderService.acceptQuote(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 거절
     * POST /api/lew-service-orders/{id}/reject-quote
     */
    @PostMapping("/{id}/reject-quote")
    public ResponseEntity<LewServiceOrderResponse> rejectQuote(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Request for LEW Service 견적 거절: userSeq={}, orderSeq={}", userSeq, id);
        LewServiceOrderResponse response = lewServiceOrderService.rejectQuote(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 수정 요청 (SLD_UPLOADED 상태에서)
     * POST /api/lew-service-orders/{id}/request-revision
     */
    @PostMapping("/{id}/request-revision")
    public ResponseEntity<LewServiceOrderResponse> requestRevision(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody RevisionRequestDto request) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("SLD 수정 요청: userSeq={}, orderSeq={}", userSeq, id);
        LewServiceOrderResponse response = lewServiceOrderService.requestRevision(id, userSeq, request.getComment());
        return ResponseEntity.ok(response);
    }

    /**
     * 완료 확인 (SLD_UPLOADED 상태에서 신청자가 확인)
     * POST /api/lew-service-orders/{id}/confirm
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<LewServiceOrderResponse> confirmCompletion(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Request for LEW Service 주문 완료 확인: userSeq={}, orderSeq={}", userSeq, id);
        LewServiceOrderResponse response = lewServiceOrderService.confirmCompletion(id, userSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 내역 조회
     * GET /api/lew-service-orders/{id}/payments
     */
    @GetMapping("/{id}/payments")
    public ResponseEntity<List<LewServiceOrderPaymentResponse>> getPayments(
            Authentication authentication,
            @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Request for LEW Service 결제 내역 조회: userSeq={}, orderSeq={}", userSeq, id);
        List<LewServiceOrderPaymentResponse> payments = lewServiceOrderService.getPayments(id, userSeq);
        return ResponseEntity.ok(payments);
    }
}
