package com.bluelight.backend.api.admin.manualemail.dto;

import com.bluelight.backend.domain.manualemail.BodyFormat;
import com.bluelight.backend.domain.manualemail.DispatchStatus;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import com.bluelight.backend.domain.manualemail.RecipientType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 발송 이력 응답 DTO — 목록(요약)/상세(전체 본문) 양쪽에서 동일하게 사용한다.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5.2 / §5.3.</p>
 *
 * <p>PR-1 은 단일 수신자만 처리하므로 {@code recipientEmail} 단일 필드를 그대로 노출한다. PR-2 의
 * 다중 수신자 활성화 시 {@code recipientCount} / {@code recipientPreview} 등으로 확장된다.</p>
 */
@Getter
@Builder
public class ManualEmailDispatchHistoryItem {

    private final Long dispatchSeq;
    private final Long senderUserSeq;
    private final RecipientType recipientType;
    private final Long recipientUserSeq;
    private final String recipientEmail;
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
        return ManualEmailDispatchHistoryItem.builder()
                .dispatchSeq(entity.getDispatchSeq())
                .senderUserSeq(entity.getSenderUserSeq())
                .recipientType(entity.getRecipientType())
                .recipientUserSeq(entity.getRecipientUserSeq())
                .recipientEmail(entity.getRecipientEmail())
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
