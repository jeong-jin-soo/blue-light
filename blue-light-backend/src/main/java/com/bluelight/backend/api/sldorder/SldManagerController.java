package com.bluelight.backend.api.sldorder;

import com.bluelight.backend.api.sldorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.sldorder.dto.SldManagerUploadDto;
import com.bluelight.backend.api.sldorder.dto.SldOrderDashboardResponse;
import com.bluelight.backend.api.sldorder.dto.SldOrderResponse;
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
 * SLD Manager API 컨트롤러
 * - SLD_MANAGER / ADMIN / SYSTEM_ADMIN이 SLD 주문을 관리
 */
@Slf4j
@RestController
@RequestMapping("/api/sld-manager")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
public class SldManagerController {

    private final SldManagerService sldManagerService;

    /**
     * 대시보드 통계 조회
     * GET /api/sld-manager/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<SldOrderDashboardResponse> getDashboard() {
        log.info("SLD Manager 대시보드 조회");
        SldOrderDashboardResponse dashboard = sldManagerService.getDashboard();
        return ResponseEntity.ok(dashboard);
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     * GET /api/sld-manager/orders?status=xxx&page=0&size=20
     */
    @GetMapping("/orders")
    public ResponseEntity<Page<SldOrderResponse>> getAllOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("SLD Manager 주문 목록 조회: status={}, page={}, size={}", status, page, size);
        Page<SldOrderResponse> orders = sldManagerService.getAllOrders(status, PageRequest.of(page, size));
        return ResponseEntity.ok(orders);
    }

    /**
     * 주문 상세 조회
     * GET /api/sld-manager/orders/{id}
     */
    @GetMapping("/orders/{id}")
    public ResponseEntity<SldOrderResponse> getOrder(@PathVariable Long id) {
        log.info("SLD Manager 주문 상세 조회: orderSeq={}", id);
        SldOrderResponse response = sldManagerService.getOrder(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 제안
     * POST /api/sld-manager/orders/{id}/propose-quote
     */
    @PostMapping("/orders/{id}/propose-quote")
    public ResponseEntity<SldOrderResponse> proposeQuote(
            @PathVariable Long id,
            @Valid @RequestBody ProposeQuoteRequest request) {
        log.info("SLD 견적 제안: orderSeq={}, amount={}", id, request.getQuoteAmount());
        SldOrderResponse response = sldManagerService.proposeQuote(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정
     * POST /api/sld-manager/orders/{id}/assign
     */
    @PostMapping("/orders/{id}/assign")
    public ResponseEntity<SldOrderResponse> assignManager(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        Long managerUserSeq = body.get("managerUserSeq");
        log.info("SLD 매니저 배정: orderSeq={}, managerSeq={}", id, managerUserSeq);
        SldOrderResponse response = sldManagerService.assignManager(id, managerUserSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정 해제
     * DELETE /api/sld-manager/orders/{id}/assign
     */
    @DeleteMapping("/orders/{id}/assign")
    public ResponseEntity<SldOrderResponse> unassignManager(@PathVariable Long id) {
        log.info("SLD 매니저 배정 해제: orderSeq={}", id);
        SldOrderResponse response = sldManagerService.unassignManager(id);
        return ResponseEntity.ok(response);
    }

    /**
     * SLD 업로드 완료 (SLD_MANAGER가 상태 전환)
     * POST /api/sld-manager/orders/{id}/sld-uploaded
     */
    @PostMapping("/orders/{id}/sld-uploaded")
    public ResponseEntity<SldOrderResponse> uploadSld(
            @PathVariable Long id,
            @Valid @RequestBody SldManagerUploadDto request) {
        log.info("SLD 업로드 완료: orderSeq={}, fileSeq={}", id, request.getFileSeq());
        SldOrderResponse response = sldManagerService.uploadSld(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 확인 (관리자 수동 확인)
     * POST /api/sld-manager/orders/{id}/payment/confirm
     */
    @PostMapping("/orders/{id}/payment/confirm")
    public ResponseEntity<SldOrderResponse> confirmPayment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String transactionId = body.get("transactionId");
        String paymentMethod = body.get("paymentMethod");
        log.info("SLD 결제 확인: orderSeq={}, transactionId={}", id, transactionId);
        SldOrderResponse response = sldManagerService.confirmPayment(id, transactionId, paymentMethod);
        return ResponseEntity.ok(response);
    }

    /**
     * 주문 완료 처리
     * POST /api/sld-manager/orders/{id}/complete
     */
    @PostMapping("/orders/{id}/complete")
    public ResponseEntity<SldOrderResponse> markComplete(@PathVariable Long id) {
        log.info("SLD 주문 완료 처리: orderSeq={}", id);
        SldOrderResponse response = sldManagerService.markComplete(id);
        return ResponseEntity.ok(response);
    }
}
