package com.bluelight.backend.api.expiredlicenseorder;

import com.bluelight.backend.api.expiredlicenseorder.dto.CheckOutRequest;
import com.bluelight.backend.api.expiredlicenseorder.dto.ExpiredLicenseOrderDashboardResponse;
import com.bluelight.backend.api.expiredlicenseorder.dto.ExpiredLicenseOrderResponse;
import com.bluelight.backend.api.expiredlicenseorder.dto.ProposeQuoteRequest;
import com.bluelight.backend.api.expiredlicenseorder.dto.ScheduleVisitRequest;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrder;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrderPayment;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrderPaymentRepository;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrderRepository;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrderStatus;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseVisitPhoto;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseVisitPhotoRepository;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
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
 * Expired License 주문 서비스 — Manager 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpiredLicenseManagerService {

    private final ExpiredLicenseOrderRepository expiredLicenseOrderRepository;
    private final ExpiredLicenseOrderPaymentRepository expiredLicenseOrderPaymentRepository;
    private final ExpiredLicenseVisitPhotoRepository expiredLicenseVisitPhotoRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final FileRepository fileRepository;

    public ExpiredLicenseOrderDashboardResponse getDashboard() {
        return ExpiredLicenseOrderDashboardResponse.builder()
                .total(expiredLicenseOrderRepository.count())
                .pendingQuote(expiredLicenseOrderRepository.countByStatus(ExpiredLicenseOrderStatus.PENDING_QUOTE))
                .quoteProposed(expiredLicenseOrderRepository.countByStatus(ExpiredLicenseOrderStatus.QUOTE_PROPOSED))
                .pendingPayment(expiredLicenseOrderRepository.countByStatus(ExpiredLicenseOrderStatus.PENDING_PAYMENT))
                .paid(expiredLicenseOrderRepository.countByStatus(ExpiredLicenseOrderStatus.PAID))
                .visitScheduled(expiredLicenseOrderRepository.countByStatus(ExpiredLicenseOrderStatus.VISIT_SCHEDULED))
                .visitCompleted(expiredLicenseOrderRepository.countByStatus(ExpiredLicenseOrderStatus.VISIT_COMPLETED))
                .revisitRequested(expiredLicenseOrderRepository.countByStatus(ExpiredLicenseOrderStatus.REVISIT_REQUESTED))
                .completed(expiredLicenseOrderRepository.countByStatus(ExpiredLicenseOrderStatus.COMPLETED))
                .build();
    }

    public Page<ExpiredLicenseOrderResponse> getAllOrders(String status, Pageable pageable) {
        Page<ExpiredLicenseOrder> page;
        if (status == null || status.isBlank()) {
            page = expiredLicenseOrderRepository.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            ExpiredLicenseOrderStatus orderStatus = parseStatus(status);
            page = expiredLicenseOrderRepository.findByStatusOrderByCreatedAtDesc(orderStatus, pageable);
        }
        return page.map(this::buildResponse);
    }

    public ExpiredLicenseOrderResponse getOrder(Long orderSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse proposeQuote(Long orderSeq, ProposeQuoteRequest request) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        order.proposeQuote(request.getQuoteAmount(), request.getQuoteNote());
        log.info("Expired License 견적 제안: orderSeq={}, amount={}", orderSeq, request.getQuoteAmount());
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse assignManager(Long orderSeq, Long managerSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        User manager = userRepository.findById(managerSeq)
                .orElseThrow(() -> new BusinessException(
                        "Manager not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        UserRole role = manager.getRole();
        if (role != UserRole.SLD_MANAGER && role != UserRole.ADMIN && role != UserRole.SYSTEM_ADMIN) {
            throw new BusinessException(
                    "User is not a Manager", HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }

        order.assignManager(manager);
        log.info("Expired License 매니저 배정: orderSeq={}, managerSeq={}", orderSeq, managerSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse unassignManager(Long orderSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        order.unassignManager();
        log.info("Expired License 매니저 배정 해제: orderSeq={}", orderSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse checkIn(Long orderSeq, Long managerUserSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        validateManagerAccess(order, managerUserSeq);
        order.checkIn();
        log.info("Expired License 체크인: orderSeq={}, managerSeq={}", orderSeq, managerUserSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse checkOut(Long orderSeq, Long managerUserSeq, CheckOutRequest request) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        validateManagerAccess(order, managerUserSeq);
        order.checkOut(request.getVisitReportFileSeq(), request.getManagerNote());
        log.info("Expired License 체크아웃: orderSeq={}, managerSeq={}, reportFileSeq={}",
                orderSeq, managerUserSeq, request.getVisitReportFileSeq());
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse uploadVisitPhotos(Long orderSeq,
                                                        Long managerUserSeq,
                                                        List<MultipartFile> files,
                                                        List<String> captions) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
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

        String subDirectory = "expired-license-orders/" + order.getExpiredLicenseOrderSeq() + "/visit-photos";
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > 10 * 1024 * 1024) {
                throw new BusinessException(
                        "Each photo must not exceed 10MB", HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE");
            }
            String storedPath = fileStorageService.store(file, subDirectory);
            FileEntity fileEntity = FileEntity.builder()
                    .expiredLicenseOrder(order)
                    .fileType(FileType.EXPIRED_LICENSE_VISIT_PHOTO)
                    .fileUrl(storedPath)
                    .originalFilename(file.getOriginalFilename())
                    .fileSize(file.getSize())
                    .build();
            FileEntity saved = fileRepository.save(fileEntity);
            String caption = captions != null && i < captions.size() ? captions.get(i) : null;
            ExpiredLicenseVisitPhoto photo = ExpiredLicenseVisitPhoto.builder()
                    .order(order)
                    .fileSeq(saved.getFileSeq())
                    .caption(caption)
                    .build();
            expiredLicenseVisitPhotoRepository.save(photo);
        }

        log.info("Expired License 방문 사진 업로드: orderSeq={}, count={}", orderSeq, files.size());
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse confirmPayment(Long orderSeq, String transactionId, String paymentMethod) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);

        if (order.getStatus() != ExpiredLicenseOrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(
                    "Payment confirmation is only available in PENDING_PAYMENT status. Current: " + order.getStatus(),
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }

        ExpiredLicenseOrderPayment payment = ExpiredLicenseOrderPayment.builder()
                .expiredLicenseOrder(order)
                .amount(order.getQuoteAmount())
                .paymentMethod(paymentMethod)
                .transactionId(transactionId)
                .build();
        expiredLicenseOrderPaymentRepository.save(payment);

        order.markAsPaid();
        log.info("Expired License 결제 확인: orderSeq={}, amount={}, transactionId={}",
                orderSeq, order.getQuoteAmount(), transactionId);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse markComplete(Long orderSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        order.complete();
        log.info("Expired License 주문 완료 처리: orderSeq={}", orderSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse scheduleVisit(Long orderSeq, Long managerUserSeq, ScheduleVisitRequest request) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        validateManagerAccess(order, managerUserSeq);
        order.ensureVisitScheduled();
        order.scheduleVisit(request.getVisitScheduledAt(), request.getVisitScheduleNote());
        log.info("Expired License 방문 일정 예약: orderSeq={}, managerSeq={}, visitAt={}",
                orderSeq, managerUserSeq, request.getVisitScheduledAt());
        return buildResponse(order);
    }

    private void validateManagerAccess(ExpiredLicenseOrder order, Long callerUserSeq) {
        User assigned = order.getAssignedManager();
        if (assigned == null) {
            return;
        }
        if (assigned.getUserSeq().equals(callerUserSeq)) {
            return;
        }
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
                "EXPIRED_LICENSE_NOT_ASSIGNED_MANAGER");
    }

    private ExpiredLicenseOrder findOrderOrThrow(Long orderSeq) {
        return expiredLicenseOrderRepository.findById(orderSeq)
                .orElseThrow(() -> new BusinessException(
                        "Expired License order not found", HttpStatus.NOT_FOUND, "EXPIRED_LICENSE_ORDER_NOT_FOUND"));
    }

    private ExpiredLicenseOrderResponse buildResponse(ExpiredLicenseOrder order) {
        return ExpiredLicenseOrderResponse.from(order, loadPhotos(order), loadSupportingDocuments(order));
    }

    private List<ExpiredLicenseVisitPhoto> loadPhotos(ExpiredLicenseOrder order) {
        if (order.getExpiredLicenseOrderSeq() == null) return new ArrayList<>();
        return expiredLicenseVisitPhotoRepository
                .findByOrderExpiredLicenseOrderSeqOrderByUploadedAtAsc(order.getExpiredLicenseOrderSeq());
    }

    private List<FileEntity> loadSupportingDocuments(ExpiredLicenseOrder order) {
        if (order.getExpiredLicenseOrderSeq() == null) return new ArrayList<>();
        return fileRepository.findByExpiredLicenseOrderExpiredLicenseOrderSeqAndFileType(
                order.getExpiredLicenseOrderSeq(), FileType.EXPIRED_LICENSE_SUPPORTING_DOC);
    }

    private ExpiredLicenseOrderStatus parseStatus(String status) {
        try {
            return ExpiredLicenseOrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    "Invalid order status: " + status, HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }
}
