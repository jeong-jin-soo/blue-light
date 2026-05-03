import { useEffect, useMemo, useState } from 'react';
import { Button } from '../ui/Button';
import { Select } from '../ui/Select';
import { Textarea } from '../ui/Textarea';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../ui/Modal';
import { ConfirmDialog } from '../ui/ConfirmDialog';
import { useToastStore } from '../../stores/toastStore';
import lewReviewApi from '../../api/lewReviewApi';
import priceApi from '../../api/priceApi';
import type { AdminApplication, MasterPrice } from '../../types';

/**
 * 결제 후 kVA 사후 변경 요청 모달 (LEW 전용, PR-3).
 *
 * <p>스펙: doc/Project Analysis/kva-postpayment-adjustment-spec.md §4.2.</p>
 *
 * <ul>
 *   <li>kVA tier 옵션은 priceApi.getPrices() 로 동적 로드 — 하드코딩 금지(설정 우선 원칙).</li>
 *   <li>요청은 단순 제안 — Application.selectedKva 는 변경되지 않으며, ADMIN 검토 대기 row 가 생성된다.</li>
 *   <li>제출 시 confirm dialog 로 사용자 의사 재확인.</li>
 *   <li>에러 코드 매핑:
 *     <ul>
 *       <li>409 KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING — 동일 application 의 PENDING 요청 존재</li>
 *       <li>409 KVA_NOT_POSTPAYMENT — 결제 전 신청은 별도 흐름 사용</li>
 *       <li>409 KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED — EXPIRED 신청 거부</li>
 *       <li>400 KVA_NO_CHANGE — 동일 proposedKva</li>
 *       <li>400 INVALID_KVA_TIER — master_prices 미존재</li>
 *     </ul>
 *   </li>
 * </ul>
 */

const REASON_MIN = 10;
const REASON_MAX = 1000;

interface Props {
  isOpen: boolean;
  application: AdminApplication;
  onClose: () => void;
  onSuccess: () => void;
}

