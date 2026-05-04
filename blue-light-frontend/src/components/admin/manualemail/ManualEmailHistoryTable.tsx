import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button } from '../../ui/Button';
import { Input } from '../../ui/Input';
import { Select } from '../../ui/Select';
import { Badge } from '../../ui/Badge';
import { LoadingSpinner } from '../../ui/LoadingSpinner';
import { EmptyState } from '../../ui/EmptyState';
import { useAuthStore } from '../../../stores/authStore';
import { getManualEmailHistory } from '../../../api/adminManualEmailApi';
import { ManualEmailHistoryDetailModal } from './ManualEmailHistoryDetailModal';
import { statusBadgeVariant, statusLabel } from './statusBadge';
import type {
  DispatchStatus,
  ManualEmailDispatchHistoryItem,
  ManualEmailHistoryFilter,
} from '../../../types/manualEmail';
import type { Page } from '../../../types';

/**
 * ADMIN 수동 이메일 발송 이력 테이블 (PR-3).
 *
 * <p>스펙: doc/Project Analysis/admin-manual-email-spec.md §7.2.2.</p>
 *
 * <h3>필터</h3>
 * <ul>
 *   <li>"My dispatches only" 토글 — senderUserSeq 를 본인으로 제한.</li>
 *   <li>날짜 범위 (from/to) — datetime-local 입력 → ISO LocalDateTime 변환.</li>
 *   <li>Status (단일 select — multi-select 는 PR-4 와 함께 enhancement).</li>
 *   <li>관련 신청번호.</li>
 * </ul>
 */

const PAGE_SIZE = 20;

const STATUS_OPTIONS: Array<{ value: DispatchStatus | ''; label: string }> = [
  { value: '', label: 'All statuses' },
  { value: 'PENDING', label: 'PENDING' },
  { value: 'SENT', label: 'SENT' },
  { value: 'PARTIAL_FAILED', label: 'PARTIAL_FAILED' },
  { value: 'FAILED', label: 'FAILED' },
];

/** 'YYYY-MM-DDTHH:MM' (datetime-local) → 'YYYY-MM-DDTHH:MM:00' (백엔드 LocalDateTime) */
function toLocalDateTimeIso(local: string): string | undefined {
  if (!local) return undefined;
  // datetime-local 은 'YYYY-MM-DDTHH:MM' (분 단위) 또는 'YYYY-MM-DDTHH:MM:SS'
  return local.length === 16 ? `${local}:00` : local;
}

interface Props {
  /** 외부에서 강제 새로고침 트리거 (예: Compose 직후 History 진입 시). */
  refreshKey?: number;
}

