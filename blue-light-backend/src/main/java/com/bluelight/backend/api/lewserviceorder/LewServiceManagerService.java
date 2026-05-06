package com.bluelight.backend.api.lewserviceorder;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.api.lewserviceorder.dto.CheckOutRequest;
import com.bluelight.backend.api.lewserviceorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceManagerUploadDto;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderDashboardResponse;
import com.bluelight.backend.api.lewserviceorder.dto.LewServiceOrderResponse;
import com.bluelight.backend.api.lewserviceorder.dto.ScheduleVisitRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrder;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderPayment;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderPaymentRepository;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderRepository;
import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderStatus;
import com.bluelight.backend.domain.lewserviceorder.LewServiceVisitPhoto;
import com.bluelight.backend.domain.lewserviceorder.LewServiceVisitPhotoRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Request for LEW Service 주문 서비스 — SLD_MANAGER 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LewServiceManagerService {

    private final LewServiceOrderRepository lewServiceOrderRepository;
    private final LewServiceOrderPaymentRepository lewServiceOrderPaymentRepository;
    private final LewServiceVisitPhotoRepository lewServiceVisitPhotoRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final FileRepository fileRepository;

    /**
     * 대시보드 통계 조회 (상태별 건수)
     */
    public LewServiceOrderDashboardResponse getDashboard() {
        long visitScheduled = lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.VISIT_SCHEDULED);
        long visitCompleted = lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.VISIT_COMPLETED);
        long revisitRequested = lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.REVISIT_REQUESTED);
        return LewServiceOrderDashboardResponse.builder()
                .total(lewServiceOrderRepository.count())
                .pendingQuote(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.PENDING_QUOTE))
                .quoteProposed(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.QUOTE_PROPOSED))
                .pendingPayment(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.PENDING_PAYMENT))
                .paid(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.PAID))
                .visitScheduled(visitScheduled)
                .visitCompleted(visitCompleted)
                .revisitRequested(revisitRequested)
                .completed(lewServiceOrderRepository.countByStatus(LewServiceOrderStatus.COMPLETED))
                // 하위호환 alias
                .inProgress(visitScheduled)
                .deliverableUploaded(visitCompleted)
                .build();
    }

    /**
     * 전체 주문 목록 (상태 필터 + 페이지네이션)
     */
    public Page<LewServiceOrderResponse> getAllOrders(String status, Pageable pageable) {
        Page<LewServiceOrder> page;
        if (status == null || status.isBlank()) {
            page = lewServiceOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            LewServiceOrderStatus orderStatus = parseStatus(status);
            page = lewServiceOrderRepository.findByStatusOrderByCreatedAtDesc(orderStatus, pageable);
        }
        return page.map(order -> LewServiceOrderResponse.from(order, loadPhotos(order)));
    }

    /**
     * 주문 상세 조회
     */
    public LewServiceOrderResponse getOrder(Long orderSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 견적 제안
     */
    @Transactional
    public LewServiceOrderResponse proposeQuote(Long orderSeq, ProposeQuoteRequest request) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        order.proposeQuote(request.getQuoteAmount(), request.getQuoteNote());
        log.info("Request for LEW Service 견적 제안: orderSeq={}, amount={}", orderSeq, request.getQuoteAmount());
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 담당 매니저 배정
     */
    @Transactional
    public LewServiceOrderResponse assignManager(Long orderSeq, Long managerSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        User manager = userRepository.findById(managerSeq)
                .orElseThrow(() -> new BusinessException(
                        "Manager not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        UserRole role = manager.getRole();
        if (role != UserRole.SLD_MANAGER && role != UserRole.ADMIN && role != UserRole.SYSTEM_ADMIN) {
            throw new BusinessException(
                    "User is not an LewService Manager", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }

        order.assignManager(manager);
        log.info("LewService 매니저 배정: orderSeq={}, managerSeq={}", orderSeq, managerSeq);
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 담당 매니저 배정 해제
     */
    @Transactional
    public LewServiceOrderResponse unassignManager(Long orderSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        order.unassignManager();
        log.info("LewService 매니저 배정 해제: orderSeq={}", orderSeq);
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * @deprecated PR 3 — 하위호환 어댑터. 내부에서 {@link LewServiceOrder#legacyUploadDeliverable} 로
     *   위임하여 VISIT_COMPLETED 로 전이시킨다. 신규 코드는 {@link #checkOut}/{@link #checkIn} 사용.
     */
    @Deprecated
    @Transactional
    public LewServiceOrderResponse uploadSld(Long orderSeq, LewServiceManagerUploadDto request) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        order.legacyUploadDeliverable(request.getFileSeq(), request.getManagerNote());
        log.info("LEW Service 하위호환 업로드 (legacy /sld-uploaded): orderSeq={}, fileSeq={}",
                orderSeq, request.getFileSeq());
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 체크인 — PR 3. VISIT_SCHEDULED 상태에서 호출.
     */
    @Transactional
    public LewServiceOrderResponse checkIn(Long orderSeq, Long managerUserSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        validateManagerAccess(order, managerUserSeq);
        order.checkIn();
        log.info("LEW Service 체크인: orderSeq={}, managerSeq={}, at={}",
                orderSeq, managerUserSeq, order.getCheckInAt());
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 체크아웃 + 방문 보고서 제출 — PR 3.
     */
    @Transactional
    public LewServiceOrderResponse checkOut(Long orderSeq, Long managerUserSeq, CheckOutRequest request) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        validateManagerAccess(order, managerUserSeq);
        order.checkOut(request.getVisitReportFileSeq(), request.getManagerNote());
        log.info("LEW Service 체크아웃 + 보고서 제출: orderSeq={}, managerSeq={}, reportFileSeq={}",
                orderSeq, managerUserSeq, request.getVisitReportFileSeq());
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 방문 사진 업로드 (여러 장) — PR 3.
     *
     * @param orderSeq        주문 seq
     * @param managerUserSeq  호출자 (배정 매니저 또는 ADMIN/SYSTEM_ADMIN)
     * @param files           업로드할 파일들 (최대 10장)
     * @param captions        각 파일에 대응하는 caption (nullable, 길이가 files 와 달라도 null 로 패딩)
     */
    @Transactional
    public LewServiceOrderResponse uploadVisitPhotos(Long orderSeq,
                                                    Long managerUserSeq,
                                                    List<MultipartFile> files,
                                                    List<String> captions) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        validateManagerAccess(order, managerUserSeq);

        if (files == null || files.isEmpty()) {
            throw new BusinessException(
                    "At least one photo is required", HttpStatus.BAD_REQUEST, "FILE_REQUIRED");
        }
        if (files.size() > 10) {
            throw new BusinessException(
                    "A maximum of 10 photos can be uploaded at once",
                    HttpStatus.BAD_REQUEST, "TOO_MANY_FILES");
        }

        String subDirectory = "lew-service-orders/" + order.getLewServiceOrderSeq() + "/visit-photos";
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > 10 * 1024 * 1024) {
                throw new BusinessException(
                        "Each photo must not exceed 10MB", HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE");
            }
            String storedPath = fileStorageService.store(file, subDirectory);
            FileEntity fileEntity = FileEntity.builder()
                    .lewServiceOrder(order)
                    .fileType(FileType.LEW_SERVICE_VISIT_PHOTO)
                    .fileUrl(storedPath)
                    .originalFilename(file.getOriginalFilename())
                    .fileSize(file.getSize())
                    .build();
            FileEntity saved = fileRepository.save(fileEntity);
            String caption = captions != null && i < captions.size() ? captions.get(i) : null;
            LewServiceVisitPhoto photo = LewServiceVisitPhoto.builder()
                    .order(order)
                    .fileSeq(saved.getFileSeq())
                    .caption(caption)
                    .build();
            lewServiceVisitPhotoRepository.save(photo);
        }

        log.info("LEW Service 방문 사진 업로드: orderSeq={}, count={}", orderSeq, files.size());
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 결제 확인 (관리자가 수동 확인)
     */
    @Transactional
    public LewServiceOrderResponse confirmPayment(Long orderSeq, String transactionId, String paymentMethod) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);

        if (order.getStatus() != LewServiceOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    "Payment confirmation is only available in PENDING_PAYMENT status. Current: " + order.getStatus(),
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }

        LewServiceOrderPayment payment = LewServiceOrderPayment.builder()
                .lewServiceOrder(order)
                .amount(order.getQuoteAmount())
                .paymentMethod(paymentMethod)
                .transactionId(transactionId)
                .build();
        lewServiceOrderPaymentRepository.save(payment);

        order.markAsPaid();
        log.info("Request for LEW Service 결제 확인: orderSeq={}, amount={}, transactionId={}",
                orderSeq, order.getQuoteAmount(), transactionId);
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 주문 완료 처리 (관리자)
     */
    @Transactional
    public LewServiceOrderResponse markComplete(Long orderSeq) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        order.complete();
        log.info("Request for LEW Service 주문 완료 처리: orderSeq={}", orderSeq);
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * 방문 일정 예약 / 재예약 (LEW Service 방문형 리스키닝 PR 2)
     * <p>
     * 상태 전이는 유발하지 않음 — visitScheduledAt / visitScheduleNote 데이터만 세팅.
     * Access: 배정된 매니저가 있는 경우 본인 또는 ADMIN/SYSTEM_ADMIN 만 호출 가능.
     * 배정 매니저가 없으면 (상위 @PreAuthorize 에서 이미 역할은 검증됨) 해당 역할 모두 허용.
     */
    @Transactional
    public LewServiceOrderResponse scheduleVisit(Long orderSeq, Long managerUserSeq, ScheduleVisitRequest request) {
        LewServiceOrder order = findOrderOrThrow(orderSeq);
        validateManagerAccess(order, managerUserSeq);
        // PAID → VISIT_SCHEDULED 자동 전이 (일정이 확정되면 방문 단계로 이동)
        order.ensureVisitScheduled();
        order.scheduleVisit(request.getVisitScheduledAt(), request.getVisitScheduleNote());
        log.info("LEW Service 방문 일정 예약: orderSeq={}, managerSeq={}, visitAt={}",
                orderSeq, managerUserSeq, request.getVisitScheduledAt());
        return LewServiceOrderResponse.from(order, loadPhotos(order));
    }

    /**
     * Access 검증 — 배정된 매니저가 있으면 본인 혹은 ADMIN/SYSTEM_ADMIN 만 접근 허용.
     * (@PreAuthorize 에서 이미 SLD_MANAGER/ADMIN/SYSTEM_ADMIN 역할 검증됨)
     */
    private void validateManagerAccess(LewServiceOrder order, Long callerUserSeq) {
        User assigned = order.getAssignedManager();
        if (assigned == null) {
            return; // 미배정 상태에서는 역할 검증만으로 충분
        }
        if (assigned.getUserSeq().equals(callerUserSeq)) {
            return;
        }
        // 본인이 아니면 ADMIN / SYSTEM_ADMIN 은 우회 허용
        User caller = userRepository.findById(callerUserSeq)
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        UserRole role = caller.getRole();
        if (role == UserRole.ADMIN || role == UserRole.SYSTEM_ADMIN) {
            return;
        }
        throw new BusinessException(
                "Only the assigned manager or an administrator can perform this action",
                HttpStatus.FORBIDDEN,
                "LEW_SERVICE_NOT_ASSIGNED_MANAGER");
    }

    // ── 내부 유틸 ──────────────────────────────────────

    private LewServiceOrder findOrderOrThrow(Long orderSeq) {
        return lewServiceOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "Request for LEW Service order not found", HttpStatus.NOT_FOUND, "LEW_SERVICE_ORDER_NOT_FOUND"));
    }

    private List<LewServiceVisitPhoto> loadPhotos(LewServiceOrder order) {
        if (order.getLewServiceOrderSeq() == null) return new ArrayList<>();
        return lewServiceVisitPhotoRepository
                .findByOrderLewServiceOrderSeqOrderByUploadedAtAsc(order.getLewServiceOrderSeq());
    }

    private LewServiceOrderStatus parseStatus(String status) {
        try {
            return LewServiceOrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid order status: " + status, HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }
}
