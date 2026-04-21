package com.bluelight.backend.api.lewserviceorder;

import com.bluelight.backend.api.lewserviceorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceManagerUploadDto;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderDashboardResponse;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderResponse;
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
 * LewService Manager API 컨트롤러
 * - SLD_MANAGER / ADMIN / SYSTEM_ADMIN이 Request for LEW Service 주문을 관리
 */
@Slf4j
@RestController
@RequestMapping("/api/lew-service-manager")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SLD_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
public class LewServiceManagerController {

    private final LewServiceManagerService sldManagerService;

    /**
     * 대시보드 통계 조회
     * GET /api/lew-service-manager/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<LewServiceOrderDashboardResponse> getDashboard() {
        log.info("LewService Manager 대시보드 조회");
        LewServiceOrderDashboardResponse dashboard = sldManagerService.getDashboard();
        return ResponseEntity.ok(dashboard);
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     * GET /api/lew-service-manager/orders?status=xxx&page=0&size=20
     */
    @GetMapping("/orders")
    public ResponseEntity<Page<LewServiceOrderResponse>> getAllOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("LewService Manager 주문 목록 조회: status={}, page={}, size={}", status, page, size);
        Page<LewServiceOrderResponse> orders = sldManagerService.getAllOrders(status, PageRequest.of(page, size));
        return ResponseEntity.ok(orders);
    }

    /**
     * 주문 상세 조회
     * GET /api/lew-service-manager/orders/{id}
     */
    @GetMapping("/orders/{id}")
    public ResponseEntity<LewServiceOrderResponse> getOrder(@PathVariable Long id) {
        log.info("LewService Manager 주문 상세 조회: orderSeq={}", id);
        LewServiceOrderResponse response = sldManagerService.getOrder(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 견적 제안
     * POST /api/lew-service-manager/orders/{id}/propose-quote
     */
    @PostMapping("/orders/{id}/propose-quote")
    public ResponseEntity<LewServiceOrderResponse> proposeQuote(
            @PathVariable Long id,
            @Valid @RequestBody ProposeQuoteRequest request) {
        log.info("Request for LEW Service 견적 제안: orderSeq={}, amount={}", id, request.getQuoteAmount());
        LewServiceOrderResponse response = sldManagerService.proposeQuote(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정
     * POST /api/lew-service-manager/orders/{id}/assign
     */
    @PostMapping("/orders/{id}/assign")
    public ResponseEntity<LewServiceOrderResponse> assignManager(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        Long managerUserSeq = body.get("managerUserSeq");
        log.info("LewService 매니저 배정: orderSeq={}, managerSeq={}", id, managerUserSeq);
        LewServiceOrderResponse response = sldManagerService.assignManager(id, managerUserSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정 해제
     * DELETE /api/lew-service-manager/orders/{id}/assign
     */
    @DeleteMapping("/orders/{id}/assign")
    public ResponseEntity<LewServiceOrderResponse> unassignManager(@PathVariable Long id) {
        log.info("LewService 매니저 배정 해제: orderSeq={}", id);
        LewServiceOrderResponse response = sldManagerService.unassignManager(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Request for LEW Service 업로드 완료 (SLD_MANAGER가 상태 전환)
     * POST /api/lew-service-manager/orders/{id}/sld-uploaded
     */
    @PostMapping("/orders/{id}/sld-uploaded")
    public ResponseEntity<LewServiceOrderResponse> uploadSld(
            @PathVariable Long id,
            @Valid @RequestBody LewServiceManagerUploadDto request) {
        log.info("Request for LEW Service 업로드 완료: orderSeq={}, fileSeq={}", id, request.getFileSeq());
        LewServiceOrderResponse response = sldManagerService.uploadSld(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 결제 확인 (관리자 수동 확인)
     * POST /api/lew-service-manager/orders/{id}/payment/confirm
     */
    @PostMapping("/orders/{id}/payment/confirm")
    public ResponseEntity<LewServiceOrderResponse> confirmPayment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String transactionId = body.get("transactionId");
        String paymentMethod = body.get("paymentMethod");
        log.info("Request for LEW Service 결제 확인: orderSeq={}, transactionId={}", id, transactionId);
        LewServiceOrderResponse response = sldManagerService.confirmPayment(id, transactionId, paymentMethod);
        return ResponseEntity.ok(response);
    }

    /**
     * 주문 완료 처리
     * POST /api/lew-service-manager/orders/{id}/complete
     */
    @PostMapping("/orders/{id}/complete")
    public ResponseEntity<LewServiceOrderResponse> markComplete(@PathVariable Long id) {
        log.info("Request for LEW Service 주문 완료 처리: orderSeq={}", id);
        LewServiceOrderResponse response = sldManagerService.markComplete(id);
        return ResponseEntity.ok(response);
    }
}
