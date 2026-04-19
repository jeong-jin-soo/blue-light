/**
 * ConciergeAccountStatusPanel
 * - Kaki Concierge v1.5 Phase 1 PR#4 Stage B
 * - 신청자(User) 활성화 상태 요약 + PENDING_ACTIVATION 시 "Resend link" 버튼
 */

import { useState } from 'react';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import type { ApplicantStatusInfo } from '../../../api/conciergeManagerApi';

interface Props {
  applicantStatus: ApplicantStatusInfo | null;
  onResend: () => Promise<void>;
  disabled?: boolean;
}

function fmt(at: string | null): string {
  if (!at) return '—';
  try {
    return new Date(at).toLocaleString();
  } catch {
    return at;
  }
}

export function ConciergeAccountStatusPanel({ applicantStatus, onResend, disabled }: Props) {
  const [resending, setResending] = useState(false);
  const [resendMessage, setResendMessage] = useState<string | null>(null);
  const [resendError, setResendError] = useState<string | null>(null);

  if (!applicantStatus) {
    return <p className="text-sm text-gray-500">No applicant linked.</p>;
  }

  const { userStatus, emailVerified, activatedAt, firstLoggedInAt,
          hasActiveSetupToken, setupTokenExpiresAt } = applicantStatus;

  const isPending = userStatus === 'PENDING_ACTIVATION';

  const handleResend = async () => {
    setResending(true);
    setResendMessage(null);
    setResendError(null);
    try {
      await onResend();
      setResendMessage('Activation link sent to applicant.');
    } catch (err) {
      const msg =
        err && typeof err === 'object' && 'message' in err
          ? String((err as { message: unknown }).message)
          : 'Failed to resend activation link';
      setResendError(msg);
    } finally {
      setResending(false);
    }
  };

  const statusBadge = () => {
    if (userStatus === 'ACTIVE') return <Badge variant="success">Active</Badge>;
    if (userStatus === 'PENDING_ACTIVATION') return <Badge variant="warning">Pending activation</Badge>;
    if (userStatus === 'SUSPENDED') return <Badge variant="error">Suspended</Badge>;
    return <Badge variant="gray">Deleted</Badge>;
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-gray-700">Account status</span>
        {statusBadge()}
      </div>

      <dl className="text-sm space-y-1.5">
        <div className="flex justify-between">
          <dt className="text-gray-500">Email verified</dt>
          <dd className="text-gray-900">{emailVerified ? 'Yes' : 'No'}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-gray-500">Activated at</dt>
          <dd className="text-gray-900">{fmt(activatedAt)}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-gray-500">First logged in</dt>
          <dd className="text-gray-900">{fmt(firstLoggedInAt)}</dd>
        </div>
        {isPending && (
          <div className="flex justify-between">
            <dt className="text-gray-500">Setup token</dt>
            <dd className="text-gray-900">
              {hasActiveSetupToken
                ? `Active (expires ${fmt(setupTokenExpiresAt)})`
                : 'None'}
            </dd>
          </div>
        )}
      </dl>

      {isPending && (
        <div className="pt-2 border-t border-gray-200">
          {resendMessage ? (
            <p className="text-sm text-success-700 bg-success-50 border border-success-100 rounded p-2">
              {resendMessage}
            </p>
          ) : (
            <>
              <p className="text-xs text-gray-600 mb-2">
                Applicant hasn&apos;t activated their account yet. Send a fresh activation link.
              </p>
              <Button
                variant="concierge"
                size="sm"
                onClick={handleResend}
                loading={resending}
                disabled={disabled || resending}
              >
                Resend activation link
              </Button>
              {resendError && (
                <p className="mt-2 text-xs text-error-700">{resendError}</p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

export default ConciergeAccountStatusPanel;
