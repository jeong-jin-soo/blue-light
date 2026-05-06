/**
 * ConciergeTimeline
 * - Kaki Concierge v1.5 Phase 1 PR#4 Stage B
 * - 상세 페이지 좌측 타임라인 (진입 → 취소/완료까지 주요 마일스톤)
 */

import type { ConciergeRequestDetail } from '../../../api/conciergeManagerApi';

interface Props {
  detail: ConciergeRequestDetail;
}

interface Milestone {
  label: string;
  at: string | null;
  done: boolean;
}

function fmt(at: string | null): string {
  if (!at) return '';
  try {
    return new Date(at).toLocaleString();
  } catch {
    return at;
  }
}

export function ConciergeTimeline({ detail }: Props) {
  const milestones: Milestone[] = [
    { label: 'Submitted', at: detail.createdAt, done: !!detail.createdAt },
    { label: 'Assigned', at: detail.assignedAt, done: !!detail.assignedAt },
    { label: 'First contact', at: detail.firstContactAt, done: !!detail.firstContactAt },
    { label: 'Application created', at: detail.applicationCreatedAt, done: !!detail.applicationCreatedAt },
    { label: 'LOA requested', at: detail.loaRequestedAt, done: !!detail.loaRequestedAt },
    { label: 'LOA signed', at: detail.loaSignedAt, done: !!detail.loaSignedAt },
    { label: 'Licence paid', at: detail.licencePaidAt, done: !!detail.licencePaidAt },
    { label: 'Completed', at: detail.completedAt, done: !!detail.completedAt },
  ];

  const showCancelled = !!detail.cancelledAt;

  return (
    <ol className="relative border-s border-gray-200 ml-2 space-y-4 py-1">
      {milestones.map((m) => (
        <li key={m.label} className="ms-4">
          <span
            className={`absolute -start-1.5 flex h-3 w-3 rounded-full border-2 ${
              m.done
                ? 'bg-concierge-500 border-concierge-500'
                : 'bg-white border-gray-300'
            }`}
            aria-hidden="true"
          />
          <div className="text-sm">
            <span
              className={`font-medium ${
                m.done ? 'text-gray-900' : 'text-gray-400'
              }`}
            >
              {m.label}
            </span>
            {m.at && (
              <span className="ml-2 text-xs text-gray-500">{fmt(m.at)}</span>
            )}
          </div>
        </li>
      ))}
      {showCancelled && (
        <li className="ms-4">
          <span
            className="absolute -start-1.5 flex h-3 w-3 rounded-full border-2 bg-error-500 border-error-500"
            aria-hidden="true"
          />
          <div className="text-sm">
            <span className="font-medium text-error-700">Cancelled</span>
            <span className="ml-2 text-xs text-gray-500">{fmt(detail.cancelledAt)}</span>
            {detail.cancellationReason && (
              <p className="mt-1 text-xs text-gray-600">
                Reason: {detail.cancellationReason}
              </p>
            )}
          </div>
        </li>
      )}
    </ol>
  );
}

export default ConciergeTimeline;
