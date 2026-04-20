/**
 * ConciergeActionBar
 * - Kaki Concierge v1.5 Phase 1 PR#4 Stage B
 * - 현재 status에 따라 가능한 전이 버튼을 렌더링 + Cancel 버튼 (reason textarea 포함 모달).
 * - APPLICATION_CREATED 전이는 PR#5(on-behalf Application) 엔드포인트 전용 — 이 바에서 비활성 + 툴팁.
 */

import { useState } from 'react';
import { Button } from '../../../components/ui/Button';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../../components/ui/Modal';
import type {
  ConciergeRequestDetail,
  ConciergeStatus,
} from '../../../api/conciergeManagerApi';

interface Props {
  detail: ConciergeRequestDetail;
  onTransition: (nextStatus: ConciergeStatus) => Promise<void>;
  onCancel: (reason: string) => Promise<void>;
  /** CONTACTING 상태에서 "Create application on behalf" 클릭 시 호출 (★ PR#5 Stage B) */
  onCreateApplication?: () => void;
  disabled?: boolean;
}

type ActionKind = 'transition' | 'createApplication' | 'blocked';

interface ActionDef {
  label: string;
  kind: ActionKind;
  /** kind='transition'인 경우의 target status */
  nextStatus?: ConciergeStatus;
  /** kind='blocked'인 경우의 툴팁 안내 */
  disabledReason?: string;
}

function actionsFor(status: ConciergeStatus): ActionDef[] {
  switch (status) {
    case 'SUBMITTED':
      return [{ label: 'Assign to me', kind: 'transition', nextStatus: 'ASSIGNED' }];
    case 'ASSIGNED':
      return [{ label: 'Mark as contacting', kind: 'transition', nextStatus: 'CONTACTING' }];
    case 'CONTACTING':
      // ★ PR#5 Stage B: 활성화 — onCreateApplication 핸들러 연결
      return [
        {
          label: 'Create application on behalf',
          kind: 'createApplication',
        },
      ];
    case 'APPLICATION_CREATED':
      return [{ label: 'Request LOA signing', kind: 'transition', nextStatus: 'AWAITING_APPLICANT_LOA_SIGN' }];
    case 'AWAITING_APPLICANT_LOA_SIGN':
      return [
        {
          label: 'Awaiting applicant LOA signature',
          kind: 'blocked',
          disabledReason: 'Applicant must sign the LOA before continuing.',
        },
      ];
    case 'AWAITING_LICENCE_PAYMENT':
      return [
        {
          label: 'Awaiting applicant payment',
          kind: 'blocked',
          disabledReason: 'Applicant must pay the licence fee before continuing.',
        },
      ];
    case 'IN_PROGRESS':
      return [{ label: 'Mark completed', kind: 'transition', nextStatus: 'COMPLETED' }];
    case 'COMPLETED':
    case 'CANCELLED':
      return [];
  }
}

export function ConciergeActionBar({ detail, onTransition, onCancel, onCreateApplication, disabled }: Props) {
  const [transitioning, setTransitioning] = useState(false);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const actions = actionsFor(detail.status);
  const isTerminal = detail.status === 'COMPLETED' || detail.status === 'CANCELLED';

  const handleTransition = async (next: ConciergeStatus) => {
    setTransitioning(true);
    setActionError(null);
    try {
      await onTransition(next);
    } catch (err) {
      const msg =
        err && typeof err === 'object' && 'message' in err
          ? String((err as { message: unknown }).message)
          : 'Action failed';
      setActionError(msg);
    } finally {
      setTransitioning(false);
    }
  };

  const closeCancelDialog = () => {
    if (cancelling) return;
    setCancelDialogOpen(false);
    setCancelReason('');
    setCancelError(null);
  };

  const handleCancelConfirm = async () => {
    if (!cancelReason.trim()) return;
    setCancelling(true);
    setCancelError(null);
    try {
      await onCancel(cancelReason.trim());
      setCancelDialogOpen(false);
      setCancelReason('');
    } catch (err) {
      const msg =
        err && typeof err === 'object' && 'message' in err
          ? String((err as { message: unknown }).message)
          : 'Cancel failed';
      setCancelError(msg);
    } finally {
      setCancelling(false);
    }
  };

  return (
    <div className="space-y-3">
      {actionError && (
        <div
          role="alert"
          className="p-2 rounded bg-error-50 border border-error-200 text-xs text-error-700"
        >
          {actionError}
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        {actions.map((a) => {
          if (a.kind === 'transition' && a.nextStatus) {
            const next = a.nextStatus;
            return (
              <Button
                key={a.label}
                variant="concierge"
                size="sm"
                onClick={() => handleTransition(next)}
                disabled={disabled || transitioning}
                loading={transitioning}
              >
                {a.label}
              </Button>
            );
          }
          if (a.kind === 'createApplication') {
            return (
              <Button
                key={a.label}
                variant="concierge"
                size="sm"
                onClick={() => onCreateApplication?.()}
                disabled={disabled || transitioning || !onCreateApplication}
                title={
                  !onCreateApplication
                    ? 'Create application handler not provided'
                    : undefined
                }
              >
                {a.label}
              </Button>
            );
          }
          // kind='blocked'
          return (
            <Button
              key={a.label}
              variant="outline"
              size="sm"
              disabled
              title={a.disabledReason}
            >
              {a.label}
            </Button>
          );
        })}

        {!isTerminal && (
          <Button
            variant="danger"
            size="sm"
            onClick={() => setCancelDialogOpen(true)}
            disabled={disabled || transitioning}
          >
            Cancel request
          </Button>
        )}
      </div>

      <Modal isOpen={cancelDialogOpen} onClose={closeCancelDialog} size="sm">
        <ModalHeader title="Cancel concierge request?" onClose={closeCancelDialog} />
        <ModalBody>
          <p className="text-sm text-gray-600 mb-3">
            This will mark the request as cancelled. The applicant may be notified.
            Please provide a reason below.
          </p>
          <label htmlFor="cancel-reason" className="block text-sm font-medium text-gray-700 mb-1.5">
            Reason <span className="text-error-500">*</span>
          </label>
          <textarea
            id="cancel-reason"
            value={cancelReason}
            onChange={(e) => setCancelReason(e.target.value)}
            rows={3}
            maxLength={500}
            disabled={cancelling}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
            placeholder="e.g., Applicant requested cancellation."
          />
          <p className="mt-1 text-xs text-gray-500">{cancelReason.length}/500</p>
          {cancelError && (
            <p className="mt-2 text-xs text-error-700" role="alert">{cancelError}</p>
          )}
        </ModalBody>
        <ModalFooter>
          <Button variant="outline" size="sm" onClick={closeCancelDialog} disabled={cancelling}>
            Keep open
          </Button>
          <Button
            variant="danger"
            size="sm"
            onClick={handleCancelConfirm}
            loading={cancelling}
            disabled={!cancelReason.trim() || cancelling}
          >
            Cancel request
          </Button>
        </ModalFooter>
      </Modal>
    </div>
  );
}

export default ConciergeActionBar;
