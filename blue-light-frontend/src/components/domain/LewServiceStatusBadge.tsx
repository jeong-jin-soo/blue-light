import type { LewServiceOrderStatus } from '../../types';

/**
 * LEW Service Manager / Applicant 공용 상태 뱃지 (PR 3).
 * <p>LewServiceOrderStatus 는 PR 3 에서 SldOrderStatus 와 분리되어 독자 enum 이 되었다.
 */

const STATUS_CONFIG: Record<LewServiceOrderStatus, { label: string; color: string }> = {
  PENDING_QUOTE: { label: 'Pending Quote', color: 'bg-blue-100 text-blue-800' },
  QUOTE_PROPOSED: { label: 'Quote Proposed', color: 'bg-yellow-100 text-yellow-800' },
  QUOTE_REJECTED: { label: 'Quote Rejected', color: 'bg-red-100 text-red-800' },
  PENDING_PAYMENT: { label: 'Pending Payment', color: 'bg-orange-100 text-orange-800' },
  PAID: { label: 'Paid', color: 'bg-green-100 text-green-800' },
  VISIT_SCHEDULED: { label: 'Visit Scheduled', color: 'bg-blue-100 text-blue-800' },
  VISIT_COMPLETED: { label: 'Report Ready for Review', color: 'bg-purple-100 text-purple-800' },
  REVISIT_REQUESTED: { label: 'Revisit Requested', color: 'bg-orange-100 text-orange-800' },
  COMPLETED: { label: 'Completed', color: 'bg-green-100 text-green-800' },
};

interface Props {
  status: LewServiceOrderStatus;
  className?: string;
}

export function LewServiceStatusBadge({ status, className = '' }: Props) {
  const config = STATUS_CONFIG[status] || { label: status, color: 'bg-gray-100 text-gray-800' };
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${config.color} ${className}`}>
      {config.label}
    </span>
  );
}

/**
 * Status options for list filter dropdowns.
 */
export const LEW_SERVICE_STATUS_OPTIONS = [
  { value: '', label: 'All Statuses' },
  { value: 'PENDING_QUOTE', label: 'Pending Quote' },
  { value: 'QUOTE_PROPOSED', label: 'Quote Proposed' },
  { value: 'QUOTE_REJECTED', label: 'Quote Rejected' },
  { value: 'PENDING_PAYMENT', label: 'Pending Payment' },
  { value: 'PAID', label: 'Paid' },
  { value: 'VISIT_SCHEDULED', label: 'Visit Scheduled' },
  { value: 'VISIT_COMPLETED', label: 'Report Ready for Review' },
  { value: 'REVISIT_REQUESTED', label: 'Revisit Requested' },
  { value: 'COMPLETED', label: 'Completed' },
];
