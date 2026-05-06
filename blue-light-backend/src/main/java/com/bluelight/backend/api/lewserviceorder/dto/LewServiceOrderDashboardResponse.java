package com.bluelight.backend.api.lewserviceorder.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Request for LEW Service 주문 대시보드 통계 응답 DTO.
 * <p>PR 3 — enum rename 반영: {@code inProgress → visitScheduled},
 * {@code deliverableUploaded → visitCompleted}. 하위호환 위해 기존 필드도 alias 로 유지.
 */
@Getter
@Builder
public class LewServiceOrderDashboardResponse {

    private long total;
    private long pendingQuote;
    private long quoteProposed;
    private long pendingPayment;
    private long paid;
    /** PR 3 — VISIT_SCHEDULED 건수 */
    private long visitScheduled;
    /** PR 3 — VISIT_COMPLETED 건수 */
    private long visitCompleted;
    /** PR 3 — REVISIT_REQUESTED 건수 */
    private long revisitRequested;
    private long completed;

    /**
     * @deprecated PR 3 — {@link #visitScheduled} 사용 권장. 하위호환용 alias.
     */
    @Deprecated
    private long inProgress;
    /**
     * @deprecated PR 3 — {@link #visitCompleted} 사용 권장. 하위호환용 alias.
     */
    @Deprecated
    private long deliverableUploaded;
}
