package com.bluelight.backend.api.lew.dto;

import com.bluelight.backend.domain.kva.KvaAdjustmentRecord;
import com.bluelight.backend.domain.kva.KvaAdjustmentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * LEW 의 kVA 변경 요청 응답 DTO (PR-3).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.2 AC-L1.</p>
 */
@Getter
@Builder
public class LewKvaAdjustmentResponse {

    /** 생성된 KvaAdjustmentRecord PK. */
    private Long adjustmentSeq;

    /** 요청 row 의 status. PR-3 정상 흐름에서 항상 {@link KvaAdjustmentStatus#PENDING_ADMIN_REVIEW}. */
    private KvaAdjustmentStatus status;

    /** LEW 가 제안한 kVA. */
    private Integer proposedKva;

    /** 요청 직전 application.selectedKva (참조 표시용). */
    private Integer currentKva;

    /** 요청 사유. */
    private String reason;

    /** 요청 작성 시각. */
    private LocalDateTime createdAt;

    public static LewKvaAdjustmentResponse from(KvaAdjustmentRecord record) {
        return LewKvaAdjustmentResponse.builder()
                .adjustmentSeq(record.getAdjustmentSeq())
                .status(record.getStatus())
                .proposedKva(record.getProposedKva())
                .currentKva(record.getPreviousKva())
                .reason(record.getReason())
                .createdAt(record.getCreatedAt())
                .build();
    }
}
