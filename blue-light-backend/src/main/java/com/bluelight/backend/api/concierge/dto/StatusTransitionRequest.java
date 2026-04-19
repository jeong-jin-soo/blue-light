package com.bluelight.backend.api.concierge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Concierge 상태 전이 요청 DTO (★ Phase 1 PR#4 Stage A).
 * <p>
 * nextStatus 값은 {@link com.bluelight.backend.domain.concierge.ConciergeRequestStatus} 중 하나.
 * ASSIGNED 전이에서만 assignedManagerSeq 사용 (null이면 actor self-assign).
 */
@Getter
@Setter
@NoArgsConstructor
public class StatusTransitionRequest {

    @NotBlank
    private String nextStatus;

    /** ASSIGNED 전이 시에만 의미. null이면 actor 본인을 담당자로 지정. */
    private Long assignedManagerSeq;
}
