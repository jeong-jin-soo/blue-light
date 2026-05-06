package com.bluelight.backend.api.expiredlicenseorder;

import com.bluelight.backend.api.expiredlicenseorder.dto.CreateExpiredLicenseOrderRequest;
import com.bluelight.backend.api.expiredlicenseorder.dto.ExpiredLicenseOrderPaymentResponse;
import com.bluelight.backend.api.expiredlicenseorder.dto.ExpiredLicenseOrderResponse;
import com.bluelight.backend.api.expiredlicenseorder.dto.UpdateExpiredLicenseOrderRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrder;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrderPaymentRepository;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrderRepository;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseVisitPhoto;
import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseVisitPhotoRepository;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Expired License 주문 서비스 — 신청자(Applicant) 측 비즈니스 로직
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpiredLicenseOrderService {

    private final ExpiredLicenseOrderRepository expiredLicenseOrderRepository;
    private final ExpiredLicenseOrderPaymentRepository expiredLicenseOrderPaymentRepository;
    private final ExpiredLicenseVisitPhotoRepository expiredLicenseVisitPhotoRepository;
    private final FileRepository fileRepository;
    private final UserRepository userRepository;

    @Transactional
    public ExpiredLicenseOrderResponse createOrder(Long userSeq, CreateExpiredLicenseOrderRequest request) {
        User user = userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        ExpiredLicenseOrder order = ExpiredLicenseOrder.builder()
                .user(user)
                .address(request.getAddress())
                .postalCode(request.getPostalCode())
                .buildingType(request.getBuildingType())
                .selectedKva(request.getSelectedKva())
                .applicantNote(request.getApplicantNote())
                .build();

        List<User> managers = userRepository.findByRoleAndApprovedStatus(UserRole.SLD_MANAGER, ApprovalStatus.APPROVED);
        if (managers.size() == 1) {
            order.assignManager(managers.get(0));
            log.info("Expired License 주문 자동 배정: orderUser={}, manager={}", userSeq, managers.get(0).getUserSeq());
        }

        expiredLicenseOrderRepository.save(order);
        log.info("Expired License 주문 생성: orderSeq={}, userSeq={}", order.getExpiredLicenseOrderSeq(), userSeq);
        return buildResponse(order);
    }

    public List<ExpiredLicenseOrderResponse> getMyOrders(Long userSeq) {
        return expiredLicenseOrderRepository.findByUserUserSeqOrderByCreatedAtDesc(userSeq)
                .stream()
                .map(this::buildResponse)
                .toList();
    }

    public ExpiredLicenseOrderResponse getOrder(Long orderSeq, Long userSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse updateOrder(Long orderSeq, Long userSeq, UpdateExpiredLicenseOrderRequest request) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.updateApplicantNote(request.getApplicantNote());
        log.info("Expired License 주문 수정: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse acceptQuote(Long orderSeq, Long userSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.acceptQuote();
        log.info("Expired License 견적 수락: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse rejectQuote(Long orderSeq, Long userSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.rejectQuote();
        log.info("Expired License 견적 거절: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse requestRevisit(Long orderSeq, Long userSeq, String comment) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.requestRevisit(comment);
        log.info("Expired License 재방문 요청: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return buildResponse(order);
    }

    @Transactional
    public ExpiredLicenseOrderResponse confirmCompletion(Long orderSeq, Long userSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        order.complete();
        log.info("Expired License 주문 완료 확인: orderSeq={}, userSeq={}", orderSeq, userSeq);
        return buildResponse(order);
    }

    public List<ExpiredLicenseOrderPaymentResponse> getPayments(Long orderSeq, Long userSeq) {
        ExpiredLicenseOrder order = findOrderOrThrow(orderSeq);
        OwnershipValidator.validateOwner(order.getUser().getUserSeq(), userSeq);
        return expiredLicenseOrderPaymentRepository.findByExpiredLicenseOrderExpiredLicenseOrderSeq(orderSeq)
                .stream()
                .map(ExpiredLicenseOrderPaymentResponse::from)
                .toList();
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
}
