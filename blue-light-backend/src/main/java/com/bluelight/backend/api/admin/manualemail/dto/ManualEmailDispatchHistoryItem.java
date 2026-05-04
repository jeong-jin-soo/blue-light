package com.bluelight.backend.api.admin.manualemail.dto;

import com.bluelight.backend.domain.manualemail.BodyFormat;
import com.bluelight.backend.domain.manualemail.DispatchStatus;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import com.bluelight.backend.domain.manualemail.RecipientType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 발송 이력 응답 DTO — 목록(요약)/상세(전체 본문) 양쪽에서 동일하게 사용한다.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5.2 / §5.3.</p>
 *
 * <p>PR-2 부터는 단일/다수 수신자를 모두 표현 — {@link #recipientEmail} 은 단일 또는 대표 이메일,
 * {@link #recipientUserSeqs} / {@link #recipientEmails} 는 MULTI 시 전체 목록, {@link #recipientCount}
 * 는 합계. 단일 발송에서는 _Seqs/_Emails 는 null 이고 count=1.</p>
 */
@Getter
@Builder
public class ManualEmailDispatchHistoryItem {

    private final Long dispatchSeq;
    private final Long senderUserSeq;
    private final RecipientType recipientType;
    private final Long recipientUserSeq;
    private final String recipientEmail;
    /** PR-2 MULTI: 시스템 사용자 user_seq 목록. 단일/EXTERNAL 시 null. */
    private final List<Long> recipientUserSeqs;
    /** PR-2 MULTI: 전체 발송 대상 이메일 목록. 단일 시 null. */
    private final List<String> recipientEmails;
    /** PR-2: 전체 수신자 수 (단일=1, MULTI=N). 프론트 History 탭의 "Recipients" 컬럼 표시용. */
    private final int recipientCount;
    private final Long relatedApplicationSeq;
    private final String subject;
    private final String bodyText;
    private final BodyFormat bodyFormat;
    private final String categoryTag;
    private final DispatchStatus dispatchStatus;
    private final int sentCount;
    private final int failedCount;
    private final String failedReason;
    private final LocalDateTime dispatchedAt;
    private final LocalDateTime createdAt;

    public static ManualEmailDispatchHistoryItem from(ManualEmailDispatch entity) {
        // PR-2: recipientCount 는 single/multi 통합 — entity.resolveAllRecipientEmails() 의 size.
        int count = entity.resolveAllRecipientEmails().size();
        return ManualEmailDispatchHistoryItem.builder()
                .dispatchSeq(entity.getDispatchSeq())
                .senderUserSeq(entity.getSenderUserSeq())
                .recipientType(entity.getRecipientType())
                .recipientUserSeq(entity.getRecipientUserSeq())
                .recipientEmail(entity.getRecipientEmail())
                .recipientUserSeqs(entity.getRecipientUserSeqsJson())
                .recipientEmails(entity.getRecipientEmailsJson())
                .recipientCount(count)
                .relatedApplicationSeq(entity.getRelatedApplicationSeq())
                .subject(entity.getSubject())
                .bodyText(entity.getBodyText())
                .bodyFormat(entity.getBodyFormat())
                .categoryTag(entity.getCategoryTag())
                .dispatchStatus(entity.getDispatchStatus())
                .sentCount(entity.getSentCount())
                .failedCount(entity.getFailedCount())
                .failedReason(entity.getFailedReason())
                .dispatchedAt(entity.getDispatchedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
