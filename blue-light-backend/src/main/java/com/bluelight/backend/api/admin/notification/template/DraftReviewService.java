package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.domain.notification.NotificationCategory;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.bluelight.backend.domain.notification.NotificationTemplateDraftRepository;
import com.bluelight.backend.domain.notification.NotificationTemplateHistory;
import com.bluelight.backend.domain.notification.NotificationTemplateHistoryRepository;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.bluelight.backend.domain.notification.TemplateChangeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * D-1 결정에 따른 2-step publish 워크플로 — SYSTEM_ADMIN 전용 approve/reject.
 *
 * <p>책임 분리:
 * <ul>
 *   <li>{@link NotificationTemplateAdminService} — Draft 작성·편집·회수 + enable/disable</li>
 *   <li>본 서비스 — Draft 리뷰 (publish 트랜잭션 + history 적재)</li>
 * </ul>
 *
 * <p>{@code approve} 트랜잭션: (a) draft 상태 전이 (b) 본 테이블({@link NotificationTemplate}) 반영
 * 또는 신규 생성 (c) {@link NotificationTemplateHistory} insert — 모두 같은 commit.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DraftReviewService {

    private static final Set<NotificationCategory> CATEGORIES_REQUIRING_REASON = Set.of(
            NotificationCategory.SECURITY,
            NotificationCategory.PAYMENT,
            NotificationCategory.MARKETING
    );

    private final NotificationTemplateRepository templateRepository;
    private final NotificationTemplateDraftRepository draftRepository;
    private final NotificationTemplateHistoryRepository historyRepository;
    private final TemplateSnapshotMapper snapshotMapper;

    /**
     * SA 가 draft 를 승인 → 본 테이블 반영 + history insert.
     *
     * @param draftSeq      대상 draft
     * @param reviewerSeq   SYSTEM_ADMIN user_seq
     * @param reviewNote    리뷰 코멘트 (선택, 단 D-6 카테고리는 필수)
     * @param actorIp       감사 — 리뷰어의 요청 IP
     * @return 적용된 NotificationTemplate (신규 또는 갱신된 row)
     */
    @Transactional
    public NotificationTemplate approve(Long draftSeq, Long reviewerSeq, String reviewNote, String actorIp) {
        NotificationTemplateDraft draft = loadDraft(draftSeq);
        validateReviewNote(draft.getCategory(), reviewNote);

        NotificationTemplate template;
        Map<String, Object> beforeSnap;
        TemplateChangeType changeType;

        if (draft.getTemplateSeq() == null) {
            // 신규 템플릿
            template = NotificationTemplate.builder()
                    .templateCode(draft.getTemplateCode())
                    .channel(draft.getChannel())
                    .locale(draft.getLocale())
                    .subject(draft.getSubject())
                    .bodyText(draft.getBodyText())
                    .variablesJson(draft.getVariablesJson())
                    .providerTemplateName(draft.getProviderTemplateName())
                    .enabled(true)
                    .catalogMetaKey(draft.getTemplateCode())
                    .category(draft.getCategory())
                    .severity(draft.getSeverity())
                    .recipientRoles(draft.getRecipientRoles())
                    .build();
            template = templateRepository.save(template);
            beforeSnap = new LinkedHashMap<>();
            changeType = TemplateChangeType.CREATE;
        } else {
            // 기존 template 갱신
            template = templateRepository.findById(draft.getTemplateSeq())
                    .orElseThrow(() -> new NotificationTemplateAdminService.TemplateNotFoundException(draft.getTemplateSeq()));
            beforeSnap = snapshotMapper.snapshot(template);
            template.applyPublishedSnapshot(
                    draft.getSubject(),
                    draft.getBodyText(),
                    draft.getVariablesJson(),
                    draft.getProviderTemplateName(),
                    draft.getCategory(),
                    draft.getSeverity(),
                    draft.getRecipientRoles()
            );
            changeType = TemplateChangeType.PUBLISH;
        }

        Map<String, Object> afterSnap = snapshotMapper.snapshot(template);
        Map<String, Object> diff = new LinkedHashMap<>(snapshotMapper.diff(beforeSnap, afterSnap));

        historyRepository.save(NotificationTemplateHistory.builder()
                .templateSeq(template.getTemplateSeq())
                .changeType(changeType)
                .diffJson(snapshotMapper.toJson(diff))
                .beforeSnapshotJson(snapshotMapper.toJson(beforeSnap))
                .afterSnapshotJson(snapshotMapper.toJson(afterSnap))
                .changeReason(reviewNote)
                .actorUserSeq(reviewerSeq)
                .actorIp(actorIp)
                .build());

        draft.approve(reviewerSeq, reviewNote);
        log.info("Template {} ({} {}) published by reviewer={}, changeType={}",
                template.getTemplateCode(), template.getChannel(), template.getLocale(),
                reviewerSeq, changeType);
        return template;
    }

    /** SA 가 draft 를 거절 — reviewNote 필수 (엔티티에서도 검증). */
    @Transactional
    public void reject(Long draftSeq, Long reviewerSeq, String reviewNote) {
        NotificationTemplateDraft draft = loadDraft(draftSeq);
        draft.reject(reviewerSeq, reviewNote);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private NotificationTemplateDraft loadDraft(Long draftSeq) {
        return draftRepository.findById(draftSeq)
                .orElseThrow(() -> new NotificationTemplateAdminService.DraftNotFoundException(draftSeq));
    }

    private void validateReviewNote(NotificationCategory category, String reviewNote) {
        boolean required = category != null && CATEGORIES_REQUIRING_REASON.contains(category);
        if (required && (reviewNote == null || reviewNote.isBlank())) {
            throw new NotificationTemplateAdminService.ChangeReasonRequiredException(category,
                    category + " 카테고리 publish 시 reviewNote(사유)는 필수입니다.");
        }
    }
}
