package com.bluelight.backend.api.application;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Application 에 LEW 가 배정된 직후 발행되는 도메인 이벤트.
 *
 * <p>두 배정 경로를 단일 알림 흐름으로 통일하기 위해 도입:</p>
 * <ol>
 *   <li><b>자동 배정</b> — {@code ApplicationService.createApplication} 에서 해당 kVA 를 처리 가능한
 *       승인 LEW 가 정확히 1명일 때 자동 할당. (기존엔 무알림 — 본 이벤트로 보완)</li>
 *   <li><b>ADMIN 수동 배정</b> — {@code AdminLewService.assignLew}. (기존엔 이메일만, 인앱 알림 누락)</li>
 * </ol>
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 본 트랜잭션의 본질은 {@code Application.assignedLew} 설정이며, 알림 발송(SMTP, NotificationService
 * REQUIRES_NEW 트랜잭션)은 부수 효과다. 외부 의존 일시 오류가 배정 트랜잭션을 롤백시켜선 안 된다
 * ({@code ConciergeLewAssignedEvent} 와 동일 원칙).
 *
 * <h3>본 이벤트가 트리거하는 후속 작업</h3>
 * {@link LewAssignmentNotificationListener} 가 배정된 LEW 에게 인앱 알림
 * ({@link com.bluelight.backend.domain.notification.NotificationType#APPLICATION_LEW_ASSIGNED_LEW})
 * + 이메일({@code EmailService.sendLewAssignedEmail}) 을 발송한다.
 *
 * @param applicationSeq 대상 Application PK (인앱 알림 referenceId + 이메일 본문/CTA)
 * @param lewUserSeq     배정된 LEW user_seq (리스너가 최신 이메일/이름을 repo 에서 로드)
 * @param applicantName  신청자 이름 (이메일 본문 표시 — escape 후 사용)
 * @param address        설치 주소 (이메일 본문 표시)
 * @param autoAssigned   자동 단일 적격 배정이면 true, ADMIN 수동 배정이면 false (로그/디버깅용 구분)
 * @param reassigned     기존 다른 LEW 를 교체한 재배정이면 true (새 LEW 에게 "진행중" 표시용).
 *                       자동 배정·최초 배정이면 false.
 */
@Getter
@RequiredArgsConstructor
public class LewAssignedEvent {
    private final Long applicationSeq;
    private final Long lewUserSeq;
    private final String applicantName;
    private final String address;
    private final boolean autoAssigned;
    private final boolean reassigned;
}
