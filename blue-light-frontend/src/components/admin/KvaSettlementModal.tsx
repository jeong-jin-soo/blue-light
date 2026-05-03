import { useEffect, useMemo, useState } from 'react';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { Select } from '../ui/Select';
import { Textarea } from '../ui/Textarea';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../ui/Modal';
import { useToastStore } from '../../stores/toastStore';
import {
  markKvaSettlement,
  type KvaAdjustmentHistoryItem,
} from '../../api/adminApplicationApi';

/**
 * 결제 후 kVA 사후 변경 row 의 settlement 마킹 모달 (ADMIN 전용, PR-4).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.3 / §8 PR-4.</p>
 *
 * <ul>
 *   <li>paymentAdjustment: PAID_DIFFERENCE / REFUNDED / WAIVED 중 선택 (PENDING 은 백엔드에서 거부).</li>
 *   <li>D6 정책: 이미 finalize 된 row 는 다시 마킹 불가 — 재마킹 시도 시 백엔드가 409
 *       {@code KVA_SETTLEMENT_ALREADY_FINALIZED} 반환, 모달은 이를 toast 안내.</li>
 *   <li>제출 직전 confirm dialog (간단한 window.confirm) — 동적으로 paymentAdjustment 라벨 표시.</li>
 *   <li>notifyLew 체크박스 기본 true — false 면 LEW 알림 발송 안 됨.</li>
 *   <li>amountDifference (이력 row 의 차액) 를 참고로 표시 — settledAmount 기본값으로 채워줌.</li>
 * </ul>
 */

interface Props {
  isOpen: boolean;
  applicationSeq: number;
  /** settlement 마킹 대상 row. 모달은 read-only 정보로 차액·변경 대상을 보여준다. */
  adjustment: KvaAdjustmentHistoryItem;
  onClose: () => void;
  /** 갱신된 row 를 받아 부모가 history 카드를 refresh 한다. */
  onSuccess: (updated: KvaAdjustmentHistoryItem) => void;
}

const PAYMENT_ADJUSTMENT_OPTIONS = [
  { value: 'PAID_DIFFERENCE', label: 'Paid difference (applicant paid extra)' },
  { value: 'REFUNDED', label: 'Refunded (refund issued)' },
  { value: 'WAIVED', label: 'Waived (no settlement required)' },
];

const REF_MAX = 100;
const MEMO_MAX = 1000;

