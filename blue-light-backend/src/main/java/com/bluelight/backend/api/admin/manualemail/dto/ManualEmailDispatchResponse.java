package com.bluelight.backend.api.admin.manualemail.dto;

import com.bluelight.backend.domain.manualemail.DispatchStatus;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * ADMIN 수동 이메일 발송 결과 응답 DTO ({@code POST /api/admin/manual-emails}).
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5.1.</p>
 *
 * <p>발송 시점에 row 는 PENDING 으로 저장되고 AFTER_COMMIT 에서 SMTP 가 시도된다 — 이 응답은
 * 컨트롤러가 즉시 반환하므로 PENDING 으로 응답될 수 있다. 클라이언트는 발송 결과 확인이
 * 필요하면 {@code GET /api/admin/manual-emails/{seq}} 를 폴링하거나 History 탭에서 status 를 확인한다.</p>
 */
@Getter
@Builder
public class ManualEmailDispatchResponse {

    private final Long dispatchSeq;
    private final DispatchStatus dispatchStatus;
    private final LocalDateTime dispatchedAt;
    private final int sentCount;
    private final int failedCount;

    public static ManualEmailDispatchResponse from(ManualEmailDispatch entity) {
        return ManualEmailDispatchResponse.builder()
                .dispatchSeq(entity.getDispatchSeq())
                .dispatchStatus(entity.getDispatchStatus())
                .dispatchedAt(entity.getDispatchedAt())
                .sentCount(entity.getSentCount())
                .failedCount(entity.getFailedCount())
                .build();
    }
}
