package com.bluelight.backend.api.concierge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ★ Concierge 강화 + 별도 수금 PR-3 — LEW 배정 요청 DTO.
 *
 * <p>스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §5.3 / §14 PR-3.</p>
 *
 * <h3>D6=A (셀프 할당)</h3>
 * 매니저 본인이 동시에 LEW role 을 보유한 경우, {@code lewUserSeq=actor.userSeq} 로 호출하여
 * 셀프 할당 가능. 별도 플래그 없이 서비스 레이어에서 actor.userSeq vs lewUserSeq 비교로 판정.
 */
@Getter
@Setter
@NoArgsConstructor
public class AssignLewRequest {

    /**
     * 배정할 LEW 의 user_seq. 필수.
     * <p>LEW role 보유 (primary 또는 다중 역할 secondary) + 활성/승인 상태가 검증된다.
     */
    @NotNull(message = "lewUserSeq is required")
    private Long lewUserSeq;
}
