import { useEffect, useMemo, useState } from 'react';
import { Button } from '../ui/Button';
import { Select } from '../ui/Select';
import { Textarea } from '../ui/Textarea';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../ui/Modal';
import { useToastStore } from '../../stores/toastStore';
import { overrideKvaPostPayment } from '../../api/adminApplicationApi';
import priceApi from '../../api/priceApi';
import type { AdminApplication, MasterPrice } from '../../types';

/**
 * 결제 후 kVA 사후 변경 모달 (ADMIN 전용, PR-1).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.1.</p>
 *
 * <ul>
 *   <li>kVA tier 옵션은 {@code priceApi.getPrices()} 로 동적 로드 — 하드코딩 금지(설정 우선 원칙).</li>
 *   <li>변경 미리보기: previousQuoteAmount → newQuoteAmount, amountDifference 표시.</li>
 *   <li>CoF 가 finalized 인 경우 "LEW 재서명 필요" 경고 노출.</li>
 *   <li>에러 코드 매핑:
 *     <ul>
 *       <li>409 KVA_NOT_POSTPAYMENT — 결제 전 신청은 별도 엔드포인트 사용</li>
 *       <li>409 KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED — EXPIRED 신청 거부</li>
 *       <li>400 KVA_NO_CHANGE — 동일 newKva</li>
 *       <li>400 INVALID_KVA_TIER — master_prices 미존재</li>
 *       <li>409 STALE_STATE — @Version 충돌</li>
 *     </ul>
 *   </li>
 * </ul>
 */

const REASON_MIN = 10;
const REASON_MAX = 1000;
const MEMO_MAX = 2000;

interface Props {
  isOpen: boolean;
  application: AdminApplication;
  onClose: () => void;
  onSuccess: () => void;
}

const PAYMENT_ADJUSTMENT_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'PENDING', label: 'Pending — settle later' },
  { value: 'PAID_DIFFERENCE', label: 'Paid difference (applicant paid extra)' },
  { value: 'REFUNDED', label: 'Refunded (refund issued)' },
  { value: 'WAIVED', label: 'Waived (no settlement required)' },
];

