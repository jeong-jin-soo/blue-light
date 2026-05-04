/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-4 — Manual Payment 모달.
 *
 * <p>스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §7.3, §10 AC-A1~A7.</p>
 *
 * <p>본 모달은 ADMIN의 Application 별도 수금과 CONCIERGE_MANAGER의 ConciergeRequest 별도 수금
 * 에서 공통으로 사용된다 (호출자 컨텍스트는 onSubmit 콜백에서 처리).</p>
 *
 * <h3>입력 필드</h3>
 * <ul>
 *   <li>Amount (BigDecimal, 양수, 기본값=quoteAmount/quotedAmount)</li>
 *   <li>Paid At (DatePicker, 기본=today)</li>
 *   <li>Payment Method (BANK_TRANSFER / PAYNOW_OFFLINE / CASH / OTHER — PAYNOW_ONLINE 제외)</li>
 *   <li>Reference Note (max 500자, optional)</li>
 *   <li>Issue Receipt (체크박스, 기본 ON)</li>
 * </ul>
 *
 * <h3>견적 차이 경고</h3>
 * amount 가 expectedAmount(quoteAmount) 와 다를 경우 노란색 경고 박스 표시 (D4=B 정책).
 */

import { useEffect, useState } from 'react';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../ui/Modal';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { Textarea } from '../ui/Textarea';
import {
  OFFLINE_PAYMENT_METHODS,
  PAYMENT_METHOD_LABELS,
  type PaymentMethod,
  type ManualPaymentPayload,
} from '../../types/manualPayment';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (payload: ManualPaymentPayload) => Promise<void>;
  /** 모달 헤더 표시용 — "Application #123" 또는 "Concierge ABCDEF". */
  contextLabel: string;
  /** 신청자/제출자 표시명 — preview 영역에 노출. */
  recipientName: string;
  /**
   * 견적 금액 (SGD). amount 기본값 + 차이 경고 기준.
   * Concierge 의 경우 quotedAmount, Application 의 경우 quoteAmount.
   * null/undefined 면 견적 비교 생략.
   */
  expectedAmount?: number | null;
  /** Submit 동작 중 로딩 UI. */
  loading?: boolean;
}

function todayIso(): string {
  // LocalDate ISO (YYYY-MM-DD) — 백엔드 LocalDate parse 호환.
  return new Date().toISOString().slice(0, 10);
}

