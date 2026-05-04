/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-4 — 컨시어지 도메인 추가 타입.
 *
 * <p>conciergeManagerApi.ts 에 이미 정의된 타입(ConciergeStatus 등)은 그대로 재수출하고,
 * PR-3/PR-4 신규 필드 (LEW 배정, AssignLew Request/Response) 만 본 파일에 정의한다.</p>
 */

/**
 * ConciergeRequestStatus — PR-3 에서 LEW_ASSIGNED 가 추가됨.
 *
 * <p>conciergeManagerApi.ConciergeStatus 와 정렬해야 하지만, 본 type 은 LEW_ASSIGNED 를
 * 명시적으로 포함하여 PR-3/PR-4 신규 코드에서 사용된다.</p>
 */
export type ConciergeRequestStatus =
  | 'SUBMITTED'
  | 'ASSIGNED'
  | 'CONTACTING'
  | 'QUOTE_SENT'
  | 'APPLICATION_CREATED'
  | 'AWAITING_APPLICANT_LOA_SIGN'
  | 'AWAITING_LICENCE_PAYMENT'
  | 'IN_PROGRESS'
  | 'LEW_ASSIGNED'
  | 'COMPLETED'
  | 'CANCELLED';

/**
 * LEW 배정 요청 — POST /api/concierge-manager/requests/{id}/assign-lew.
 *
 * <p>D6=A 셀프 할당: 매니저 본인이 동시에 LEW role 보유한 경우 lewUserSeq 에 본인 userSeq 입력.
 * 백엔드가 actor.userSeq vs lewUserSeq 비교 + actor.hasRole(LEW) 검사로 자동 selfAssigned 마킹.</p>
 */
export interface AssignLewRequestPayload {
  lewUserSeq: number;
}

/**
 * LEW 배정 응답 (백엔드 AssignLewResponse mirror).
 *
 * <p>{@code previousLewSeq} 가 있으면 재할당 — 이전 LEW 에게 unassign 알림이 발송된다.
 * {@code selfAssigned=true} 이면 D6=A 케이스 — toast 메시지를 다르게 표시한다.</p>
 */
export interface AssignLewResponseDto {
  conciergeRequestSeq: number;
  assignedLewSeq: number;
  assignedLewName: string;
  lewAssignedAt: string;
  previousLewSeq: number | null;
  selfAssigned: boolean;
  status: ConciergeRequestStatus;
}
