import type { SldOrderStatus } from '../../types';

/**
 * Manager-side order status badge shared by Lighting / PowerSocket / LEW Service Manager pages.
 *
 * SldOrderStatus is reused (LightingOrderStatus / PowerSocketOrderStatus / LewServiceOrderStatus
 * are all aliases of SldOrderStatus). The SLD Manager uses its own inline badge; we intentionally
 * do not refactor it in this PR.
 */

const STATUS_CONFIG: Record<SldOrderStatus, { label: string; color: string }> = {
  PENDING_QUOTE: { label: 'Pending Quote', color: 'bg-blue-100 text-blue-800' },
  QUOTE_PROPOSED: { label: 'Quote Proposed', color: 'bg-yellow-100 text-yellow-800' },
  QUOTE_REJECTED: { label: 'Quote Rejected', color: 'bg-red-100 text-red-800' },
  PENDING_PAYMENT: { label: 'Pending Payment', color: 'bg-orange-100 text-orange-800' },
  PAID: { label: 'Paid', color: 'bg-green-100 text-green-800' },
  IN_PROGRESS: { label: 'In Progress', color: 'bg-blue-100 text-blue-800' },
  SLD_UPLOADED: { label: 'Uploaded', color: 'bg-purple-100 text-purple-800' },
  REVISION_REQUESTED: { label: 'Revision Requested', color: 'bg-orange-100 text-orange-800' },
  COMPLETED: { label: 'Completed', color: 'bg-green-100 text-green-800' },
};

interface Props {
  status: SldOrderStatus;
  className?: string;
}

export function ManagerOrderStatusBadge({ status, className = '' }: Props) {
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
export const MANAGER_ORDER_STATUS_OPTIONS = [
  { value: '', label: 'All Statuses' },
  { value: 'PENDING_QUOTE', label: 'Pending Quote' },
  { value: 'QUOTE_PROPOSED', label: 'Quote Proposed' },
  { value: 'QUOTE_REJECTED', label: 'Quote Rejected' },
  { value: 'PENDING_PAYMENT', label: 'Pending Payment' },
  { value: 'PAID', label: 'Paid' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'SLD_UPLOADED', label: 'Uploaded' },
  { value: 'REVISION_REQUESTED', label: 'Revision Requested' },
  { value: 'COMPLETED', label: 'Completed' },
];
