import type { ApplicationStatus } from '../../types';
import { Badge, type BadgeVariant } from '../ui/Badge';

interface StatusConfig {
  label: string;
  variant: BadgeVariant;
}

const STATUS_CONFIG: Record<ApplicationStatus, StatusConfig> = {
  PENDING_REVIEW:     { label: 'Pending Review',     variant: 'info' },
  REVISION_REQUESTED: { label: 'Revision Requested', variant: 'warning' },
  PENDING_PAYMENT:    { label: 'Pending Payment',    variant: 'warning' },
  PAID:               { label: 'Paid',               variant: 'info' },
  IN_PROGRESS:        { label: 'In Progress',        variant: 'primary' },
  COMPLETED:          { label: 'Completed',           variant: 'success' },
  EXPIRED:            { label: 'Expired',             variant: 'gray' },
};

interface StatusBadgeProps {
  status: ApplicationStatus;
  className?: string;
}

export function StatusBadge({ status, className }: StatusBadgeProps) {
  const config = STATUS_CONFIG[status];
  return (
    <Badge variant={config.variant} dot className={className}>
      {config.label}
    </Badge>
  );
}
