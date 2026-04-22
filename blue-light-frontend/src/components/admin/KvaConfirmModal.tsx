import { useEffect, useMemo, useState } from 'react';
import { Button } from '../ui/Button';
import { Select } from '../ui/Select';
import { Textarea } from '../ui/Textarea';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../ui/Modal';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import { confirmKva as confirmKvaApi } from '../../api/adminApplicationApi';
import priceApi from '../../api/priceApi';
import type { AdminApplication, MasterPrice } from '../../types';

/**
 * Phase 5 PR#3 — LEW/ADMIN kVA 확정 모달.
 *
 * <ul>
 *   <li>UNKNOWN 신청의 확정 + ADMIN override(force=true) 겸용.</li>
 *   <li>note 최소 길이: 신규 확정 10자, ADMIN override 20자 (UI validation).</li>
 *   <li>에러코드 매핑:
 *     <ul>
 *       <li>403 FORCE_REQUIRES_ADMIN</li>
 *       <li>409 KVA_LOCKED_AFTER_PAYMENT</li>
 *       <li>409 KVA_ALREADY_CONFIRMED</li>
 *       <li>409 STALE_STATE (엔티티 @Version 충돌)</li>
 *       <li>400 INVALID_KVA_TIER</li>
 *     </ul>
 *   </li>
 * </ul>
 */

const NOTE_MIN = 10;
const NOTE_MIN_OVERRIDE = 20;
const NOTE_MAX = 1000;

interface KvaConfirmModalProps {
  isOpen: boolean;
  application: AdminApplication;
  onClose: () => void;
  onSuccess: () => void;
}