export function ManualEmailHistoryTable({ refreshKey }: Props) {
  const { user } = useAuthStore();
  const [myOnly, setMyOnly] = useState(false);
  const [statusFilter, setStatusFilter] = useState<DispatchStatus | ''>('');
  const [appSeqFilter, setAppSeqFilter] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<ManualEmailDispatchHistoryItem> | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [detailSeq, setDetailSeq] = useState<number | null>(null);

  const filter: ManualEmailHistoryFilter = useMemo(() => {
    const f: ManualEmailHistoryFilter = {};
    if (myOnly && user?.userSeq) f.senderUserSeq = user.userSeq;
    if (statusFilter) f.dispatchStatus = statusFilter;
    if (appSeqFilter.trim()) {
      const n = Number(appSeqFilter.trim());
      if (Number.isFinite(n) && n > 0) f.relatedApplicationSeq = n;
    }
    const fromIso = toLocalDateTimeIso(from);
    const toIso = toLocalDateTimeIso(to);
    if (fromIso) f.from = fromIso;
    if (toIso) f.to = toIso;
    return f;
  }, [myOnly, user?.userSeq, statusFilter, appSeqFilter, from, to]);

  const loadPage = useCallback(
    async (targetPage: number) => {
      setLoading(true);
      setError(null);
      try {
        const result = await getManualEmailHistory(filter, targetPage, PAGE_SIZE);
        setData(result);
      } catch (e) {
        setError((e as { message?: string })?.message || 'Failed to load history');
      } finally {
        setLoading(false);
      }
    },
    [filter]
  );

  // 필터 변경 시 첫 페이지로 재조회. refreshKey 변경(예: Compose 직후)에도 재조회.
  useEffect(() => {
    setPage(0);
    loadPage(0);
  }, [loadPage, refreshKey]);

  const handlePageChange = (next: number) => {
    setPage(next);
    loadPage(next);
  };

  const handleResetFilters = () => {
    setMyOnly(false);
    setStatusFilter('');
    setAppSeqFilter('');
    setFrom('');
    setTo('');
  };

  return (
    <div className="space-y-4">
      {/* Filter bar */}
      <div className="bg-white border border-gray-200 rounded-lg p-3 sm:p-4 space-y-3">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
          <div>
            <label className="block text-xs text-gray-500 mb-1">Status</label>
            <Select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value as DispatchStatus | '')}
              options={STATUS_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">From</label>
            <Input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">To</label>
            <Input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Application #</label>
            <Input
              type="number"
              min={1}
              value={appSeqFilter}
              onChange={(e) => setAppSeqFilter(e.target.value)}
              placeholder="e.g. 1234"
            />
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <label className="inline-flex items-center gap-2 text-sm text-gray-700 cursor-pointer">
            <input
              type="checkbox"
              checked={myOnly}
              onChange={(e) => setMyOnly(e.target.checked)}
              className="text-primary"
            />
            My dispatches only
          </label>
          <Button variant="outline" size="sm" onClick={handleResetFilters}>
            Reset
          </Button>
          <div className="ml-auto text-xs text-gray-500">
            {data ? `${data.totalElements} total` : ' '}
          </div>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        {loading && (
          <div className="flex items-center justify-center py-10 gap-2 text-gray-500">
            <LoadingSpinner /> Loading…
          </div>
        )}
        {error && (
          <div className="p-4 text-sm text-red-700 bg-red-50">{error}</div>
        )}
        {!loading && !error && data && data.empty && (
          <EmptyState title="No manual emails yet" description="Dispatches will appear here." />
        )}
        {!loading && !error && data && !data.empty && (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200 text-sm">
              <thead className="bg-gray-50 text-xs uppercase text-gray-500">
                <tr>
                  <th className="px-3 py-2 text-left">Sent at</th>
                  <th className="px-3 py-2 text-left">Sender</th>
                  <th className="px-3 py-2 text-left">Recipients</th>
                  <th className="px-3 py-2 text-left">Subject</th>
                  <th className="px-3 py-2 text-left">Status</th>
                  <th className="px-3 py-2 text-left">Sent / Failed</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 bg-white">
                {data.content.map((row) => {
                  const firstEmail =
                    row.recipientEmails && row.recipientEmails.length > 0
                      ? row.recipientEmails[0]
                      : row.recipientEmail ?? '(unknown)';
                  const more =
                    row.recipientCount > 1 ? ` +${row.recipientCount - 1} more` : '';
                  return (
                    <tr
                      key={row.dispatchSeq}
                      className="hover:bg-gray-50 cursor-pointer"
                      onClick={() => setDetailSeq(row.dispatchSeq)}
                    >
                      <td className="px-3 py-2 whitespace-nowrap text-gray-700">
                        {row.dispatchedAt ?? row.createdAt}
                      </td>
                      <td className="px-3 py-2 whitespace-nowrap text-gray-700">
                        #{row.senderUserSeq}
                      </td>
                      <td className="px-3 py-2 text-gray-700 max-w-[280px]">
                        <div className="truncate">
                          {firstEmail}
                          {more && <span className="ml-1 text-gray-500">{more}</span>}
                        </div>
                        <div className="text-[11px] text-gray-400">{row.recipientType}</div>
                      </td>
                      <td className="px-3 py-2 text-gray-700 max-w-[260px]">
                        <div className="truncate">{row.subject}</div>
                      </td>
                      <td className="px-3 py-2">
                        <Badge variant={statusBadgeVariant(row.dispatchStatus)}>
                          {statusLabel(row.dispatchStatus)}
                        </Badge>
                      </td>
                      <td className="px-3 py-2 text-gray-700 whitespace-nowrap">
                        {row.sentCount} / {row.failedCount}
                      </td>
                      <td className="px-3 py-2 text-right">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={(e) => {
                            e.stopPropagation();
                            setDetailSeq(row.dispatchSeq);
                          }}
                        >
                          View
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Pagination */}
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => handlePageChange(page - 1)}
            disabled={data.first || loading}
          >
            Previous
          </Button>
          <span className="text-sm text-gray-600">
            Page {data.number + 1} of {data.totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => handlePageChange(page + 1)}
            disabled={data.last || loading}
          >
            Next
          </Button>
        </div>
      )}

      {/* Detail modal */}
      <ManualEmailHistoryDetailModal
        isOpen={detailSeq !== null}
        dispatchSeq={detailSeq}
        onClose={() => setDetailSeq(null)}
      />
    </div>
  );
}
