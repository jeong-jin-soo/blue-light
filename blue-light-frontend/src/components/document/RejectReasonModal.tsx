import { useEffect, useState } from 'react';
import { Button } from '../ui/Button';
import { Modal, ModalBody, ModalFooter, ModalHeader } from '../ui/Modal';
import { Textarea } from '../ui/Textarea';

/**
 * Phase 3 PR#2 — RejectReasonModal (AC-LU4, S3)
 *
 * - min 10자 / max 500자
 * - "신청자에게 그대로 전달됩니다" 가이드 톤(destructive 아님)
 */

const REASON_MIN = 10;
const REASON_MAX = 500;

interface RejectReasonModalProps {
  isOpen: boolean;
  requestId: number | null;
  documentLabel?: string;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<void>;
}

export function RejectReasonModal({
  isOpen,
  requestId,
  documentLabel,
  onClose,
  onSubmit,
}: RejectReasonModalProps) {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 모달이 열릴 때마다 초기화
  useEffect(() => {
    if (isOpen) {
      setReason('');
      setSubmitting(false);
    }
  }, [isOpen]);

  const trimmedLen = reason.trim().length;
  const isValid = trimmedLen >= REASON_MIN && trimmedLen <= REASON_MAX;
  const showLengthHint = reason.length > 0 && trimmedLen < REASON_MIN;

  const handleSubmit = async () => {
    if (!isValid || submitting) return;
    setSubmitting(true);
    try {
      await onSubmit(reason.trim());
      // 성공 시 상위 onClose가 호출된다고 가정 — 안전하게 여기서도 reset
      setReason('');
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
      size="sm"
      closeOnEscape={!submitting}
      closeOnOverlay={!submitting}
      ariaLabelledBy="rr-title"
    >
      <ModalHeader onClose={close}>
        <h3 id="rr-title" className="text-lg font-semibold text-gray-800">
          Reject this upload?
        </h3>
      </ModalHeader>

      <ModalBody>
        {(requestId || documentLabel) && (
          <p className="text-xs text-gray-500 mb-3">
            {documentLabel}
            {requestId ? ` (#${requestId})` : ''}
          </p>
        )}

        <Textarea
          label="Reason (shared with applicant) *"
          rows={4}
          maxLength={REASON_MAX}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={submitting}
          aria-describedby="rr-hint"
          placeholder="e.g., Resolution too low to read the signature. Please rescan at 200dpi or higher."
          error={showLengthHint ? `Minimum ${REASON_MIN} characters` : undefined}
        />
        <p id="rr-hint" className="text-xs text-gray-500 mt-1">
          {reason.length} / {REASON_MAX} · minimum {REASON_MIN} · shared with the applicant as-is.
        </p>
      </ModalBody>

      <ModalFooter>
        <Button variant="outline" size="sm" onClick={close} disabled={submitting}>
          Cancel
        </Button>
        <Button
          size="sm"
          onClick={handleSubmit}
          loading={submitting}
          disabled={!isValid}
          className="bg-error hover:bg-error/90 text-white"
        >
          Reject & Notify
        </Button>
      </ModalFooter>
    </Modal>
  );
}

export default RejectReasonModal;