export function ManualPaymentModal({
  isOpen,
  onClose,
  onSubmit,
  contextLabel,
  recipientName,
  expectedAmount,
  loading = false,
}: Props) {
  const [amount, setAmount] = useState<string>(
    expectedAmount != null ? String(expectedAmount) : '',
  );
  const [paidAt, setPaidAt] = useState<string>(todayIso());
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('BANK_TRANSFER');
  const [referenceNote, setReferenceNote] = useState<string>('');
  const [issueReceipt, setIssueReceipt] = useState<boolean>(true);
  const [submitConfirm, setSubmitConfirm] = useState<boolean>(false);
  const [errMsg, setErrMsg] = useState<string | null>(null);

  // 모달 재오픈 시 기본값 재설정 (expectedAmount 변경에 반응).
  // ESLint react-hooks/set-state-in-effect 는 controlled-modal 의 prop-driven reset 패턴을
  // 잘못 진단한다 — KvaConfirmModal 등 코드베이스의 다른 모달과 동일한 합법 패턴.
  // 실효성: isOpen=false→true 전이의 단일-frame 재설정으로 cascading render 위험 없음.
  useEffect(() => {
    if (isOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setAmount(expectedAmount != null ? String(expectedAmount) : '');
      setPaidAt(todayIso());
      setPaymentMethod('BANK_TRANSFER');
      setReferenceNote('');
      setIssueReceipt(true);
      setSubmitConfirm(false);
      setErrMsg(null);
    }
  }, [isOpen, expectedAmount]);

  const numericAmount = Number(amount);
  const isAmountValid = Number.isFinite(numericAmount) && numericAmount > 0;
  const amountDiffersFromQuote =
    expectedAmount != null
    && isAmountValid
    && Math.abs(numericAmount - expectedAmount) > 0.005;

  const canSubmit = isAmountValid && !!paidAt && !!paymentMethod;

  const handleSubmit = async () => {
    setErrMsg(null);
    if (!canSubmit) return;
    if (!submitConfirm) {
      setSubmitConfirm(true);
      return;
    }
    try {
      await onSubmit({
        amount: numericAmount,
        paidAt,
        paymentMethod,
        referenceNote: referenceNote.trim() || undefined,
        receiptIssue: issueReceipt,
      });
      // 부모가 onClose 처리. 모달 close 후 useEffect 가 reset.
    } catch (err) {
      const msg = err && typeof err === 'object' && 'message' in err
        ? String((err as { message?: unknown }).message)
        : 'Failed to record payment';
      setErrMsg(msg);
      setSubmitConfirm(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="md" ariaLabelledBy="manual-payment-title">
      <ModalHeader title="Record manual payment" onClose={onClose}>
        <h3 id="manual-payment-title" className="text-lg font-semibold text-gray-800">
          Record manual payment
        </h3>
      </ModalHeader>
      <ModalBody className="space-y-4">
        <div className="bg-gray-50 rounded-md p-3 border border-gray-200">
          <p className="text-xs text-gray-500">Context</p>
          <p className="text-sm font-medium text-gray-800">{contextLabel}</p>
          <p className="text-xs text-gray-500 mt-1">Recipient</p>
          <p className="text-sm text-gray-700">{recipientName}</p>
        </div>

        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1" htmlFor="mp-amount">
            Amount (SGD) <span className="text-error-600">*</span>
          </label>
          <Input
            id="mp-amount"
            type="number"
            min="0"
            step="0.01"
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder="0.00"
            disabled={loading}
          />
          {amount && !isAmountValid && (
            <p className="mt-1 text-xs text-error-600">Amount must be a positive number.</p>
          )}
          {amountDiffersFromQuote && expectedAmount != null && (
            <div className="mt-2 rounded-md bg-warning-50 border border-warning-200 p-2.5 text-xs text-warning-800">
              <p className="font-medium">Amount differs from quote</p>
              <p className="mt-0.5">
                Quoted: SGD {expectedAmount.toFixed(2)} · Recorded: SGD {numericAmount.toFixed(2)}
                {' '}({(numericAmount - expectedAmount >= 0 ? '+' : '')}
                {(numericAmount - expectedAmount).toFixed(2)})
              </p>
              <p className="mt-1 text-warning-700">
                The difference will be logged in the audit trail (D4=B).
              </p>
            </div>
          )}
        </div>

        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1" htmlFor="mp-paidAt">
            Paid at <span className="text-error-600">*</span>
          </label>
          <Input
            id="mp-paidAt"
            type="date"
            value={paidAt}
            onChange={(e) => setPaidAt(e.target.value)}
            max={todayIso()}
            disabled={loading}
          />
          <p className="mt-1 text-xs text-gray-500">
            Date the funds were actually received (cannot be in the future).
          </p>
        </div>

        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1" htmlFor="mp-method">
            Payment method <span className="text-error-600">*</span>
          </label>
          <select
            id="mp-method"
            className="block w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500 disabled:bg-gray-100"
            value={paymentMethod}
            onChange={(e) => setPaymentMethod(e.target.value as PaymentMethod)}
            disabled={loading}
          >
            {OFFLINE_PAYMENT_METHODS.map((method) => (
              <option key={method} value={method}>
                {PAYMENT_METHOD_LABELS[method]}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-gray-500">
            Online PayNow (in-site PG) cannot be selected here — use the standard payment flow.
          </p>
        </div>

        <div>
          <label className="block text-xs font-medium text-gray-600 mb-1" htmlFor="mp-note">
            Reference note (optional)
          </label>
          <Textarea
            id="mp-note"
            rows={2}
            maxLength={500}
            value={referenceNote}
            onChange={(e) => setReferenceNote(e.target.value)}
            placeholder="e.g. DBS transfer ref 12345, PayNow QR receipt #ABC"
            disabled={loading}
          />
          <p className="mt-1 text-xs text-gray-500">
            {referenceNote.length}/500 — appears in audit log only, not on the receipt PDF.
          </p>
        </div>

        <div className="flex items-start gap-2 rounded-md bg-gray-50 p-3 border border-gray-200">
          <input
            id="mp-receipt"
            type="checkbox"
            className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
            checked={issueReceipt}
            onChange={(e) => setIssueReceipt(e.target.checked)}
            disabled={loading}
          />
          <label htmlFor="mp-receipt" className="text-sm text-gray-700">
            <span className="font-medium">Issue receipt automatically</span>
            <span className="block text-xs text-gray-500 mt-0.5">
              {issueReceipt
                ? 'A receipt PDF will be generated and emailed to the applicant.'
                : 'No receipt will be issued. Use only for accounting reconciliation.'}
            </span>
          </label>
        </div>

        {/* Preview — submitConfirm 단계 */}
        {submitConfirm && isAmountValid && (
          <div className="rounded-md bg-primary-50 border border-primary-200 p-3 text-sm">
            <p className="font-medium text-primary-800">Confirm submission</p>
            <p className="text-primary-700 mt-1">
              Record SGD {numericAmount.toFixed(2)} from <span className="font-medium">{recipientName}</span>
              {' '}via <span className="font-medium">{PAYMENT_METHOD_LABELS[paymentMethod]}</span> on {paidAt}.
              Receipt: {issueReceipt ? 'ON' : 'OFF'}.
            </p>
            <p className="text-xs text-primary-600 mt-1.5">Click "Record payment" again to confirm.</p>
          </div>
        )}

        {errMsg && (
          <div role="alert" className="rounded-md bg-error-50 border border-error-200 p-3 text-sm text-error-700">
            {errMsg}
          </div>
        )}
      </ModalBody>
      <ModalFooter>
        <Button variant="ghost" onClick={onClose} disabled={loading}>
          Cancel
        </Button>
        <Button
          variant="primary"
          onClick={handleSubmit}
          disabled={!canSubmit || loading}
          loading={loading}
        >
          {submitConfirm ? 'Confirm & record' : 'Record payment'}
        </Button>
      </ModalFooter>
    </Modal>
  );
}
