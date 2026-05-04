/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-4 — Concierge 별도 수금 모달.
 *
 * <p>스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §7.3, AC-A4.</p>
 *
 * <p>본 컴포넌트는 ADMIN의 Application 별도 수금과 동일한 UI 를 사용한다 — 차이점은:</p>
 * <ul>
 *   <li>contextLabel 에 ConciergeRequest publicCode 표시</li>
 *   <li>expectedAmount 는 quotedAmount (Application 의 quoteAmount 와 별개)</li>
 *   <li>onSubmit 콜백이 conciergeManagerApi.recordManualPayment 를 호출</li>
 * </ul>
 *
 * <p>UI 분리 이유: 향후 컨시어지 전용 입력(예: splitFee, waiveServiceCharge)이 추가될 가능성이
 * 있어, ManualPaymentModal 의 contract 를 단순하게 유지하고 컨시어지 도메인 변경은 본 wrapper
 * 에서 흡수한다.</p>
 */

import { ManualPaymentModal } from '../admin/ManualPaymentModal';
import type { ManualPaymentPayload } from '../../types/manualPayment';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (payload: ManualPaymentPayload) => Promise<void>;
  /** ConciergeRequest publicCode (예: "ABCDEF"). */
  publicCode: string;
  /** 신청자/제출자 표시명. */
  submitterName: string;
  /** 견적 금액 (SGD) — quotedAmount 사용. null 이면 견적 비교 생략. */
  quotedAmount?: number | null;
  loading?: boolean;
}

export function ConciergeManualPaymentModal({
  isOpen,
  onClose,
  onSubmit,
  publicCode,
  submitterName,
  quotedAmount,
  loading = false,
}: Props) {
  return (
    <ManualPaymentModal
      isOpen={isOpen}
      onClose={onClose}
      onSubmit={onSubmit}
      contextLabel={`Concierge ${publicCode}`}
      recipientName={submitterName}
      expectedAmount={quotedAmount ?? null}
      loading={loading}
    />
  );
}
