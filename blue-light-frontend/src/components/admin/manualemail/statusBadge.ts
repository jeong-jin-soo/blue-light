import type { BadgeVariant } from '../../ui/Badge';
import type { DispatchStatus } from '../../../types/manualEmail';

/** 발송 상태 → Badge variant 매핑. */
export function statusBadgeVariant(status: DispatchStatus): BadgeVariant {
  switch (status) {
    case 'SENT':
      return 'success';
    case 'PARTIAL_FAILED':
      return 'warning';
    case 'FAILED':
      return 'error';
    case 'PENDING':
    default:
      return 'gray';
  }
}

/** 발송 상태 → 라벨 (현재는 enum 그대로, 향후 i18n 가능). */
export function statusLabel(status: DispatchStatus): string {
  return status;
}
