package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.notification.template.lint.LintInput;
import com.bluelight.backend.api.notification.template.lint.LintResult;
import com.bluelight.backend.api.notification.template.lint.TemplateLintException;
import com.bluelight.backend.api.notification.template.lint.TemplateLinter;
import com.bluelight.backend.domain.notification.NotificationCatalog;
import com.bluelight.backend.domain.notification.NotificationCatalogRepository;
import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationSeverity;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.bluelight.backend.domain.notification.NotificationTemplateDraftRepository;
import com.bluelight.backend.domain.notification.NotificationTemplateHistory;
import com.bluelight.backend.domain.notification.NotificationTemplateHistoryRepository;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.bluelight.backend.domain.notification.TemplateChangeType;
import com.bluelight.backend.domain.notification.TemplateDraftStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 알림 템플릿 Admin 서비스 — Draft 생성·편집·회수 + enable/disable + lint 진입점.
 *
 * <p>접근 제어는 컨트롤러 단의 {@code @PreAuthorize} 에서 처리(NM/SA 만). 본 서비스는 정책 검증
 * (D-6 change_reason 필수, H-S3 SECURITY/PAYMENT disable 권한)만 책임진다.</p>
 *
 * <p>publish 흐름(approve/reject)은 별도 {@link DraftReviewService} 가 처리한다 — 책임 분리.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationTemplateAdminService {

    private static final Set<NotificationCategory> CATEGORIES_REQUIRING_REASON = Set.of(
            NotificationCategory.SECURITY,
            NotificationCategory.PAYMENT,
            NotificationCategory.MARKETING
    );

    private static final int SECURITY_DISABLE_MIN_REASON_LENGTH = 50;

    private final NotificationTemplateRepository templateRepository;
    private final NotificationTemplateDraftRepository draftRepository;
    private final NotificationTemplateHistoryRepository historyRepository;
    private final NotificationCatalogRepository catalogRepository;
    private final TemplateLinter linter;
    private final TemplateSnapshotMapper snapshotMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ============================================================
    // Draft CRUD (NM)
    // ============================================================

    /** 새 draft 생성 — lint 통과해야 저장. submitter 본인이 PENDING 상태로 적재. */
    @Transactional
    public NotificationTemplateDraft createDraft(DraftMutationInput input, Long submittedBy) {
        runLintOrThrow(input);
        NotificationTemplateDraft draft = NotificationTemplateDraft.builder()
                .templateSeq(input.templateSeq())
                .templateCode(input.templateCode())
                .channel(input.channel())
                .locale(input.locale())
                .subject(input.subject())
                .bodyText(input.body())
                .variablesJson(input.variablesJson())
                .providerTemplateName(input.providerTemplateName())
                .category(input.category())
                .severity(input.severity())
                .recipientRoles(input.recipientRoles())
                .submittedBy(submittedBy)
                .submissionNote(input.submissionNote())
                .build();
        return draftRepository.save(draft);
    }

    /** 본인 draft 수정 — PENDING 상태에서만. lint 재검증. */
    @Transactional
    public NotificationTemplateDraft editDraft(Long draftSeq, DraftMutationInput input, Long actorUserSeq) {
        NotificationTemplateDraft draft = loadDraft(draftSeq);
        ensureOwner(draft, actorUserSeq);
        runLintOrThrow(input);
        draft.edit(input.subject(), input.body(), input.variablesJson(),
                input.providerTemplateName(), input.category(), input.severity(),
                input.recipientRoles(), input.submissionNote());
        return draft;
    }

    /** 본인 draft 회수 — PENDING 상태에서만. */
    @Transactional
    public void withdrawDraft(Long draftSeq, Long actorUserSeq) {
        NotificationTemplateDraft draft = loadDraft(draftSeq);
        ensureOwner(draft, actorUserSeq);
        draft.withdraw();
    }

    // ============================================================
    // Enable / Disable (NM·SA, H-S3 SECURITY/PAYMENT 가드)
    // ============================================================

    /** 템플릿 활성화 — D-6 카테고리에 따라 reason 필수 여부 분기. */
    @Transactional
    public void enableTemplate(Long templateSeq, String changeReason, Long actorUserSeq, String actorIp) {
        NotificationTemplate template = loadTemplate(templateSeq);
        if (template.isEnabled()) return;
        validateChangeReason(template.getCategory(), changeReason, /*isDisable*/ false);

        Map<String, Object> before = snapshotMapper.snapshot(template);
        template.enable();
        Map<String, Object> after = snapshotMapper.snapshot(template);

        recordHistory(template.getTemplateSeq(), TemplateChangeType.ENABLE,
                before, after, changeReason, actorUserSeq, actorIp);
    }

    /**
     * 템플릿 비활성화 — H-S3: SECURITY/PAYMENT 카테고리는 SYSTEM_ADMIN 권한 + reason 50 자 이상 + 이벤트 발행.
     *
     * @param isSystemAdmin 호출자 역할이 SYSTEM_ADMIN 인지 — 컨트롤러가 SecurityContext 에서 판단해 전달
     */
    @Transactional
    public void disableTemplate(Long templateSeq,
                                String changeReason,
                                Long actorUserSeq,
                                String actorIp,
                                boolean isSystemAdmin) {
        NotificationTemplate template = loadTemplate(templateSeq);
        if (!template.isEnabled()) return;

        NotificationCategory cat = template.getCategory();
        boolean lockedCategory = cat == NotificationCategory.SECURITY || cat == NotificationCategory.PAYMENT;
        if (lockedCategory && !isSystemAdmin) {
            throw new SecurityCategoryDisableNotPermittedException(template.getTemplateCode(), cat);
        }
        validateChangeReason(cat, changeReason, /*isDisable*/ true);

        Map<String, Object> before = snapshotMapper.snapshot(template);
        template.disable();
        Map<String, Object> after = snapshotMapper.snapshot(template);

        recordHistory(template.getTemplateSeq(), TemplateChangeType.DISABLE,
                before, after, changeReason, actorUserSeq, actorIp);

        if (lockedCategory) {
            eventPublisher.publishEvent(new SecurityTemplateDisableEvent(
                    template.getTemplateSeq(),
                    template.getTemplateCode(),
                    cat,
                    actorUserSeq,
                    actorIp,
                    changeReason
            ));
        }
    }

    // ============================================================
    // Reads
    // ============================================================

    @Transactional(readOnly = true)
    public Optional<NotificationTemplate> findTemplate(Long templateSeq) {
        return templateRepository.findById(templateSeq);
    }

    @Transactional(readOnly = true)
    public Optional<NotificationTemplateDraft> findDraft(Long draftSeq) {
        return draftRepository.findById(draftSeq);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private NotificationTemplate loadTemplate(Long templateSeq) {
        return templateRepository.findById(templateSeq)
                .orElseThrow(() -> new TemplateNotFoundException(templateSeq));
    }

    private NotificationTemplateDraft loadDraft(Long draftSeq) {
        return draftRepository.findById(draftSeq)
                .orElseThrow(() -> new DraftNotFoundException(draftSeq));
    }

    private void ensureOwner(NotificationTemplateDraft draft, Long actorUserSeq) {
        if (!draft.getSubmittedBy().equals(actorUserSeq)) {
            throw new DraftOwnershipException(draft.getDraftSeq(), actorUserSeq);
        }
    }

    private void runLintOrThrow(DraftMutationInput input) {
        Optional<NotificationCatalog> catalog = catalogRepository.findByTemplateCode(input.templateCode());
        LintInput lintInput = new LintInput(
                input.templateCode(),
                input.channel(),
                input.subject(),
                input.body(),
                input.category(),
                input.providerTemplateName(),
                input.variablesJson(),
                catalog.map(NotificationCatalog::getAllowedVariablesJson).orElse(null),
                catalog.map(NotificationCatalog::getRequiredTokensJson).orElse(null)
        );
        LintResult result = linter.lint(lintInput);
        if (!result.isPassed()) {
            throw new TemplateLintException(result);
        }
    }

    private void validateChangeReason(NotificationCategory category, String reason, boolean isDisable) {
        boolean required = category != null && CATEGORIES_REQUIRING_REASON.contains(category);
        if (required && (reason == null || reason.isBlank())) {
            throw new ChangeReasonRequiredException(category);
        }
        if (isDisable && category == NotificationCategory.SECURITY
                && (reason == null || reason.trim().length() < SECURITY_DISABLE_MIN_REASON_LENGTH)) {
            throw new ChangeReasonRequiredException(category,
                    "SECURITY 카테고리 disable 사유는 " + SECURITY_DISABLE_MIN_REASON_LENGTH + " 자 이상이어야 합니다.");
        }
    }

    private void recordHistory(Long templateSeq,
                               TemplateChangeType type,
                               Map<String, Object> before,
                               Map<String, Object> after,
                               String reason,
                               Long actorUserSeq,
                               String actorIp) {
        Map<String, Object> diff = new LinkedHashMap<>(snapshotMapper.diff(before, after));
        historyRepository.save(NotificationTemplateHistory.builder()
                .templateSeq(templateSeq)
                .changeType(type)
                .diffJson(snapshotMapper.toJson(diff))
                .beforeSnapshotJson(snapshotMapper.toJson(before))
                .afterSnapshotJson(snapshotMapper.toJson(after))
                .changeReason(reason)
                .actorUserSeq(actorUserSeq)
                .actorIp(actorIp)
                .build());
    }

    // ============================================================
    // Input + Exceptions
    // ============================================================

    /** Draft 생성·수정 공용 입력. */
    public record DraftMutationInput(Long templateSeq,
                                     String templateCode,
                                     NotificationChannel channel,
                                     String locale,
                                     String subject,
                                     String body,
                                     String variablesJson,
                                     String providerTemplateName,
                                     NotificationCategory category,
                                     NotificationSeverity severity,
                                     String recipientRoles,
                                     String submissionNote) {
    }

    public static class TemplateNotFoundException extends RuntimeException {
        public TemplateNotFoundException(Long seq) {
            super("Template not found: " + seq);
        }
    }

    public static class DraftNotFoundException extends RuntimeException {
        public DraftNotFoundException(Long seq) {
            super("Draft not found: " + seq);
        }
    }

    public static class DraftOwnershipException extends RuntimeException {
        public DraftOwnershipException(Long draftSeq, Long actor) {
            super("Draft " + draftSeq + " is not owned by user " + actor);
        }
    }

    public static class ChangeReasonRequiredException extends RuntimeException {
        public ChangeReasonRequiredException(NotificationCategory category) {
            super("change_reason 은 카테고리 " + category + " 에 대해 필수입니다.");
        }
        public ChangeReasonRequiredException(NotificationCategory category, String message) {
            super(message);
        }
    }

    public static class SecurityCategoryDisableNotPermittedException extends RuntimeException {
        public SecurityCategoryDisableNotPermittedException(String code, NotificationCategory category) {
            super("Template " + code + " (" + category + ") 는 SYSTEM_ADMIN 만 disable 할 수 있습니다.");
        }
    }
}
