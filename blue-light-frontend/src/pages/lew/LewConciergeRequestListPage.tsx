/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-4 — LEW 컨시어지 목록 페이지.
 *
 * <p>스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §14 PR-4 D.</p>
 *
 * <p>경로: {@code /lew/concierge-requests}. LEW 본인에게 배정된 ConciergeRequest 만 표시.</p>
 *
 * <h3>API 동작</h3>
 * <p>백엔드 {@code ConciergeManagerService.listForActor} 가 actor.role==LEW 이면서 매니저/ADMIN
 * 권한이 없으면 자동으로 {@code findByAssignedLewSeq(actor.userSeq)} 분기로 라우팅한다 (PR-3).
 * 따라서 LEW 단독 사용자는 같은 GET 엔드포인트를 호출해도 본인 배정만 받는다.</p>
 *
 * <p>LEW + CONCIERGE_MANAGER 다중 역할인 경우는 매니저 우선 — 매니저 워크스페이스 사용 권장.</p>
 */

import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Badge } from '../../components/ui/Badge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { Pagination } from '../../components/data/Pagination';
import { ConciergeStatusBadge } from '../../components/concierge/ConciergeStatusBadge';
import conciergeManagerApi, {
  type ConciergeRequestSummary,
} from '../../api/conciergeManagerApi';

const PAGE_SIZE = 20;

function fmtDate(at: string): string {
  try {
    return new Date(at).toLocaleDateString();
  } catch {
    return at;
  }
}

export default function LewConciergeRequestListPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<ConciergeRequestSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      // 백엔드가 LEW 사용자에 대해 자동으로 본인 배정 필터링 — 별도 query param 불요.
      const res = await conciergeManagerApi.list({
        page,
        size: PAGE_SIZE,
      });
      setItems(res.content);
      setTotalPages(res.totalPages);
    } catch (err) {
      const msg = err && typeof err === 'object' && 'message' in err
        ? String((err as { message: unknown }).message)
        : 'Failed to load concierge requests';
      setError(msg);
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="max-w-5xl mx-auto">
      <div className="mb-4">
        <h1 className="text-2xl font-bold text-gray-900">My Concierge Requests</h1>
        <p className="text-sm text-gray-600 mt-1">
          Concierge requests assigned to you. Use the detail page to create the application
          on behalf of the applicant.
        </p>
      </div>

      {error && (
        <div role="alert" className="mb-4 p-3 rounded-md bg-error-50 border border-error-200 text-sm text-error-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center h-40">
          <LoadingSpinner size="lg" label="Loading..." />
        </div>
      ) : items.length === 0 ? (
        <Card>
          <div className="text-center py-12">
            <p className="text-sm text-gray-500">No concierge requests assigned to you yet.</p>
            <p className="text-xs text-gray-400 mt-1">
              A concierge manager will assign requests to you when applicable.
            </p>
          </div>
        </Card>
      ) : (
        <div className="space-y-2">
          {items.map((r) => (
            <button
              key={r.conciergeRequestSeq}
              type="button"
              onClick={() => navigate(`/lew/concierge-requests/${r.conciergeRequestSeq}`)}
              className="w-full text-left p-4 rounded-lg border border-gray-200 bg-white hover:bg-gray-50 transition-colors cursor-pointer"
            >
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="text-xs font-mono text-gray-500">{r.publicCode}</div>
                  <div className="text-sm font-medium text-gray-900 truncate">{r.submitterName}</div>
                  <div className="text-xs text-gray-500 truncate">{r.submitterEmail}</div>
                  {r.submitterPhone && (
                    <div className="text-xs text-gray-500">{r.submitterPhone}</div>
                  )}
                </div>
                <div className="flex flex-col items-end gap-1 flex-shrink-0">
                  <ConciergeStatusBadge status={r.status} />
                  {r.slaBreached && <Badge variant="error">SLA Breach</Badge>}
                  <div className="text-xs text-gray-500 mt-0.5">{fmtDate(r.createdAt)}</div>
                </div>
              </div>
            </button>
          ))}
        </div>
      )}

      <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}