export function KvaConfirmModal({
  isOpen,
  application,
  onClose,
  onSuccess,
}: KvaConfirmModalProps) {
  const toast = useToastStore();
  const { user } = useAuthStore();
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SYSTEM_ADMIN';

  const isOverride = application.kvaStatus === 'CONFIRMED';
  const minNoteLen = isOverride ? NOTE_MIN_OVERRIDE : NOTE_MIN;

  const [selectedKva, setSelectedKva] = useState<number>(application.selectedKva || 45);
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Admin이 설정한 Master Price 테이블에서 active tier 목록을 동적으로 로드.
  // 하드코딩된 옵션을 쓰면 admin이 추가/삭제한 tier가 반영되지 않아
  // 백엔드가 INVALID_KVA_TIER로 거부하는 불일치가 발생한다.
  const [tierOptions, setTierOptions] = useState<Array<{ value: number; label: string }>>([]);
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
        // 로드 실패 시 빈 목록 — 사용자가 Cancel 하고 재시도하도록 toast
        toast.error('Failed to load kVA tiers. Please close and reopen.');
        setTierOptions([]);
      })
      .finally(() => setTiersLoading(false));
  }, [isOpen]);

  useEffect(() => {
    if (isOpen) {
      setSelectedKva(application.selectedKva || 45);
      setNote('');
    }
  }, [isOpen, application.selectedKva]);

  // tier 목록이 로드된 후 현재 selectedKva 가 목록에 없으면 첫 번째 tier 로 자동 교정.
  // (Admin 이 기존 tier 를 제거·수정한 뒤 legacy 신청이 들어오는 케이스)
  useEffect(() => {
    if (tierOptions.length === 0) return;
    if (!tierOptions.some((t) => t.value === selectedKva)) {
      setSelectedKva(tierOptions[0].value);
    }
  }, [tierOptions, selectedKva]);

  const noteTrimmed = note.trim();
  const errors = useMemo(() => {
    const errs: string[] = [];
    if (!selectedKva) errs.push('Select a kVA tier.');
    if (tierOptions.length > 0 && !tierOptions.some((t) => t.value === selectedKva)) {
      errs.push('Selected kVA is not in the current Admin price table. Choose another.');
    }
    if (noteTrimmed.length < minNoteLen) {
      errs.push(`Note must be at least ${minNoteLen} characters.`);
    }
    if (noteTrimmed.length > NOTE_MAX) {
      errs.push(`Note must be at most ${NOTE_MAX} characters.`);
    }
    if (isOverride && !isAdmin) {
      errs.push('Only ADMIN can override a confirmed kVA.');
    }
    return errs;
  }, [selectedKva, tierOptions, noteTrimmed, minNoteLen, isOverride, isAdmin]);

  const canSubmit = errors.length === 0 && !submitting;

  const applicantName = [application.userFirstName, application.userLastName]
    .filter(Boolean)
    .join(' ')
    .trim() || application.userEmail;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await confirmKvaApi(
        application.applicationSeq,
        { selectedKva, note: noteTrimmed },
        isOverride // ADMIN override
      );
      toast.success('kVA confirmed');
      onSuccess();
      onClose();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      switch (e.code) {
        case 'FORCE_REQUIRES_ADMIN':
          toast.error('Only ADMIN can override confirmed kVA');
          break;
        case 'KVA_LOCKED_AFTER_PAYMENT':
          toast.error('kVA cannot be changed after payment');
          break;
        case 'KVA_ALREADY_CONFIRMED':
          toast.error('Already confirmed — use override if ADMIN');
          break;
        case 'STALE_STATE':
          toast.error('Concurrent update detected — refresh and try again');
          break;
        case 'INVALID_KVA_TIER':
          toast.error('Selected kVA not available');
          break;
        case 'APPLICATION_NOT_FOUND':
          toast.error('Application not found');
          break;
        case 'ACCESS_DENIED':
        case 'FORBIDDEN':
          toast.error('You are not permitted to confirm this kVA');
          break;
        default:
          toast.error(e.message ?? 'Failed to confirm kVA');
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
    <Modal
      isOpen={isOpen}
      onClose={close}
      size="md"
      closeOnEscape={!submitting}
      closeOnOverlay={!submitting}
      ariaLabelledBy="kva-confirm-title"
    >
      <ModalHeader onClose={close}>
        <div className="flex items-center gap-2">
          <span className="text-xl" aria-hidden>⚡</span>
          <div>
            <h3 id="kva-confirm-title" className="text-lg font-semibold text-gray-800">
              {isOverride ? 'Override confirmation' : 'Confirm kVA'}
            </h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Application #{application.applicationSeq} · {applicantName}
            </p>
          </div>
        </div>
      </ModalHeader>

      <ModalBody>
        {/* Context summary */}
        <div className="bg-gray-50 border border-gray-200 rounded-md p-3 text-sm mb-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-y-1.5 gap-x-4">
            <div>
              <dt className="text-xs text-gray-500">Installation address</dt>
              <dd className="text-gray-800 truncate">{application.address}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Building type</dt>
              <dd className="text-gray-800">{application.buildingType || '—'}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Current kVA</dt>
              <dd className="text-gray-800">
                {application.selectedKva} kVA
                {application.kvaStatus === 'UNKNOWN' && (
                  <span className="ml-1 text-xs text-warning-700">(pending)</span>
                )}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Current quote</dt>
              <dd className="text-gray-800">
                ${Number(application.quoteAmount || 0).toLocaleString()}
              </dd>
            </div>
          </div>
        </div>

        {/* Override warning */}
        {isOverride && (
          <div
            role="alert"
            className="text-sm text-warning-700 bg-warning-50 border border-warning-500/40 rounded-md p-3 mb-4"
          >
            <p className="font-medium">Override confirmation</p>
            <p className="mt-0.5">
              This will override the current confirmed kVA and update the quoted price.
              Admin-only action — recorded in the audit log with previous values.
            </p>
          </div>
        )}

        <div className="space-y-4">
          <Select
            label="kVA tier"
            required
            value={String(selectedKva)}
            onChange={(e) => setSelectedKva(Number(e.target.value))}
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
            label="How did you verify this?"
            required
            rows={4}
            placeholder="Briefly explain how you verified this (e.g., 'Main breaker rating 100A @ 415V')"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            disabled={submitting}
            maxLength={NOTE_MAX}
            hint={`${noteTrimmed.length} / ${NOTE_MAX} (min ${minNoteLen})`}
          />

          <p className="text-xs text-gray-500">
            Do not include NRIC, UEN or other personal identifiers in the note.
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
          Confirm &amp; notify
        </Button>
      </ModalFooter>
    </Modal>
  );
}

export default KvaConfirmModal;
