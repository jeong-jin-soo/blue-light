package com.bluelight.backend.api.concierge.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Concierge 대리 Application 생성 응답 (★ Kaki Concierge v1.5 Phase 1 PR#5 Stage A).
 * <p>
 * {@code POST /api/concierge-manager/requests/{id}/applications} 성공 응답.
 * Frontend는 applicationSeq로 상세 페이지 이동, conciergeStatus로 다음 액션 결정.
 */
@Getter
@Builder
public class CreateOnBehalfResponse {

    /** 새로 생성된 Application의 seq */
    private Long applicationSeq;

    /** 연결된 ConciergeRequest의 seq */
    private Long conciergeRequestSeq;

    /** 전이 후 ConciergeRequest.status (APPLICATION_CREATED) */
    private String conciergeStatus;
}