export function KvaSettlementModal({
  isOpen,
  applicationSeq,
  adjustment,
  onClose,
  onSuccess,
}: Props) {
  const toast = useToastStore();

  // amountDifference 의 절댓값을 settledAmount 기본값으로 — ADMIN 이 이론값을 그대로 사용하는 케이스가 많다.
  const defaultAmount = useMemo(() => {
    const diff = adjustment.amountDifference;
    if (diff == null) return '';
    return Math.abs(diff).toFixed(2);
  }, [adjustment.amountDifference]);

  const [paymentAdjustment, setPaymentAdjustment] =
    useState<'PAID_DIFFERENCE' | 'REFUNDED' | 'WAIVED'>('PAID_DIFFERENCE');
  const [settledAmount, setSettledAmount] = useState<string>(defaultAmount);
  const [receiptRef, setReceiptRef] = useState('');
  const [memo, setMemo] = useState('');
  const [notifyLew, setNotifyLew] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  // 모달 열림 시마다 reset.
  useEffect(() => {
    if (isOpen) {
      // amountDifference 부호로 paymentAdjustment 추론: + 면 PAID_DIFFERENCE, - 면 REFUNDED.
      const diff = adjustment.amountDifference;
      if (diff != null && diff < 0) {
        setPaymentAdjustment('REFUNDED');
      } else if (diff != null && diff > 0) {
        setPaymentAdjustment('PAID_DIFFERENCE');
      } else {
        setPaymentAdjustment('WAIVED');
      }
      setSettledAmount(defaultAmount);
      setReceiptRef('');
      setMemo('');
      setNotifyLew(true);
    }
  }, [isOpen, adjustment.amountDifference, defaultAmount]);

  const errors = useMemo(() => {
    const errs: string[] = [];
    if (settledAmount.trim() !== '') {
      const n = Number(settledAmount);
      if (!Number.isFinite(n) || n <= 0) {
        errs.push('Settled amount must be a positive number.');
      }
    }
    if (receiptRef.length > REF_MAX) {
      errs.push(`Receipt reference must be at most ${REF_MAX} characters.`);
    }
    if (memo.length > MEMO_MAX) {
      errs.push(`Settlement memo must be at most ${MEMO_MAX} characters.`);
    }
    return errs;
  }, [settledAmount, receiptRef, memo]);

  const canSubmit = errors.length === 0 && !submitting;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    // 동적 confirm dialog
    const label =
      PAYMENT_ADJUSTMENT_OPTIONS.find((o) => o.value === paymentAdjustment)?.label
      ?? paymentAdjustment;
    if (!window.confirm(`Mark settlement as ${label}? This action cannot be reversed (PR-4 D6 policy).`)) {
      return;
    }

    setSubmitting(true);
    try {
      const updated = await markKvaSettlement(applicationSeq, adjustment.adjustmentSeq, {
        paymentAdjustment,
        settledAmount: settledAmount.trim() === '' ? undefined : Number(settledAmount),
        receiptReferenceNumber: receiptRef.trim() === '' ? undefined : receiptRef.trim(),
        settlementMemo: memo.trim() === '' ? undefined : memo.trim(),
        notifyLew,
      });
      toast.success('Settlement marked');
      onSuccess(updated);
      onClose();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      switch (e.code) {
        case 'KVA_SETTLEMENT_ALREADY_FINALIZED':
          toast.error('Already finalized — create a new adjustment to correct');
          break;
        case 'KVA_SETTLEMENT_NOT_APPLICABLE':
          toast.error('This row cannot be settled (status not APPLIED/RESOLVED)');
          break;
        case 'KVA_ADJUSTMENT_NOT_FOUND':
          toast.error('Adjustment record not found — refresh and try again');
          break;
        case 'KVA_SETTLEMENT_INVALID_VALUE':
          toast.error('Choose PAID_DIFFERENCE, REFUNDED, or WAIVED');
          break;
        default:
          toast.error(e.message ?? 'Failed to mark settlement');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const close = () => {
    if (submitting) return;
    onClose();
  };

  const fmt = (n: number | undefined | null) =>
    n == null
      ? '—'
      : `$${Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  return (
    <Modal
      isOpen={isOpen}
      onClose={close}
      size="md"
      closeOnEscape={!submitting}
      closeOnOverlay={!submitting}
      ariaLabelledBy="kva-settlement-title"
    >
      <ModalHeader onClose={close}>
        <div className="flex items-center gap-2">
          <span className="text-xl" aria-hidden>🧾</span>
          <div>
            <h3 id="kva-settlement-title" className="text-lg font-semibold text-gray-800">
              Mark settlement
            </h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Application #{applicationSeq} · Adjustment #{adjustment.adjustmentSeq}
            </p>
          </div>
        </div>
      </ModalHeader>

      <ModalBody>
        <div
          role="alert"
          className="text-sm text-warning-700 bg-warning-50 border border-warning-500/40 rounded-md p-3 mb-4"
        >
          <p className="font-medium">Settlement is final (D6)</p>
          <p className="mt-0.5">
            Once marked as PAID_DIFFERENCE / REFUNDED / WAIVED, this row cannot be re-marked.
            If you need to correct a settlement, create a new adjustment record instead.
          </p>
        </div>

        {/* 변경 row 요약 */}
        <div className="bg-gray-50 border border-gray-200 rounded-md p-3 text-sm mb-4">
          <div className="grid grid-cols-2 gap-y-1.5 gap-x-4">
            <div>
              <dt className="text-xs text-gray-500">Previous kVA</dt>
              <dd className="text-gray-800">
                {adjustment.previousKva != null ? `${adjustment.previousKva} kVA` : '—'}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">New kVA</dt>
              <dd className="text-gray-800 font-medium">
                {adjustment.newKva != null ? `${adjustment.newKva} kVA` : '—'}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Previous quote</dt>
              <dd className="text-gray-800">{fmt(adjustment.previousQuoteAmount)}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">New quote</dt>
              <dd className="text-gray-800">{fmt(adjustment.newQuoteAmount)}</dd>
            </div>
            <div className="col-span-2 pt-2 border-t border-gray-200">
              <dt className="text-xs text-gray-500">System-calculated difference</dt>
              <dd className="text-gray-800 font-medium">
                {adjustment.amountDifference != null && adjustment.amountDifference > 0 ? '+' : ''}
                {fmt(adjustment.amountDifference)}
              </dd>
            </div>
          </div>
        </div>

        <div className="space-y-4">
          <Select
            label="Settlement outcome"
            required
            value={paymentAdjustment}
            onChange={(e) =>
              setPaymentAdjustment(e.target.value as 'PAID_DIFFERENCE' | 'REFUNDED' | 'WAIVED')
            }
            options={PAYMENT_ADJUSTMENT_OPTIONS}
            disabled={submitting}
            hint="Choose how the difference was settled outside the platform."
          />

          <Input
            label="Settled amount (SGD)"
            type="number"
            step="0.01"
            min="0.01"
            placeholder="e.g. 200.00"
            value={settledAmount}
            onChange={(e) => setSettledAmount(e.target.value)}
            disabled={submitting || paymentAdjustment === 'WAIVED'}
            hint={
              paymentAdjustment === 'WAIVED'
                ? 'Not applicable for waived settlement'
                : 'Actual amount transferred (after fees). Optional but recommended.'
            }
          />

          <Input
            label="Receipt reference"
            placeholder="e.g. PAYNOW-ABC-123"
            value={receiptRef}
            onChange={(e) => setReceiptRef(e.target.value)}
            disabled={submitting}
            maxLength={REF_MAX}
            hint={`${receiptRef.length} / ${REF_MAX} (PayNow ref, bank ref, etc.)`}
          />

          <Textarea
            label="Settlement memo"
            rows={2}
            placeholder="Internal memo (e.g., 'Refunded via PayNow on 2026-05-15')"
            value={memo}
            onChange={(e) => setMemo(e.target.value)}
            disabled={submitting}
            maxLength={MEMO_MAX}
            hint={`${memo.length} / ${MEMO_MAX}`}
          />

          <label className="flex items-start gap-2 text-sm text-gray-700">
            <input
              type="checkbox"
              checked={notifyLew}
              onChange={(e) => setNotifyLew(e.target.checked)}
              disabled={submitting}
              className="mt-0.5"
            />
            <span>
              Notify the assigned LEW by email and in-app message.
              <span className="block text-xs text-gray-500 mt-0.5">
                Uncheck only if the LEW already knows the settlement outcome from another channel.
              </span>
            </span>
          </label>

          <p className="text-xs text-gray-500">
            Do not include NRIC, UEN or other personal identifiers in the memo or receipt reference.
          </p>

          {errors.length > 0 && (
            <div role="alert" className="text-sm text-error-700 bg-error-50 border border-error-500/40 rounded-md p-3">
              <ul className="list-disc list-inside">
                {errors.map((e) => (
                  <li key={e}>{e}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </ModalBody>

      <ModalFooter>
        <Button variant="outline" size="sm" onClick={close} disabled={submitting}>
          Cancel
        </Button>
        <Button
          size="sm"
          onClick={handleSubmit}
          loading={submitting}
          disabled={!canSubmit}
        >
          Mark as {paymentAdjustment}
        </Button>
      </ModalFooter>
    </Modal>
  );
}

export default KvaSettlementModal;
