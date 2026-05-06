package com.bluelight.backend.api.lewserviceorder;

import com.bluelight.backend.api.lewserviceorder.dto.CheckOutRequest;
import com.bluelight.backend.api.lewserviceorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceManagerUploadDto;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderDashboardResponse;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderResponse;
import com.bluelight.backend.api.lewserviceorder.dto.ScheduleVisitRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
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

    private final LewServiceManagerService lewServiceManagerService;

    /**
     * 대시보드 통계 조회
     * GET /api/lew-service-manager/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<LewServiceOrderDashboardResponse> getDashboard() {
        log.info("LewService Manager 대시보드 조회");
        LewServiceOrderDashboardResponse dashboard = lewServiceManagerService.getDashboard();
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
        Page<LewServiceOrderResponse> orders = lewServiceManagerService.getAllOrders(status, PageRequest.of(page, size));
        return ResponseEntity.ok(orders);
    }

    /**
     * 주문 상세 조회
     * GET /api/lew-service-manager/orders/{id}
     */
    @GetMapping("/orders/{id}")
    public ResponseEntity<LewServiceOrderResponse> getOrder(@PathVariable Long id) {
        log.info("LewService Manager 주문 상세 조회: orderSeq={}", id);
        LewServiceOrderResponse response = lewServiceManagerService.getOrder(id);
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
        LewServiceOrderResponse response = lewServiceManagerService.proposeQuote(id, request);
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
        LewServiceOrderResponse response = lewServiceManagerService.assignManager(id, managerUserSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 담당 매니저 배정 해제
     * DELETE /api/lew-service-manager/orders/{id}/assign
     */
    @DeleteMapping("/orders/{id}/assign")
    public ResponseEntity<LewServiceOrderResponse> unassignManager(@PathVariable Long id) {
        log.info("LewService 매니저 배정 해제: orderSeq={}", id);
        LewServiceOrderResponse response = lewServiceManagerService.unassignManager(id);
        return ResponseEntity.ok(response);
    }

    /**
     * @deprecated PR 3 — 구 도면 업로드 엔드포인트. 하위호환용으로 1 개월간 유지.
     *   신규 구현은 {@link #checkIn}/{@link #checkOut} 조합 사용.
     *   <p>내부에서 {@link LewServiceManagerService#uploadSld} 는 legacy 어댑터로 위임.
     * <p>POST /api/lew-service-manager/orders/{id}/sld-uploaded
     */
    @Deprecated
    @PostMapping("/orders/{id}/sld-uploaded")
    public ResponseEntity<LewServiceOrderResponse> uploadSld(
            @PathVariable Long id,
            @Valid @RequestBody LewServiceManagerUploadDto request) {
        log.warn("LEW Service legacy /sld-uploaded 호출 (PR 3 에서 deprecate): orderSeq={}, fileSeq={}",
                id, request.getFileSeq());
        LewServiceOrderResponse response = lewServiceManagerService.uploadSld(id, request);
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
        LewServiceOrderResponse response = lewServiceManagerService.confirmPayment(id, transactionId, paymentMethod);
        return ResponseEntity.ok(response);
    }

    /**
     * 주문 완료 처리
     * POST /api/lew-service-manager/orders/{id}/complete
     */
    @PostMapping("/orders/{id}/complete")
    public ResponseEntity<LewServiceOrderResponse> markComplete(@PathVariable Long id) {
        log.info("Request for LEW Service 주문 완료 처리: orderSeq={}", id);
        LewServiceOrderResponse response = lewServiceManagerService.markComplete(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 방문 일정 예약 / 재예약 (LEW Service 방문형 리스키닝 PR 2)
     * POST /api/lew-service-manager/orders/{id}/schedule-visit
     * <p>
     * 상태 전이 없음 — visitScheduledAt / visitScheduleNote 데이터만 세팅.
     * PAID / IN_PROGRESS / REVISION_REQUESTED 상태에서만 허용.
     */
    @PostMapping("/orders/{id}/schedule-visit")
    public ResponseEntity<LewServiceOrderResponse> scheduleVisit(
            @PathVariable Long id,
            @Valid @RequestBody ScheduleVisitRequest request,
            Authentication authentication) {
        Long managerUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW Service 방문 일정 예약: orderSeq={}, managerSeq={}, visitAt={}",
                id, managerUserSeq, request.getVisitScheduledAt());
        LewServiceOrderResponse response = lewServiceManagerService.scheduleVisit(id, managerUserSeq, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 체크인 (현장 도착) — PR 3.
     * <p>VISIT_SCHEDULED 상태에서 호출. 상태 전이 없이 {@code checkInAt} 만 기록.
     * POST /api/lew-service-manager/orders/{id}/check-in
     */
    @PostMapping("/orders/{id}/check-in")
    public ResponseEntity<LewServiceOrderResponse> checkIn(
            @PathVariable Long id,
            Authentication authentication) {
        Long managerUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW Service 체크인 요청: orderSeq={}, managerSeq={}", id, managerUserSeq);
        LewServiceOrderResponse response = lewServiceManagerService.checkIn(id, managerUserSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 체크아웃 + 방문 보고서 제출 — PR 3.
     * <p>VISIT_SCHEDULED 에서 {@code checkInAt} 이 있어야 호출 가능. VISIT_COMPLETED 로 전이.
     * POST /api/lew-service-manager/orders/{id}/check-out
     */
    @PostMapping("/orders/{id}/check-out")
    public ResponseEntity<LewServiceOrderResponse> checkOut(
            @PathVariable Long id,
            @Valid @RequestBody CheckOutRequest request,
            Authentication authentication) {
        Long managerUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW Service 체크아웃 요청: orderSeq={}, managerSeq={}, reportFileSeq={}",
                id, managerUserSeq, request.getVisitReportFileSeq());
        LewServiceOrderResponse response = lewServiceManagerService.checkOut(id, managerUserSeq, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 방문 사진 업로드 (여러 장) — PR 3.
     * <p>POST /api/lew-service-manager/orders/{id}/visit-photos
     * <p>multipart/form-data: files[], captions[] (각 파일에 대응, optional)
     */
    @PostMapping("/orders/{id}/visit-photos")
    public ResponseEntity<LewServiceOrderResponse> uploadVisitPhotos(
            @PathVariable Long id,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "captions", required = false) List<String> captions,
            Authentication authentication) {
        Long managerUserSeq = (Long) authentication.getPrincipal();
        log.info("LEW Service 방문 사진 업로드: orderSeq={}, managerSeq={}, count={}",
                id, managerUserSeq, files == null ? 0 : files.size());
        LewServiceOrderResponse response = lewServiceManagerService.uploadVisitPhotos(id, managerUserSeq, files, captions);
        return ResponseEntity.ok(response);
    }
}
