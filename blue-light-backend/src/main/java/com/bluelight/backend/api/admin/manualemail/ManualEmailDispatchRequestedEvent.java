package com.bluelight.backend.api.admin.manualemail;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ADMIN 수동 이메일 발송 트랜잭션 커밋 직후 발행되는 이벤트.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §8.1, §8.7.</p>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * <ul>
 *   <li>비즈니스 트랜잭션의 본질은 {@code ManualEmailDispatch} row 저장 + audit 로그 기록이며,
 *       SMTP 발송은 부수 효과다. SMTP/외부 서비스 일시 오류가 row 저장을 롤백시켜선 안 된다 —
 *       발송 이력의 "PENDING" 마저 사라지면 운영 재현이 불가능해진다.</li>
 *   <li>{@code TransactionPhase.AFTER_COMMIT} 에서 SMTP 호출 + status 갱신 (성공 시 SENT, 실패 시
 *       FAILED + failedReason). 어떤 결과든 비즈니스 트랜잭션은 이미 커밋되어 보존된다.</li>
 * </ul>
 *
 * <h3>이벤트 페이로드 정책</h3>
 * <p>row 의 모든 발송 정보(to, subject, bodyText, adminEmail) 가 이미 DB 에 영속되어 있으므로,
 * 이벤트는 식별자({@code dispatchSeq}) 만 전달한다. 리스너가 row 를 다시 조회해 발송에 필요한
 * 필드를 읽고, status 갱신도 동일 row 에 한다 — 이벤트 페이로드 비대화를 방지하고 단일 정본을 유지.</p>
 *
 * @param dispatchSeq 발행된 {@code ManualEmailDispatch} PK
 */
@Getter
@RequiredArgsConstructor
public class ManualEmailDispatchRequestedEvent {
    private final Long dispatchSeq;
}
