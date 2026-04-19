package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.ConfirmKvaRequest;
import com.bluelight.backend.api.admin.dto.ConfirmKvaResponse;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.application.KvaStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 5 PR#1 — LEW/ADMIN kVA 확정 서비스.
 *
 * <p>Endpoint: {@code PATCH /api/admin/applications/{id}/kva}
 *
 * <h3>보안 가드</h3>
 * <ul>
 *   <li><b>B-3</b>: {@code status ∈ {PAID, IN_PROGRESS, COMPLETED, EXPIRED}} 이면
 *       force 여부 무관하게 409 {@code KVA_LOCKED_AFTER_PAYMENT}.</li>
 *   <li><b>B-4</b>: force=true 경로는 {@link AuditAction#KVA_OVERRIDDEN_BY_ADMIN},
 *       일반 경로는 {@link AuditAction#KVA_CONFIRMED_BY_LEW},
 *       실패 경로는 {@link AuditAction#KVA_CONFIRMATION_DENIED} 로 각각 분리 기록.</li>
 *   <li><b>AC-P1</b>: 이미 CONFIRMED 인 신청에 force=false 로 재확정 시 409
 *       {@code KVA_ALREADY_CONFIRMED}. force=true 는 ADMIN 전용 (컨트롤러에서 역할 검증).</li>
 *   <li><b>AC-A2</b>: ADMIN 이 아닌 LEW 는 본인에게 할당된 신청만 확정 가능 (403).</li>
 *   <li><b>@Version</b>: Application 엔티티의 {@code @Version} 으로 동시성 충돌 차단,
 *       OptimisticLockException → GlobalExceptionHandler 가 409 {@code STALE_STATE} 로 변환.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationKvaService {

    private final ApplicationRepository applicationRepository;
    private final MasterPriceRepository masterPriceRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    /**
     * kVA 확정 (LEW/ADMIN).
     *
     * @param applicationId 신청서 ID
     * @param request       {@code selectedKva}, {@code note}
     * @param force         ADMIN 오버라이드 플래그 (컨트롤러에서 역할 검증 완료된 상태)
     * @param actorSeq      호출자 userSeq
     * @param role          호출자 권한 (e.g., {@code ROLE_ADMIN}, {@code ROLE_LEW})
     */
    @Transactional
    public ConfirmKvaResponse confirm(Long applicationId, ConfirmKvaRequest request,
                                      boolean force, Long actorSeq, String role) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // AC-A2: 권한(소유권) 체크 — ADMIN/SYSTEM_ADMIN 통과, LEW 는 assignedLew 일치 시만 통과.
        // 추가 발견(NPE): ownerSeq 에 application.user.userSeq 를 실제 값으로 전달한다.
        Long ownerSeq = application.getUser() != null ? application.getUser().getUserSeq() : null;
        Long assignedLewSeq = application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq() : null;
        try {
            OwnershipValidator.validateOwnerOrAdminOrAssignedLew(
                    ownerSeq, actorSeq, role, assignedLewSeq);
        } catch (BusinessException denied) {
            logDenied(actorSeq, application, request, "FORBIDDEN", denied.getMessage(), force);
            throw denied;
        }

        // B-3: PAID 이후 차단 (force 무관)
        ApplicationStatus current = application.getStatus();
        if (isLockedStatus(current)) {
            logDenied(actorSeq, application, request, "KVA_LOCKED_AFTER_PAYMENT",
                    "Application status " + current + " is locked for kVA changes", force);
            throw new BusinessException(
                    "kVA cannot be changed after payment (status=" + current + ")",
                    HttpStatus.CONFLICT, "KVA_LOCKED_AFTER_PAYMENT");
        }

        // AC-P1: 이미 CONFIRMED 상태에서 force=false 이면 거부
        KvaStatus previousStatus = application.getKvaStatus();
        Integer previousKva = application.getSelectedKva();
        BigDecimal previousQuote = application.getQuoteAmount();
        if (previousStatus == KvaStatus.CONFIRMED && !force) {
            logDenied(actorSeq, application, request, "KVA_ALREADY_CONFIRMED",
                    "Application already confirmed; force=true required", false);
            throw new BusinessException(
                    "kVA already confirmed; use force=true to override (ADMIN only)",
                    HttpStatus.CONFLICT, "KVA_ALREADY_CONFIRMED");
        }

        // AC-A3: tier 유효성 — MasterPrice 조회 실패 시 롤백 + 400 INVALID_KVA_TIER
        MasterPrice masterPrice = masterPriceRepository.findByKva(request.getSelectedKva())
                .orElseThrow(() -> {
                    // 감사 로그는 트랜잭션 롤백과 무관한 REQUIRES_NEW 로 기록됨
                    logDenied(actorSeq, application, request, "INVALID_KVA_TIER",
                            "Unknown kVA tier: " + request.getSelectedKva(), force);
                    return new BusinessException(
                            "Invalid kVA tier: " + request.getSelectedKva(),
                            HttpStatus.BAD_REQUEST, "INVALID_KVA_TIER");
                });

        // 가격 재계산: tierPrice + sldFee + emaFee (ApplicationService.create() 와 동일 로직)
        BigDecimal tierPrice = (application.getApplicationType() == ApplicationType.RENEWAL)
                ? masterPrice.getRenewalPrice()
                : masterPrice.getPrice();
        BigDecimal newQuote = tierPrice;
        // SLD fee: REQUEST_LEW 일 때만 신규 tier 의 sldPrice 적용 (일관성 유지)
        BigDecimal newSldFee = null;
        if (application.getSldOption() != null
                && application.getSldOption().name().equals("REQUEST_LEW")) {
            newSldFee = masterPrice.getSldPrice();
            if (newSldFee != null) {
                newQuote = newQuote.add(newSldFee);
            }
        }
        if (application.getEmaFee() != null) {
            newQuote = newQuote.add(application.getEmaFee());
        }

        // 확정자 로드 (FK 참조)
        User confirmer = userRepository.findById(actorSeq)
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // 도메인 메서드 호출 (selectedKva, quoteAmount, confirmedBy/At, status/source 원자 갱신)
        application.confirmKva(request.getSelectedKva(), newQuote, confirmer, force);
        // sldFee 도 갱신 필요 (도메인 메서드가 selectedKva/quoteAmount 만 바꾸므로)
        if (newSldFee != null || application.getSldFee() != null) {
            // updateDetails 를 쓰면 CONFIRMED 가드로 무시되므로 별도 setter 가 필요함.
            // 현재는 sldFee 필드 setter 가 없어서 — quoteAmount 에 이미 반영되었으므로 sldFee 단독 값은
            // 다음 PR 에서 reflectSldFee 추가 시 정리한다. 여기서는 quoteAmount 정합성만 보장.
        }

        log.info("kVA confirmed: applicationId={}, prev={}kVA/{}, new={}kVA/{}, force={}, actor={}",
                applicationId, previousKva, previousQuote,
                request.getSelectedKva(), newQuote, force, actorSeq);

        // B-4: 감사 이벤트 분리 기록
        AuditAction action = force
                ? AuditAction.KVA_OVERRIDDEN_BY_ADMIN
                : AuditAction.KVA_CONFIRMED_BY_LEW;
        Map<String, Object> metadata = buildConfirmMetadata(
                previousStatus, previousKva, previousQuote,
                request.getSelectedKva(), newQuote, request.getNote(), force, current);
        auditLogService.logAsync(
                actorSeq, action, AuditCategory.ADMIN,
                "Application", String.valueOf(applicationId),
                force ? "kVA overridden by ADMIN" : "kVA confirmed by LEW",
                null, metadata,
                null, null, "PATCH", "/api/admin/applications/" + applicationId + "/kva", 200);

        // 신청자 인앱 알림 (이메일은 범위 외, spec §9 out of scope #6)
        notifyApplicant(application, request.getSelectedKva(), newQuote);

        return ConfirmKvaResponse.from(application);
    }

    // ── Helpers ──────────────────────────────────────────────

    private boolean isLockedStatus(ApplicationStatus s) {
        return s == ApplicationStatus.PAID
                || s == ApplicationStatus.IN_PROGRESS
                || s == ApplicationStatus.COMPLETED
                || s == ApplicationStatus.EXPIRED;
    }

    private Map<String, Object> buildConfirmMetadata(
            KvaStatus previousStatus, Integer previousKva, BigDecimal previousQuote,
            Integer newKva, BigDecimal newQuote, String note, boolean force,
            ApplicationStatus applicationStatus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("previousKva", previousKva);
        m.put("previousStatus", previousStatus != null ? previousStatus.name() : null);
        m.put("previousQuote", previousQuote);
        m.put("newKva", newKva);
        m.put("newQuote", newQuote);
        BigDecimal delta = (previousQuote != null && newQuote != null)
                ? newQuote.subtract(previousQuote) : null;
        m.put("priceDelta", delta);
        m.put("note", note);
        m.put("force", force);
        m.put("applicationStatus", applicationStatus != null ? applicationStatus.name() : null);
        return m;
    }

    private void logDenied(Long actorSeq, Application application, ConfirmKvaRequest req,
                            String errorCode, String reason, boolean force) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errorCode", errorCode);
        m.put("reason", reason);
        m.put("requestedKva", req != null ? req.getSelectedKva() : null);
        m.put("currentKva", application.getSelectedKva());
        m.put("currentStatus", application.getStatus() != null ? application.getStatus().name() : null);
        m.put("kvaStatus", application.getKvaStatus() != null ? application.getKvaStatus().name() : null);
        m.put("force", force);
        auditLogService.logAsync(
                actorSeq, AuditAction.KVA_CONFIRMATION_DENIED, AuditCategory.ADMIN,
                "Application", String.valueOf(application.getApplicationSeq()),
                "kVA confirmation denied: " + errorCode,
                null, m,
                null, null, "PATCH",
                "/api/admin/applications/" + application.getApplicationSeq() + "/kva",
                errorCode.equals("FORBIDDEN") ? 403
                        : errorCode.equals("KVA_LOCKED_AFTER_PAYMENT") ? 409
                        : errorCode.equals("KVA_ALREADY_CONFIRMED") ? 409
                        : errorCode.equals("INVALID_KVA_TIER") ? 400 : 400);
    }

    private void notifyApplicant(Application application, Integer newKva, BigDecimal newQuote) {
        try {
            Long recipientSeq = application.getUser() != null
                    ? application.getUser().getUserSeq() : null;
            if (recipientSeq == null) {
                return;
            }
            NumberFormat fmt = NumberFormat.getCurrencyInstance(new Locale("en", "SG"));
            String title = "kVA confirmed";
            String message = String.format(
                    "Your LEW confirmed %d kVA. Price updated to %s.",
                    newKva, fmt.format(newQuote));
            notificationService.createNotification(
                    recipientSeq, NotificationType.KVA_CONFIRMED,
                    title, message,
                    "Application", application.getApplicationSeq());
        } catch (RuntimeException ex) {
            // 알림 실패가 확정 트랜잭션을 롤백시키지 않도록 방어 (AC-P1 의도)
            log.warn("kVA 확정 알림 발송 실패: applicationId={}, err={}",
                    application.getApplicationSeq(), ex.getMessage());
        }
    }
}
