import { useEffect, useMemo, useState } from 'react';
import { Card } from '../ui/Card';
import { Badge } from '../ui/Badge';
import { LoadingSpinner } from '../ui/LoadingSpinner';
import {
  getApplicationActivity,
  type ApplicationActivityItem,
} from '../../api/adminApplicationApi';
import { getActivityMeta } from '../../constants/activityLabels';
import { ROLE_LABELS } from '../../constants/roles';
import type { UserRole } from '../../constants/roles';

interface Props {
  applicationSeq: number;
  /** Trigger re-fetch after actions such as manual-payment / status change. */
  refreshKey?: number;
}

/** Actor display — "System · Automatic" for system events, otherwise role label + email. */
function actorLabel(item: ApplicationActivityItem): { name: string; role: string } {
  if (item.system) return { name: 'System', role: 'Automatic' };
  const roleLabel = item.actorRole
    ? ROLE_LABELS[item.actorRole as UserRole] ?? item.actorRole
    : 'Unknown';
  return { name: item.actorEmail ?? 'Unknown', role: roleLabel };
}

/** 카테고리/시스템/실패에 따른 점(dot) 색상. */
function dotClass(item: ApplicationActivityItem): string {
  if (item.httpStatus != null && item.httpStatus >= 400) return 'bg-error-500';
  if (item.system) return 'bg-info-500';
  switch (item.actionCategory) {
    case 'ADMIN':
      return 'bg-primary-600';
    case 'APPLICATION':
      return 'bg-success-500';
    case 'AUTH':
      return 'bg-gray-400';
    default:
      return 'bg-gray-400';
  }
}

/** before/after JSON 을 가독 가능한 형태로. 단순 스칼라면 그대로, 객체면 pretty JSON. */
function prettyValue(raw: string | null): string | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return trimmed;
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2);
  } catch {
    return trimmed;
  }
}

export function AdminActivityTimelineSection({ applicationSeq, refreshKey = 0 }: Props) {
  const [items, setItems] = useState<ApplicationActivityItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [newestFirst, setNewestFirst] = useState(true);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    getApplicationActivity(applicationSeq)
      .then((data) => {
        if (!cancelled) setItems(data);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [applicationSeq, refreshKey]);

  // 백엔드는 시간 오름차순 반환. 표시 방향만 토글.
  const ordered = useMemo(
    () => (newestFirst ? [...items].slice().reverse() : items),
    [items, newestFirst]
  );

  const toggleExpand = (seq: number) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(seq)) next.delete(seq);
      else next.add(seq);
      return next;
    });
  };

  return (
    <Card>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h2 className="text-lg font-semibold text-gray-800">Activity Timeline</h2>
          <p className="text-sm text-gray-500 mt-0.5">
            Who did what and when, plus automated actions
          </p>
        </div>
        {items.length > 0 && (
          <button
            type="button"
            onClick={() => setNewestFirst((v) => !v)}
            className="text-xs text-gray-500 hover:text-gray-700 px-2 py-1 rounded-md hover:bg-gray-100 transition-colors"
          >
            {newestFirst ? 'Newest first ▾' : 'Chronological ▴'}
          </button>
        )}
      </div>

      {loading ? (
        <div className="py-8 flex justify-center">
          <LoadingSpinner size="sm" label="Loading activity..." />
        </div>
      ) : error ? (
        <p className="text-sm text-error-600 py-4">Failed to load activity.</p>
      ) : ordered.length === 0 ? (
        <p className="text-sm text-gray-500 py-4">No recorded activity.</p>
      ) : (
        <ol className="relative border-l border-gray-200 ml-2">
          {ordered.map((item) => {
            const meta = getActivityMeta(item.action);
            const actor = actorLabel(item);
            const before = prettyValue(item.beforeValue);
            const after = prettyValue(item.afterValue);
            const hasDetail = !!before || !!after;
            const isFailure = item.httpStatus != null && item.httpStatus >= 400;
            const isOpen = expanded.has(item.auditLogSeq);
            return (
              <li key={item.auditLogSeq} className="mb-5 ml-5">
                <span
                  className={`absolute -left-[7px] mt-1.5 w-3.5 h-3.5 rounded-full ring-4 ring-surface ${dotClass(item)}`}
                  aria-hidden
                />
                <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span className="text-base leading-none" aria-hidden>
                    {meta.icon}
                  </span>
                  <span className="text-sm font-medium text-gray-800">{meta.label}</span>
                  {item.system && (
                    <Badge variant="info" dot>
                      Auto
                    </Badge>
                  )}
                  {isFailure && <Badge variant="error">Failed</Badge>}
                </div>

                <div className="mt-1 text-xs text-gray-500 flex flex-wrap items-center gap-x-2">
                  <span className="font-medium text-gray-600">{actor.role}</span>
                  {!item.system && <span className="text-gray-400">·</span>}
                  {!item.system && <span>{actor.name}</span>}
                  <span className="text-gray-400">·</span>
                  <time dateTime={item.occurredAt}>
                    {new Date(item.occurredAt).toLocaleString()}
                  </time>
                </div>

                {item.description && (
                  <p className="mt-1 text-sm text-gray-600 whitespace-pre-wrap">
                    {item.description}
                  </p>
                )}

                {hasDetail && (
                  <div className="mt-1">
                    <button
                      type="button"
                      onClick={() => toggleExpand(item.auditLogSeq)}
                      className="text-xs text-primary-600 hover:text-primary-800"
                    >
                      {isOpen ? 'Hide details' : 'View change details'}
                    </button>
                    {isOpen && (
                      <div className="mt-2 grid grid-cols-1 sm:grid-cols-2 gap-2">
                        {before && (
                          <div>
                            <p className="text-[11px] font-medium text-gray-400 mb-0.5">Before</p>
                            <pre className="text-xs bg-gray-50 rounded-md p-2 overflow-x-auto whitespace-pre-wrap break-all text-gray-600">
                              {before}
                            </pre>
                          </div>
                        )}
                        {after && (
                          <div>
                            <p className="text-[11px] font-medium text-gray-400 mb-0.5">After</p>
                            <pre className="text-xs bg-gray-50 rounded-md p-2 overflow-x-auto whitespace-pre-wrap break-all text-gray-600">
                              {after}
                            </pre>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      )}
    </Card>
  );
}
