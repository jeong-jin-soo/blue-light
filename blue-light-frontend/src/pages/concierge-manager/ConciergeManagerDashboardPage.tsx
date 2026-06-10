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
import {
  PhoneCall, FileSignature, CreditCard, Hourglass,
  AlertTriangle, ChevronRight,
} from 'lucide-react';
import { PageHeader } from '../../components/ui/PageHeader';
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

  // 2차 KPI(참고용) — 작은 칩 행으로 강등.
  const chips = [
    { label: 'Awaiting LOA', value: countBy('AWAITING_APPLICANT_LOA_SIGN'), icon: FileSignature },
    { label: 'Awaiting payment', value: countBy('AWAITING_LICENCE_PAYMENT'), icon: CreditCard },
    { label: 'Pending activation', value: pendingActivationCount, icon: Hourglass },
  ];

  return (
    <div className="max-w-7xl mx-auto space-y-6">
      <PageHeader
        title="Concierge Dashboard"
        subtitle="Kaki Concierge request management"
        actions={
          <Button
            variant="concierge"
            onClick={() => navigate('/concierge-manager/requests')}
          >
            View all requests
          </Button>
        }
      />

      {slaBreachCount > 0 && (
        <div
          role="alert"
          className="p-3 rounded-md bg-error-50 border border-error-200 flex items-center gap-3"
        >
          <AlertTriangle aria-hidden="true" className="w-4 h-4 text-error-600 shrink-0" />
          <div className="text-sm text-error-700">
            <strong>{slaBreachCount} request{slaBreachCount !== 1 ? 's' : ''}</strong>{' '}
            exceed the 24h SLA without first contact.
          </div>
        </div>
      )}

      {/* Hero KPI(진행 중 통화) + 2차 KPI 칩 행 */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className="lg:col-span-1 bg-white rounded-xl border-t-[3px] border-accent shadow-dropdown p-6">
          <div className="flex items-center gap-2 text-accent-600 mb-3">
            <PhoneCall className="w-5 h-5" />
            <span className="text-sm font-semibold">In contact</span>
          </div>
          <div className="text-5xl font-bold text-gray-900 tabular-nums leading-none">{countBy('CONTACTING')}</div>
          <div className="mt-3 text-sm text-gray-500">Active first-contact requests</div>
        </div>

        <div className="lg:col-span-2 grid grid-cols-1 sm:grid-cols-3 gap-3 content-start">
          {chips.map(({ label, value, icon: Icon }) => (
            <div
              key={label}
              className="flex items-center gap-3 bg-white rounded-lg border border-primary-100 px-4 py-3"
            >
              <Icon className="w-4 h-4 text-gray-400 shrink-0" />
              <div className="min-w-0">
                <div className="text-lg font-semibold text-gray-900 tabular-nums leading-none">{value}</div>
                <div className="text-xs text-gray-500 truncate">{label}</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* 최근 요청 — 주인공 */}
      <div className="bg-white rounded-xl border border-primary-100 shadow-dropdown overflow-hidden">
        <div className="px-5 py-4 border-b border-primary-100 flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">Recent requests</h2>
          <span className="text-xs text-gray-500">Showing latest {recent.length}</span>
        </div>
        {loading ? (
          <p className="p-4 text-sm text-gray-500">Loading...</p>
        ) : error ? (
          <p className="p-4 text-sm text-error-700">{error}</p>
        ) : recent.length === 0 ? (
          <p className="p-4 text-sm text-gray-500">No requests yet.</p>
        ) : (
          <ul className="divide-y divide-gray-50">
            {recent.map((r) => (
              <li
                key={r.conciergeRequestSeq}
                className="px-5 py-3 hover:bg-surface-secondary cursor-pointer focus-within:bg-surface-secondary transition-colors"
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
                <div className="flex items-center justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="font-mono text-xs text-gray-500">
                      {r.publicCode}
                    </div>
                    <div className="font-semibold text-gray-900 truncate">
                      {r.submitterName}
                    </div>
                    <div className="text-xs text-gray-400 truncate">
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
                  <ChevronRight className="w-4 h-4 text-gray-300 shrink-0" />
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
