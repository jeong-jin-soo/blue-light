/**
 * ConciergeStatusBadge
 * - Kaki Concierge v1.5 Phase 1 PR#3 Stage A
 * - ConciergeRequestStatus 9종(CANCELLED 포함)을 Badge로 시각화
 * - AWAITING_* 상태는 animate-pulse 닷으로 이중 채널 표현(색각 대응)
 */

import type { BadgeVariant } from '../ui/Badge';
import { Badge } from '../ui/Badge';

export type ConciergeStatus =
  | 'SUBMITTED'
  | 'ASSIGNED'
  | 'CONTACTING'
  | 'QUOTE_SENT'
  | 'APPLICATION_CREATED'
  | 'AWAITING_APPLICANT_LOA_SIGN'
  | 'AWAITING_LICENCE_PAYMENT'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED';

interface StatusConfig {
  label: string;
  variant: BadgeVariant;
  /** AWAITING_* 는 주목도 필요 — 작은 pulse dot 추가 */
  pulse?: boolean;
}

const STATUS_MAP: Record<ConciergeStatus, StatusConfig> = {
  SUBMITTED:                   { label: 'Submitted',         variant: 'info' },
  ASSIGNED:                    { label: 'Assigned',          variant: 'info' },
  CONTACTING:                  { label: 'Contacting',        variant: 'info' },
  QUOTE_SENT:                  { label: 'Quote sent',        variant: 'info' },
  APPLICATION_CREATED:         { label: 'Application ready', variant: 'info' },
  AWAITING_APPLICANT_LOA_SIGN: { label: 'Awaiting LOA',      variant: 'warning', pulse: true },
  AWAITING_LICENCE_PAYMENT:    { label: 'Awaiting payment',  variant: 'warning', pulse: true },
  IN_PROGRESS:                 { label: 'In progress',       variant: 'info' },
  COMPLETED:                   { label: 'Completed',         variant: 'success' },
  CANCELLED:                   { label: 'Cancelled',         variant: 'gray' },
};

interface Props {
  status: ConciergeStatus;
  className?: string;
}

export function ConciergeStatusBadge({ status, className }: Props) {
  const cfg = STATUS_MAP[status];
  return (
    <Badge variant={cfg.variant} className={className}>
      {cfg.pulse && (
        <span
          className="inline-block w-1.5 h-1.5 bg-current rounded-full mr-1.5 animate-pulse"
          aria-hidden="true"
        />
      )}
      {cfg.label}
    </Badge>
  );
}
