package com.bluelight.backend.api.powersocketorder;

import com.bluelight.backend.api.powersocketorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketManagerUploadDto;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketOrderDashboardResponse;
import com.bluelight.backend.api.powersocketorder.dto.PowerSocketOrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PowerSocket Manager API 컨트롤러
 * - SLD_MANAGER / ADMIN / SYSTEM_ADMIN이 Power Socket 주문을 관리
 */
@Slf4j
@RestController
@RequestMapping("/api/power-socket-manager")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
public class PowerSocketManagerController {

    private final PowerSocketManagerService powerSocketManagerService;

    /**
     * 대시보드 통계 조회
     * GET /api/power-socket-manager/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<PowerSocketOrderDashboardResponse> getDashboard() {
        log.info("PowerSocket Manager 대시보드 조회");
        PowerSocketOrderDashboardResponse dashboard = powerSocketManagerService.getDashboard();
        return ResponseEntity.ok(dashboard);
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     * GET /api/power-socket-manager/orders?status=xxx&page=0&size=20
     */
    @GetMapping("/orders")
    public ResponseEntity<Page<PowerSocketOrderResponse>> getAllOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("PowerSocket Manager 주문 목록 조회: status={}, page={}, size={}", status, page, size);
        Page<PowerSocketOrderResponse> orders = powerSocketManagerService.getAllOrders(status, PageRequest.of(page, size));
        return ResponseEntity.ok(orders);
    }

    /**
     * 주문 상세 조회
     * GET /api/power-socket-manager/orders/{id}
     */
    @GetMapping("/orders/{id}")
    public ResponseEntity<PowerSocketOrderResponse> getOrder(@PathVariable Long id) {
        log.info("PowerSocket Manager 주문 상세 조회: orderSeq={}", id);
        PowerSocketOrderResponse response = powerSocketManagerService.getOrder(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 제안
     * POST /api/power-socket-manager/orders/{id}/propose-quote
     */
    @PostMapping("/orders/{id}/propose-quote")
    public ResponseEntity<PowerSocketOrderResponse> proposeQuote(
            @PathVariable Long id,
            @Valid @RequestBody ProposeQuoteRequest request) {
        log.info("Power Socket 견적 제안: orderSeq={}, amount={}", id, request.getQuoteAmount());
        PowerSocketOrderResponse response = powerSocketManagerService.proposeQuote(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정
     * POST /api/power-socket-manager/orders/{id}/assign
     */
    @PostMapping("/orders/{id}/assign")
    public ResponseEntity<PowerSocketOrderResponse> assignManager(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        Long managerUserSeq = body.get("managerUserSeq");
        log.info("PowerSocket 매니저 배정: orderSeq={}, managerSeq={}", id, managerUserSeq);
        PowerSocketOrderResponse response = powerSocketManagerService.assignManager(id, managerUserSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정 해제
     * DELETE /api/power-socket-manager/orders/{id}/assign
     */
    @DeleteMapping("/orders/{id}/assign")
    public ResponseEntity<PowerSocketOrderResponse> unassignManager(@PathVariable Long id) {
        log.info("PowerSocket 매니저 배정 해제: orderSeq={}", id);
        PowerSocketOrderResponse response = powerSocketManagerService.unassignManager(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Power Socket 업로드 완료 (SLD_MANAGER가 상태 전환)
     * POST /api/power-socket-manager/orders/{id}/sld-uploaded
     */
    @PostMapping("/orders/{id}/sld-uploaded")
    public ResponseEntity<PowerSocketOrderResponse> uploadSld(
            @PathVariable Long id,
            @Valid @RequestBody PowerSocketManagerUploadDto request) {
        log.info("Power Socket 업로드 완료: orderSeq={}, fileSeq={}", id, request.getFileSeq());
        PowerSocketOrderResponse response = powerSocketManagerService.uploadSld(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 확인 (관리자 수동 확인)
     * POST /api/power-socket-manager/orders/{id}/payment/confirm
     */
    @PostMapping("/orders/{id}/payment/confirm")
    public ResponseEntity<PowerSocketOrderResponse> confirmPayment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String transactionId = body.get("transactionId");
        String paymentMethod = body.get("paymentMethod");
        log.info("Power Socket 결제 확인: orderSeq={}, transactionId={}", id, transactionId);
        PowerSocketOrderResponse response = powerSocketManagerService.confirmPayment(id, transactionId, paymentMethod);
        return ResponseEntity.ok(response);
    }

    /**
     * 주문 완료 처리
     * POST /api/power-socket-manager/orders/{id}/complete
     */
    @PostMapping("/orders/{id}/complete")
    public ResponseEntity<PowerSocketOrderResponse> markComplete(@PathVariable Long id) {
        log.info("Power Socket 주문 완료 처리: orderSeq={}", id);
        PowerSocketOrderResponse response = powerSocketManagerService.markComplete(id);
        return ResponseEntity.ok(response);
    }
}
