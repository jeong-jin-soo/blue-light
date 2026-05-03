package com.bluelight.backend.api.lew;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * LEW 가 결제 후 kVA 변경 요청을 ADMIN 에게 보낸 직후 발행되는 도메인 이벤트 (PR-3).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.2 / PR-3.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 본 트랜잭션의 본질은 {@code KvaAdjustmentRecord(LEW PENDING)} row 작성이며, 알림 발송은
 * 부수 효과다. SMTP/외부 서비스 일시 오류가 LEW 의 요청 트랜잭션을 롤백시켜선 안 된다.
 * 그래서 이벤트 구독을 {@code AFTER_COMMIT} 으로 분리한다 (PR-2 {@code KvaOverrideAppliedEvent}
 * 와 동일 원칙).
 *
 * @param applicationSeq        대상 신청 PK
 * @param adjustmentSeq         생성된 LEW 요청 row 의 PK ({@code KvaAdjustmentRecord.adjustmentSeq})
 * @param lewUserSeq            요청한 LEW userSeq
 * @param lewName               LEW 이름 (이메일/인앱 본문 인사·표기용, escape 필요)
 * @param proposedKva           LEW 가 제안한 kVA
 * @param currentKva            요청 시점 application.selectedKva (참조 표시용)
 * @param reason                LEW 가 입력한 사유 (HTML escape 후 본문 표시)
 */
@Getter
@RequiredArgsConstructor
public class LewKvaAdjustmentRequestedEvent {
    private final Long applicationSeq;
    private final Long adjustmentSeq;
    private final Long lewUserSeq;
    private final String lewName;
    private final Integer proposedKva;
    private final Integer currentKva;
    private final String reason;
}
