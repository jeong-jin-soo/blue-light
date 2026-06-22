// 신청 활동 타임라인 — 백엔드 AuditAction → 한국어 라벨/아이콘 매핑.
// 백엔드 com.bluelight.backend.domain.audit.AuditAction 미러. 누락 액션은 getActivityMeta 가 폴백 처리.

export interface ActivityMeta {
  label: string;
  icon: string;
}

export const ACTIVITY_LABELS: Record<string, ActivityMeta> = {
  // 신청 라이프사이클
  APPLICATION_CREATED: { label: '신청서 생성', icon: '📝' },
  APPLICATION_UPDATED: { label: '신청서 수정', icon: '✏️' },
  APPLICATION_RESUBMITTED: { label: '신청서 재제출', icon: '🔁' },
  APPLICATION_STATUS_CHANGE: { label: '상태 변경', icon: '🔀' },
  APPLICATION_REVISION_REQUESTED: { label: '보완 요청', icon: '⏳' },
  APPLICATION_APPROVED: { label: '결제 승인 (결제 요청)', icon: '✅' },
  APPLICATION_COMPLETED: { label: '신청 완료 — 면허 발급', icon: '🏁' },
  APPLICATION_VIEWED_BY_LEW: { label: 'LEW 열람', icon: '👀' },
  APPLICATION_PAYMENT_REQUESTED_BY_LEW: { label: 'LEW 결제 요청', icon: '💸' },

  // 자동(스케줄러)
  LICENSE_EXPIRED: { label: '면허 만료 (자동)', icon: '⌛' },
  LICENSE_EXPIRY_WARNING_SENT: { label: '만료 임박 알림 발송 (자동)', icon: '🔔' },

  // LEW 배정
  LEW_ASSIGNED: { label: 'LEW 배정', icon: '👷' },
  LEW_UNASSIGNED: { label: 'LEW 배정 해제', icon: '🚫' },

  // 결제
  PAYMENT_CONFIRMED: { label: '결제 확인', icon: '💳' },
  MANUAL_PAYMENT_RECORDED: { label: '별도 수금 기록', icon: '💰' },

  // 영수증
  INVOICE_GENERATED: { label: '영수증 발행', icon: '🧾' },
  INVOICE_REGENERATED: { label: '영수증 재발행', icon: '🧾' },
  INVOICE_GENERATION_FAILED: { label: '영수증 발행 실패', icon: '⚠️' },

  // 파일
  FILE_UPLOADED: { label: '파일 업로드', icon: '📎' },
  FILE_DELETED: { label: '파일 삭제', icon: '🗑️' },

  // 서류 요청
  DOCUMENT_UPLOADED_VOLUNTARY: { label: '서류 자발 업로드', icon: '📎' },
  DOCUMENT_DELETED_VOLUNTARY: { label: '서류 삭제', icon: '🗑️' },
  DOCUMENT_REQUEST_CREATED: { label: '서류 요청 생성', icon: '📋' },
  DOCUMENT_REQUEST_FULFILLED: { label: '서류 제출', icon: '📥' },
  DOCUMENT_REQUEST_CANCELLED: { label: '서류 요청 취소', icon: '🚫' },

  // LoA
  LOA_SNAPSHOT_CREATED: { label: 'LoA 스냅샷 생성', icon: '📄' },
  LOA_FORM_SENT: { label: 'LoA 폼 전달', icon: '📄' },
  LOA_APPLICANT_UPLOADED: { label: '신청자 LoA 업로드', icon: '📄' },
  LOA_FINAL_UPLOADED: { label: 'LEW 최종 LoA 업로드', icon: '📄' },
  LOA_ADMIN_REPLACED: { label: 'LoA 파일 교체 (관리자)', icon: '♻️' },

  // kVA
  KVA_CONFIRMED_BY_LEW: { label: 'kVA 확정 (LEW)', icon: '⚡' },
  KVA_OVERRIDDEN_BY_ADMIN: { label: 'kVA 변경 (관리자)', icon: '⚡' },
  KVA_OVERRIDE_POSTPAYMENT: { label: '결제 후 kVA 변경', icon: '⚡' },
  KVA_ADJUSTMENT_REQUESTED_BY_LEW: { label: 'kVA 조정 요청 (LEW)', icon: '⚡' },
  KVA_LEW_REQUEST_RESOLVED_BY_OVERRIDE: { label: 'kVA 요청 처리 (override)', icon: '⚡' },
  KVA_SETTLEMENT_MARKED: { label: 'kVA 정산 처리', icon: '🧾' },
  KVA_SETTLEMENT_DENIED: { label: 'kVA 정산 거부', icon: '⚠️' },

  // EMA ELISE 제출 추적
  EMA_SUBMITTED: { label: 'EMA 제출', icon: '📤' },
  EMA_QUERY_RAISED: { label: 'EMA 질의 발생', icon: '❓' },
  EMA_RESUBMITTED: { label: 'EMA 재제출', icon: '🔁' },
  EMA_APPROVED: { label: 'EMA 승인', icon: '✅' },
  EMA_REJECTED: { label: 'EMA 반려', icon: '⚠️' },
  EMA_WITHDRAWN: { label: 'EMA 철회', icon: '↩️' },
  EMA_DECISION_REVERTED: { label: 'EMA 결정 되돌림', icon: '↩️' },
};

/** AuditAction 문자열 → 라벨/아이콘. 미정의 액션은 enum 명을 보기 좋게 폴백. */
export function getActivityMeta(action: string): ActivityMeta {
  const found = ACTIVITY_LABELS[action];
  if (found) return found;
  return {
    label: action
      .toLowerCase()
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(' '),
    icon: '•',
  };
}
