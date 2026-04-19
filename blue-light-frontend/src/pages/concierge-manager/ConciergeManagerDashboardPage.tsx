/**
 * ConciergeManagerDashboardPage
 * - Kaki Concierge v1.5 Phase 1 PR#4 Stage B
 * - /concierge-manager/dashboard
 * - KPI 카드 4종 + SLA 위반 경고 + 최근 10건 리스트.
 * - count 엔드포인트 없이, Backend가 담당자별/ADMIN 전체를 자동 필터하므로
 *   size=100으로 한 번 호출 후 FE에서 status별 집계.
 */

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DashboardCard } from '../../components/domain/DashboardCard';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { ConciergeStatusBadge } from '../../components/concierge/ConciergeStatusBadge';
import conciergeManagerApi, {
  type ConciergeRequestSummary,
  type ConciergeStatus,
} from '../../api/conciergeManagerApi';

export default function ConciergeManagerDashboardPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<ConciergeRequestSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        setLoading(true);
        const page = await conciergeManagerApi.list({ size: 100, page: 0 });
        if (!cancelled) {
          setItems(page.content);
        }
      } catch (err) {
        if (cancelled) return;
        const msg =
          err && typeof err === 'object' && 'message' in err
            ? String((err as { message: unknown }).message)
            : 'Failed to load requests';
        setError(msg);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const countBy = (status: ConciergeStatus) =>
    items.filter((i) => i.status === status).length;
  const slaBreachCount = items.filter((i) => i.slaBreached).length;
  const pendingActivationCount = items.filter(
    (i) => i.applicantUserStatus === 'PENDING_ACTIVATION'
  ).length;
  const recent = items.slice(0, 10);

  return (
    <div className="max-w-6xl mx-auto">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Concierge Dashboard</h1>
          <p className="text-sm text-gray-600 mt-1">
            Kaki Concierge request management
          </p>
        </div>
        <Button
          variant="concierge"
          onClick={() => navigate('/concierge-manager/requests')}
        >
          View all requests
        </Button>
      </div>

      {slaBreachCount > 0 && (
        <div
          role="alert"
          className="mb-4 p-3 rounded-md bg-error-50 border border-error-200 flex items-center gap-3"
        >
          <span aria-hidden="true" className="text-error-600">
            ⚠
          </span>
          <div className="text-sm text-error-700">
            <strong>{slaBreachCount} request{slaBreachCount !== 1 ? 's' : ''}</strong>{' '}
            exceed the 24h SLA without first contact.
          </div>
        </div>
      )}

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 sm:gap-4 mb-6">
        <DashboardCard label="In contact" value={countBy('CONTACTING')} icon="📞" />
        <DashboardCard
          label="Awaiting LOA"
          value={countBy('AWAITING_APPLICANT_LOA_SIGN')}
          icon="✍️"
        />
        <DashboardCard
          label="Awaiting payment"
          value={countBy('AWAITING_LICENCE_PAYMENT')}
          icon="💳"
        />
        <DashboardCard
          label="Pending activation"
          value={pendingActivationCount}
          icon="⏳"
        />
      </div>

      <div className="bg-surface rounded-xl shadow-card overflow-hidden">
        <div className="px-4 py-3 border-b border-gray-200 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-gray-800">Recent requests</h2>
          <span className="text-xs text-gray-500">Showing latest {recent.length}</span>
        </div>
        {loading ? (
          <p className="p-4 text-sm text-gray-500">Loading...</p>
        ) : error ? (
          <p className="p-4 text-sm text-error-700">{error}</p>
        ) : recent.length === 0 ? (
          <p className="p-4 text-sm text-gray-500">No requests yet.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {recent.map((r) => (
              <li
                key={r.conciergeRequestSeq}
                className="px-4 py-3 hover:bg-gray-50 cursor-pointer focus-within:bg-gray-50"
                onClick={() =>
                  navigate(`/concierge-manager/requests/${r.conciergeRequestSeq}`)
                }
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    navigate(
                      `/concierge-manager/requests/${r.conciergeRequestSeq}`
                    );
                  }
                }}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="font-mono text-xs text-gray-500">
                      {r.publicCode}
                    </div>
                    <div className="font-medium text-gray-900 truncate">
                      {r.submitterName}
                    </div>
                    <div className="text-xs text-gray-500 truncate">
                      {r.submitterEmail}
                    </div>
                  </div>
                  <div className="flex flex-col items-end gap-1 flex-shrink-0">
                    <ConciergeStatusBadge status={r.status} />
                    {r.slaBreached && <Badge variant="error">SLA Breach</Badge>}
                    {r.applicantUserStatus === 'PENDING_ACTIVATION' && (
                      <Badge variant="warning">Pending activation</Badge>
                    )}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
