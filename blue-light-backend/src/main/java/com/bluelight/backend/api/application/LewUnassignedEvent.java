package com.bluelight.backend.api.application;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Application 에서 LEW 배정이 해제(또는 다른 LEW 로 재배정)될 때, 떠나는 LEW 에게 통지하기 위해
 * 발행되는 이벤트.
 *
 * <p>기존엔 {@code AdminLewService.unassignLew}/재배정 시 떠나는 LEW 에게 아무 통지가 없어
 * 자기 큐에서 신청이 조용히 사라졌다(#4). 본 이벤트로 보완한다.</p>
 *
 * <p>{@link LewUnassignmentNotificationListener} 가 AFTER_COMMIT 으로 수신해 인앱+이메일 통지.
 * 진행 산출물(EMA/LoA/파일)은 보존되며 새 LEW 가 인계한다 — 데이터 이전 없음.</p>
 *
 * @param applicationSeq      대상 Application PK
 * @param previousLewUserSeq  배정 해제된(떠나는) LEW user_seq
 * @param reassigned          다른 LEW 로 교체된 재배정이면 true, 단순 해제면 false
 */
@Getter
@RequiredArgsConstructor
public class LewUnassignedEvent {
    private final Long applicationSeq;
    private final Long previousLewUserSeq;
    private final boolean reassigned;
}