export function LewKvaAdjustmentRequestModal({
  isOpen,
  application,
  onClose,
  onSuccess,
}: Props) {
  const toast = useToastStore();

  const [proposedKva, setProposedKva] = useState<number>(application.selectedKva || 45);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);

  // master_prices 활성 tier — 하드코딩 금지 (CLAUDE.md §1).
  const [tierOptions, setTierOptions] =
      useState<Array<{ value: number; label: string }>>([]);
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
      setProposedKva(application.selectedKva || 45);
      setReason('');
      setShowConfirm(false);
    }
  }, [isOpen, application.selectedKva]);

  const reasonTrimmed = reason.trim();

  const errors = useMemo(() => {
    const errs: string[] = [];
    if (!proposedKva) errs.push('Select a kVA tier.');
    if (tierOptions.length > 0 && !tierOptions.some((t) => t.value === proposedKva)) {
      errs.push('Selected kVA is not in the current Admin price table. Choose another.');
    }
    if (proposedKva === application.selectedKva) {
      errs.push('Proposed kVA must differ from current value.');
    }
    if (reasonTrimmed.length < REASON_MIN) {
      errs.push(`Reason must be at least ${REASON_MIN} characters.`);
    }
    if (reasonTrimmed.length > REASON_MAX) {
      errs.push(`Reason must be at most ${REASON_MAX} characters.`);
    }
    return errs;
  }, [proposedKva, tierOptions, application.selectedKva, reasonTrimmed]);

  const canSubmit = errors.length === 0 && !submitting;

  const handleConfirm = async () => {
    if (!canSubmit) {
      setShowConfirm(false);
      return;
    }
    setShowConfirm(false);
    setSubmitting(true);
    try {
      await lewReviewApi.requestKvaAdjustment(application.applicationSeq, {
        proposedKva,
        reason: reasonTrimmed,
      });
      toast.success('Adjustment request submitted. Admin will review.');
      onSuccess();
      onClose();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      switch (e.code) {
        case 'KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING':
          toast.error('You already have a pending request for this application.');
          break;
        case 'KVA_NOT_POSTPAYMENT':
          toast.error('kVA can only be adjusted after payment is confirmed.');
          break;
        case 'KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED':
          toast.error('EXPIRED applications cannot be adjusted.');
          break;
        case 'KVA_NO_CHANGE':
          toast.error('Proposed kVA is identical to the current value.');
          break;
        case 'INVALID_KVA_TIER':
          toast.error('Selected kVA tier is not available.');
          break;
        case 'APPLICATION_NOT_ASSIGNED':
          toast.error('You are no longer assigned to this application.');
          break;
        case 'APPLICATION_NOT_FOUND':
          toast.error('Application not found.');
          break;
        default:
          toast.error(e.message ?? 'Failed to submit adjustment request');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const close = () => {
    if (submitting) return;
    onClose();
  };

  return (
    <>
      <Modal
        isOpen={isOpen && !showConfirm}
        onClose={close}
        size="md"
        closeOnEscape={!submitting}
        closeOnOverlay={!submitting}
        ariaLabelledBy="lew-kva-adjustment-title"
      >
        <ModalHeader onClose={close}>
          <div className="flex items-center gap-2">
            <span className="text-xl" aria-hidden>⚡</span>
            <div>
              <h3 id="lew-kva-adjustment-title" className="text-lg font-semibold text-gray-800">
                Request kVA adjustment
              </h3>
              <p className="text-xs text-gray-500 mt-0.5">
                Application #{application.applicationSeq} · Status {application.status}
              </p>
            </div>
          </div>
        </ModalHeader>

        <ModalBody>
          <div
            role="note"
            className="text-sm text-info-700 bg-info-50 border border-info-500/40 rounded-md p-3 mb-4"
          >
            <p className="font-medium">This is a request to Admin</p>
            <p className="mt-0.5">
              Submitting this form does not change the kVA on the application. The admin
              will review your suggestion and decide whether to apply, modify, or reject it.
            </p>
          </div>

          <div className="bg-gray-50 border border-gray-200 rounded-md p-3 text-sm mb-4">
            <div className="grid grid-cols-2 gap-y-1.5 gap-x-4">
              <div>
                <dt className="text-xs text-gray-500">Current kVA</dt>
                <dd className="text-gray-800">{application.selectedKva} kVA</dd>
              </div>
              <div>
                <dt className="text-xs text-gray-500">Proposed kVA</dt>
                <dd className="text-gray-800 font-medium">{proposedKva} kVA</dd>
              </div>
            </div>
          </div>

          <div className="space-y-4">
            <Select
              label="Proposed kVA tier"
              required
              value={String(proposedKva)}
              onChange={(e) => setProposedKva(Number(e.target.value))}
              options={tierOptions.map((t) => ({
                value: String(t.value),
                label: t.label,
              }))}
              disabled={submitting || tiersLoading || tierOptions.length === 0}
              hint={
                tiersLoading
                  ? 'Loading tiers configured by Admin…'
                  : tierOptions.length === 0
                    ? 'No active kVA tiers. Contact admin.'
                    : `Based on Admin master price table (${tierOptions.length} active ${tierOptions.length === 1 ? 'tier' : 'tiers'})`
              }
            />

            <Textarea
              label="Reason for adjustment"
              required
              rows={3}
              placeholder="Why does the kVA need to change? (e.g., 'Site survey: actual load 180 kVA, recommend 200 kVA tier')"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              disabled={submitting}
              maxLength={REASON_MAX}
              hint={`${reasonTrimmed.length} / ${REASON_MAX} (min ${REASON_MIN})`}
            />

            <p className="text-xs text-gray-500">
              Do not include NRIC, UEN or other personal identifiers in the reason field.
            </p>
          </div>
        </ModalBody>

        <ModalFooter>
          <Button variant="outline" size="sm" onClick={close} disabled={submitting}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={() => setShowConfirm(true)}
            loading={submitting}
            disabled={!canSubmit}
          >
            Submit request
          </Button>
        </ModalFooter>
      </Modal>

      <ConfirmDialog
        isOpen={showConfirm}
        title="Submit kVA adjustment request to Admin?"
        message={
          `Proposed: ${proposedKva} kVA (current ${application.selectedKva ?? '—'} kVA). `
          + 'The admin will be notified and will decide whether to apply, modify, or reject your request.'
        }
        confirmLabel="Submit request"
        onConfirm={handleConfirm}
        onClose={() => setShowConfirm(false)}
      />
    </>
  );
}

export default LewKvaAdjustmentRequestModal;