export function KvaPostPaymentOverrideModal({
  isOpen,
  application,
  onClose,
  onSuccess,
}: Props) {
  const toast = useToastStore();

  const [newKva, setNewKva] = useState<number>(application.selectedKva || 45);
  const [reason, setReason] = useState('');
  const [adminMemo, setAdminMemo] = useState('');
  const [paymentAdjustment, setPaymentAdjustment] = useState<string>('PENDING');
  const [submitting, setSubmitting] = useState(false);

  // master_prices 에서 활성 tier 동적 로드 — 하드코딩 금지 (설정 우선 원칙).
  const [tierOptions, setTierOptions] =
      useState<Array<{ value: number; price: number; label: string }>>([]);
  const [tiersLoading, setTiersLoading] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    setTiersLoading(true);
    priceApi.getPrices()
      .then((tiers: MasterPrice[]) => {
        const active = tiers
          .filter((t) => t.isActive)
          .map((t) => ({
            value: t.kvaMin,
            price: Number(t.price),
            label: t.description || `${t.kvaMin}${t.kvaMax > t.kvaMin ? `–${t.kvaMax}` : ''} kVA`,
          }))
          .sort((a, b) => a.value - b.value);
        setTierOptions(active);
      })
      .catch(() => {
        toast.error('Failed to load kVA tiers. Please close and reopen.');
        setTierOptions([]);
      })
      .finally(() => setTiersLoading(false));
  }, [isOpen]);

  useEffect(() => {
    if (isOpen) {
      setNewKva(application.selectedKva || 45);
      setReason('');
      setAdminMemo('');
      setPaymentAdjustment('PENDING');
    }
  }, [isOpen, application.selectedKva]);

  // 변경 미리보기 (서버 재계산과 일치를 100% 보장하지는 않지만 master_prices 표시 가격을 사용)
  const previousQuote = Number(application.quoteAmount || 0);
  const selectedTier = tierOptions.find((t) => t.value === newKva);
  const newQuoteEstimate = selectedTier?.price ?? 0;
  // 신청 sldFee + emaFee 등은 백엔드 재계산 결과와 다를 수 있어 추정치임을 명시.
  const amountDifference = newQuoteEstimate - previousQuote;

  const reasonTrimmed = reason.trim();

  const errors = useMemo(() => {
    const errs: string[] = [];
    if (!newKva) errs.push('Select a kVA tier.');
    if (tierOptions.length > 0 && !tierOptions.some((t) => t.value === newKva)) {
      errs.push('Selected kVA is not in the current Admin price table. Choose another.');
    }
    if (newKva === application.selectedKva) {
      errs.push('New kVA must differ from current value.');
    }
    if (reasonTrimmed.length < REASON_MIN) {
      errs.push(`Reason must be at least ${REASON_MIN} characters.`);
    }
    if (reasonTrimmed.length > REASON_MAX) {
      errs.push(`Reason must be at most ${REASON_MAX} characters.`);
    }
    if (adminMemo.length > MEMO_MAX) {
      errs.push(`Admin memo must be at most ${MEMO_MAX} characters.`);
    }
    return errs;
  }, [newKva, tierOptions, application.selectedKva, reasonTrimmed, adminMemo]);

  const canSubmit = errors.length === 0 && !submitting;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await overrideKvaPostPayment(application.applicationSeq, {
        newKva,
        reason: reasonTrimmed,
        adminMemo: adminMemo.trim() || undefined,
        paymentAdjustment,
      });
      toast.success('kVA overridden successfully');
      onSuccess();
      onClose();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      switch (e.code) {
        case 'KVA_NOT_POSTPAYMENT':
          toast.error('Use pre-payment kVA confirm flow for this status');
          break;
        case 'KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED':
          toast.error('EXPIRED applications cannot be adjusted');
          break;
        case 'KVA_NO_CHANGE':
          toast.error('New kVA is identical to current value');
          break;
        case 'INVALID_KVA_TIER':
          toast.error('Selected kVA tier is not available');
          break;
        case 'STALE_STATE':
          toast.error('Concurrent update detected — refresh and try again');
          break;
        case 'APPLICATION_NOT_FOUND':
          toast.error('Application not found');
          break;
        default:
          toast.error(e.message ?? 'Failed to override kVA');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const close = () => {
    if (submitting) return;
    onClose();
  };

  const fmt = (n: number) => `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  return (
    <Modal
      isOpen={isOpen}
      onClose={close}
      size="md"
      closeOnEscape={!submitting}
      closeOnOverlay={!submitting}
      ariaLabelledBy="kva-postpayment-title"
    >
      <ModalHeader onClose={close}>
        <div className="flex items-center gap-2">
          <span className="text-xl" aria-hidden>⚡</span>
          <div>
            <h3 id="kva-postpayment-title" className="text-lg font-semibold text-gray-800">
              Override kVA (post-payment)
            </h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Application #{application.applicationSeq} · Status {application.status}
            </p>
          </div>
        </div>
      </ModalHeader>

      <ModalBody>
        <div
          role="alert"
          className="text-sm text-warning-700 bg-warning-50 border border-warning-500/40 rounded-md p-3 mb-4"
        >
          <p className="font-medium">Post-payment override</p>
          <p className="mt-0.5">
            This will update the licensed kVA after payment. The existing invoice will be
            marked INVALIDATED and a new one will be issued. Recorded in the audit log
            with previous values.
          </p>
        </div>

        {/* Current vs New preview */}
        <div className="bg-gray-50 border border-gray-200 rounded-md p-3 text-sm mb-4">
          <div className="grid grid-cols-2 gap-y-1.5 gap-x-4">
            <div>
              <dt className="text-xs text-gray-500">Current kVA</dt>
              <dd className="text-gray-800">{application.selectedKva} kVA</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Current quote</dt>
              <dd className="text-gray-800">{fmt(previousQuote)}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">New kVA</dt>
              <dd className="text-gray-800 font-medium">{newKva} kVA</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">New quote (est.)</dt>
              <dd className="text-gray-800 font-medium">
                {selectedTier ? fmt(newQuoteEstimate) : '—'}
              </dd>
            </div>
            <div className="col-span-2 pt-2 border-t border-gray-200">
              <dt className="text-xs text-gray-500">Difference (est.)</dt>
              <dd className={`font-medium ${amountDifference > 0 ? 'text-error-600' : amountDifference < 0 ? 'text-success-600' : 'text-gray-700'}`}>
                {amountDifference > 0 ? '+' : ''}{fmt(amountDifference)}
                <span className="ml-2 text-xs text-gray-500 font-normal">
                  (server recalculates final amount with sldFee/emaFee)
                </span>
              </dd>
            </div>
          </div>
        </div>

        <div className="space-y-4">
          <Select
            label="New kVA tier"
            required
            value={String(newKva)}
            onChange={(e) => setNewKva(Number(e.target.value))}
            options={tierOptions.map((t) => ({
              value: String(t.value),
              label: t.label,
            }))}
            disabled={submitting || tiersLoading || tierOptions.length === 0}
            hint={
              tiersLoading
                ? 'Loading tiers configured by Admin…'
                : tierOptions.length === 0
                  ? 'No active kVA tiers. Configure them in Admin → Prices.'
                  : `Based on Admin master price table (${tierOptions.length} active ${tierOptions.length === 1 ? 'tier' : 'tiers'})`
            }
          />

          <Textarea
            label="Reason for override"
            required
            rows={3}
            placeholder="Why is the kVA being changed after payment? (e.g., 'Site survey: actual load 180 kVA, recommend 200 kVA tier')"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            disabled={submitting}
            maxLength={REASON_MAX}
            hint={`${reasonTrimmed.length} / ${REASON_MAX} (min ${REASON_MIN})`}
          />

          <Textarea
            label="Admin memo (optional)"
            rows={2}
            placeholder="Internal memo (e.g., 'Refund processed via PayNow ref ABC123')"
            value={adminMemo}
            onChange={(e) => setAdminMemo(e.target.value)}
            disabled={submitting}
            maxLength={MEMO_MAX}
            hint={`${adminMemo.length} / ${MEMO_MAX}`}
          />

          <Select
            label="Payment adjustment"
            required
            value={paymentAdjustment}
            onChange={(e) => setPaymentAdjustment(e.target.value)}
            options={PAYMENT_ADJUSTMENT_OPTIONS}
            disabled={submitting}
            hint="Mark how settlement is being handled. Settlement amounts can be recorded later (PR-4)."
          />

          <p className="text-xs text-gray-500">
            Do not include NRIC, UEN or other personal identifiers in the memo.
          </p>
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
          Override kVA
        </Button>
      </ModalFooter>
    </Modal>
  );
}

export default KvaPostPaymentOverrideModal;
