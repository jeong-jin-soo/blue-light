/**
 * ADMIN 수동 이메일 발송 — 프론트 타입 정의 (PR-3).
 *
 * 백엔드 DTO 와 1:1 정합:
 * - {@code SendManualEmailRequest}
 * - {@code ManualEmailDispatchResponse}
 * - {@code ManualEmailDispatchHistoryItem}
 * - {@code ManualEmailPreviewResponse}
 *
 * 스펙: doc/Project Analysis/admin-manual-email-spec.md §5.
 */

export type RecipientType = 'APPLICANT' | 'LEW' | 'EXTERNAL' | 'MULTI';

export type BodyFormat = 'PLAIN_TEXT' | 'HTML';

export type DispatchStatus = 'PENDING' | 'SENT' | 'PARTIAL_FAILED' | 'FAILED';

/**
 * 발송/미리보기 요청 DTO.
 * - 단일: APPLICANT/LEW → recipientUserSeq, EXTERNAL → recipientEmail
 * - 다수(MULTI): recipientUserSeqs + recipientEmails (합 ≥ 2, ≤ 100)
 */
export interface SendManualEmailRequest {
  recipientType: RecipientType;
  /** APPLICANT/LEW 단일 발송 시 시스템 사용자 user_seq */
  recipientUserSeq?: number | null;
  /** EXTERNAL 단일 발송 시 이메일 주소 */
  recipientEmail?: string | null;
  /** MULTI 발송 시 시스템 사용자 user_seq 목록 */
  recipientUserSeqs?: number[] | null;
  /** MULTI 발송 시 외부 이메일 목록 */
  recipientEmails?: string[] | null;
  /** 신청 컨텍스트 (옵션) */
  relatedApplicationSeq?: number | null;
  /** 메일 제목 (1~200자) */
  subject: string;
  /** PLAIN_TEXT 본문 (1~50,000자) */
  bodyText: string;
  /** 자유 분류 태그 (옵션, ≤50자) */
  categoryTag?: string | null;
  /** 30초 이내 동일 내용 발송 시 멱등성 가드 우회 (D3=B) */
  forceDuplicate?: boolean;
  /**
   * PR-4 (D4=B): 시스템 사용자 수신자(APPLICANT/LEW)에게 인앱 알림 동반 생성 여부.
   * 미지정/null 은 백엔드에서 true 로 처리. EXTERNAL 단일 발송에는 무관 (시스템 계정 없음 →
   * 자동 스킵). MULTI 시 시스템 사용자 부분에만 적용.
   */
  alsoCreateInAppNotification?: boolean;
}

/** 발송 즉시 응답 — 트랜잭션 커밋 시점 (status=PENDING 가능). */
export interface ManualEmailDispatchResponse {
  dispatchSeq: number;
  dispatchStatus: DispatchStatus;
  dispatchedAt: string | null;
  sentCount: number;
  failedCount: number;
}

/** 발송 이력 목록/상세 공용 DTO. */
export interface ManualEmailDispatchHistoryItem {
  dispatchSeq: number;
  senderUserSeq: number;
  recipientType: RecipientType;
  recipientUserSeq: number | null;
  recipientEmail: string | null;
  /** MULTI: 시스템 사용자 user_seq 목록. 단일/EXTERNAL 시 null. */
  recipientUserSeqs: number[] | null;
  /** MULTI: 전체 발송 대상 이메일 목록. 단일 시 null. */
  recipientEmails: string[] | null;
  /** 단일=1, MULTI=N — History 표 "Recipients" 컬럼 표시용. */
  recipientCount: number;
  relatedApplicationSeq: number | null;
  subject: string;
  bodyText: string;
  bodyFormat: BodyFormat;
  categoryTag: string | null;
  dispatchStatus: DispatchStatus;
  sentCount: number;
  failedCount: number;
  failedReason: string | null;
  dispatchedAt: string | null;
  createdAt: string;
  /** PR-4: 시스템 사용자 수신자 인앱 알림 동반 생성 여부 (운영 추적). */
  alsoCreateInAppNotification: boolean;
}

/**
 * PR-4: 잔여 발송 한도 스냅샷 — Compose UI 우상단 표시 (스펙 §7.2.1).
 * `usedToday + remaining = dailyCap` 항등식을 만족.
 */
export interface ManualEmailQuotaSnapshot {
  dailyCap: number;
  usedToday: number;
  remaining: number;
}

/**
 * PR-4: Category tag 추천 옵션 응답 — system_settings 에서 로드 (CSV → string[]).
 */
export interface ManualEmailCategorySuggestionsResponse {
  suggestions: string[];
}

/** 미리보기 응답 — 발송 전 ADMIN 이 모달로 확인. */
export interface ManualEmailPreviewResponse {
  renderedSubject: string;
  /** 자동 헤더 + 본문 + 자동 푸터(반피싱)가 부착된 안전한 HTML. iframe sandbox 권장. */
  renderedHtmlPreview: string;
}

/** 이력 조회 필터 (모두 옵션). */
export interface ManualEmailHistoryFilter {
  /** "내 발송분만" 토글 시 현재 사용자 seq, 전체 보기 시 undefined */
  senderUserSeq?: number;
  dispatchStatus?: DispatchStatus;
  relatedApplicationSeq?: number;
  /** ISO-8601 LocalDateTime (예: 2026-05-01T00:00:00) */
  from?: string;
  /** ISO-8601 LocalDateTime */
  to?: string;
}
