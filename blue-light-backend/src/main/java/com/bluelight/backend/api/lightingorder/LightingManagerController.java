package com.bluelight.backend.api.lightingorder;

import com.bluelight.backend.api.lightingorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.lightingorder.dto.LightingManagerUploadDto;
import com.bluelight.backend.api.lightingorder.dto.LightingOrderDashboardResponse;
import com.bluelight.backend.api.lightingorder.dto.LightingOrderResponse;
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
 * Lighting Manager API 컨트롤러
 * - SLD_MANAGER / ADMIN / SYSTEM_ADMIN이 Lighting Layout 주문을 관리
 */
@Slf4j
@RestController
@RequestMapping("/api/lighting-manager")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
public class LightingManagerController {

    private final LightingManagerService lightingManagerService;

    /**
     * 대시보드 통계 조회
     * GET /api/lighting-manager/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<LightingOrderDashboardResponse> getDashboard() {
        log.info("Lighting Manager 대시보드 조회");
        LightingOrderDashboardResponse dashboard = lightingManagerService.getDashboard();
        return ResponseEntity.ok(dashboard);
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     * GET /api/lighting-manager/orders?status=xxx&page=0&size=20
     */
    @GetMapping("/orders")
    public ResponseEntity<Page<LightingOrderResponse>> getAllOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Lighting Manager 주문 목록 조회: status={}, page={}, size={}", status, page, size);
        Page<LightingOrderResponse> orders = lightingManagerService.getAllOrders(status, PageRequest.of(page, size));
        return ResponseEntity.ok(orders);
    }

    /**
     * 주문 상세 조회
     * GET /api/lighting-manager/orders/{id}
     */
    @GetMapping("/orders/{id}")
    public ResponseEntity<LightingOrderResponse> getOrder(@PathVariable Long id) {
        log.info("Lighting Manager 주문 상세 조회: orderSeq={}", id);
        LightingOrderResponse response = lightingManagerService.getOrder(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 제안
     * POST /api/lighting-manager/orders/{id}/propose-quote
     */
    @PostMapping("/orders/{id}/propose-quote")
    public ResponseEntity<LightingOrderResponse> proposeQuote(
            @PathVariable Long id,
            @Valid @RequestBody ProposeQuoteRequest request) {
        log.info("Lighting Layout 견적 제안: orderSeq={}, amount={}", id, request.getQuoteAmount());
        LightingOrderResponse response = lightingManagerService.proposeQuote(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정
     * POST /api/lighting-manager/orders/{id}/assign
     */
    @PostMapping("/orders/{id}/assign")
    public ResponseEntity<LightingOrderResponse> assignManager(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        Long managerUserSeq = body.get("managerUserSeq");
        log.info("Lighting 매니저 배정: orderSeq={}, managerSeq={}", id, managerUserSeq);
        LightingOrderResponse response = lightingManagerService.assignManager(id, managerUserSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정 해제
     * DELETE /api/lighting-manager/orders/{id}/assign
     */
    @DeleteMapping("/orders/{id}/assign")
    public ResponseEntity<LightingOrderResponse> unassignManager(@PathVariable Long id) {
        log.info("Lighting 매니저 배정 해제: orderSeq={}", id);
        LightingOrderResponse response = lightingManagerService.unassignManager(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Lighting Layout 업로드 완료 (SLD_MANAGER가 상태 전환)
     * POST /api/lighting-manager/orders/{id}/sld-uploaded
     */
    @PostMapping("/orders/{id}/sld-uploaded")
    public ResponseEntity<LightingOrderResponse> uploadSld(
            @PathVariable Long id,
            @Valid @RequestBody LightingManagerUploadDto request) {
        log.info("Lighting Layout 업로드 완료: orderSeq={}, fileSeq={}", id, request.getFileSeq());
        LightingOrderResponse response = lightingManagerService.uploadSld(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 확인 (관리자 수동 확인)
     * POST /api/lighting-manager/orders/{id}/payment/confirm
     */
    @PostMapping("/orders/{id}/payment/confirm")
    public ResponseEntity<LightingOrderResponse> confirmPayment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String transactionId = body.get("transactionId");
        String paymentMethod = body.get("paymentMethod");
        log.info("Lighting Layout 결제 확인: orderSeq={}, transactionId={}", id, transactionId);
        LightingOrderResponse response = lightingManagerService.confirmPayment(id, transactionId, paymentMethod);
        return ResponseEntity.ok(response);
    }

    /**
     * 주문 완료 처리
     * POST /api/lighting-manager/orders/{id}/complete
     */
    @PostMapping("/orders/{id}/complete")
    public ResponseEntity<LightingOrderResponse> markComplete(@PathVariable Long id) {
        log.info("Lighting Layout 주문 완료 처리: orderSeq={}", id);
        LightingOrderResponse response = lightingManagerService.markComplete(id);
        return ResponseEntity.ok(response);
    }
}
