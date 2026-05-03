package com.bluelight.backend.api.lew;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * PR-3 / AC-L4: ADMIN 의 직접 kVA override 시, 동일 신청에 존재하던 LEW 의 PENDING 요청이 자동
 * 해소(RESOLVED_BY_ADMIN_OVERRIDE) 됐음을 알리는 도메인 이벤트.
 *
 * <p>요청한 LEW 가 배정 LEW 와 동일하면 PR-2 {@code KVA_ADJUSTED_BY_ADMIN_LEW} 알림으로 이미
 * 통지가 발송된다. 그러나 요청자가 다른 LEW 일 수 있고(자기 미배정 신청에 요청 가능 여부는
 * §4.2 AC-L2 에서 차단되지만, 운영 중 재배정 race 가 가능), 또한 요청자에게는 본인이 보낸
 * 요청이 어떤 결과로 해소되었는지 별도 통지하는 것이 감사·UX 차원에서 명확하다.</p>
 *
 * <p>리스너({@code LewKvaAdjustmentResolvedNotificationListener})는 요청자(requestingLewUserSeq)에게
 * 인앱 알림 + 이메일을 발송하되, 멱등성 가드로 동일 (LEW, application, type) 알림이 이미 존재하면
 * 스킵한다 — PR-2 listener 와 같은 패턴을 적용해 중복 발송을 방지한다.</p>
 *
 * @param applicationSeq          대상 신청 PK
 * @param requestingLewUserSeq    PENDING 요청을 보냈던 LEW userSeq (수신자)
 * @param lewRequestAdjustmentSeq 해소된 LEW 요청 row 의 PK
 * @param newAdjustmentSeq        ADMIN 이 새로 작성한 ADMIN row 의 PK
 * @param proposedKva             LEW 가 제안했던 kVA (참조 표시용)
 * @param appliedKva              ADMIN 이 실제로 적용한 kVA (제안과 다를 수 있음)
 */
@Getter
@RequiredArgsConstructor
public class LewKvaRequestResolvedByOverrideEvent {
    private final Long applicationSeq;
    private final Long requestingLewUserSeq;
    private final Long lewRequestAdjustmentSeq;
    private final Long newAdjustmentSeq;
    private final Integer proposedKva;
    private final Integer appliedKva;
}
