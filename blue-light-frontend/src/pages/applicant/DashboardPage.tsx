import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Files, Search, CreditCard, RefreshCw, BadgeCheck, ChevronRight, Plus } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { PageHeader } from '../../components/ui/PageHeader';
import { EmptyState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useAuthStore } from '../../stores/authStore';
import { useToastStore } from '../../stores/toastStore';
import applicationApi from '../../api/applicationApi';
import { usePendingDocumentCounts } from '../../hooks/usePendingDocumentCounts';
import { KvaPendingBadge } from '../../components/applicant/KvaPendingBadge';
import type { Application, ApplicationSummary } from '../../types';

function PendingDocsBadge({ count }: { count: number }) {
  if (count <= 0) return null;
  return (
    <span
      className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-semibold text-warning-800 bg-warning-50 border border-warning-500/40 rounded-full"
      title="Awaiting requested documents"
    >
      {count} awaiting
    </span>
  );
}

// 신청자 입장의 "액션 필요" 상태 — 행 좌측 레드 보더(§9-2).
const ACTION_NEEDED = new Set<string>(['REVISION_REQUESTED', 'PENDING_PAYMENT']);

export default function DashboardPage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const toast = useToastStore();
  const [summary, setSummary] = useState<ApplicationSummary | null>(null);
  const [recentApps, setRecentApps] = useState<Application[]>([]);
  const [loading, setLoading] = useState(true);

  // Phase 3 PR#3 — LEW 요청 대기 서류 건수 (AC-AU3)
  const pendingDocCounts = usePendingDocumentCounts(
    recentApps.map((a) => a.applicationSeq),
    user?.role === 'APPLICANT',
  );

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [summaryData, appsData] = await Promise.all([
          applicationApi.getApplicationSummary(),
          applicationApi.getMyApplications(),
        ]);
        setSummary(summaryData);
        setRecentApps(appsData.slice(0, 5));
      } catch (err: unknown) {
        const error = err as { message?: string };
        toast.error(error.message || 'Failed to load dashboard data');
      } finally {
        setLoading(false);
      }
    };
    fetchData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading dashboard..." />
      </div>
    );
  }

  // 액션 필요 건수 — 헤더 부제로 안내(§9-2 역할 차등: 신청자는 "내 신청 진행"이 주인공).
  const actionCount = recentApps.filter((a) => ACTION_NEEDED.has(a.status)).length;

  const stats = [
    { label: 'Total', value: summary?.total ?? 0, icon: Files },
    { label: 'Pending Review', value: summary?.pendingReview ?? 0, icon: Search },
    { label: 'Pending Payment', value: summary?.pendingPayment ?? 0, icon: CreditCard },
    { label: 'In Progress', value: summary?.inProgress ?? 0, icon: RefreshCw },
    { label: 'Completed', value: summary?.completed ?? 0, icon: BadgeCheck },
  ];

  return (
    <div className="max-w-7xl mx-auto space-y-6">
      <PageHeader
        title={`Welcome back${user?.firstName ? `, ${user.firstName}` : ''}`}
        subtitle={
          actionCount > 0
            ? `${actionCount} application${actionCount > 1 ? 's' : ''} need${actionCount > 1 ? '' : 's'} your action`
            : 'Overview of your licence applications'
        }
        actions={
          <Button onClick={() => navigate('/applications/new')}>
            <Plus className="w-4 h-4 mr-1.5 inline" />
            New / Renew Licence
          </Button>
        }
      />

      {/* 통계 — 작은 칩 행으로 강등(참고 정보). 신청자 대시보드의 주인공은 아래 진행 목록. */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        {stats.map(({ label, value, icon: Icon }) => (
          <div key={label} className="flex items-center gap-3 bg-white rounded-lg border border-primary-100 px-4 py-3">
            <Icon className="w-4 h-4 text-gray-400 shrink-0" />
            <div className="min-w-0">
              <div className="text-lg font-semibold text-gray-900 tabular-nums leading-none">{value}</div>
              <div className="text-xs text-gray-500 truncate">{label}</div>
            </div>
          </div>
        ))}
      </div>

      {/* Hero: 내 신청 진행 — 강한 elevation, 액션필요 행 레드 보더 */}
      <div className="bg-white rounded-xl border border-primary-100 shadow-dropdown overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-primary-100">
          <h2 className="text-base font-semibold text-gray-900">My Applications</h2>
          {recentApps.length > 0 && (
            <button
              type="button"
              onClick={() => navigate('/applications')}
              className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:text-accent-600 transition-colors"
            >
              View all <ChevronRight className="w-4 h-4" />
            </button>
          )}
        </div>

        {recentApps.length === 0 ? (
          <div className="p-6">
            <EmptyState
              icon="📭"
              title="No applications yet"
              description="Get started by creating your first licence application."
              action={
                <Button variant="outline" size="sm" onClick={() => navigate('/applications/new')}>
                  Create Application
                </Button>
              }
            />
          </div>
        ) : (
          <>
            {/* Mobile card view */}
            <div className="sm:hidden divide-y divide-gray-100">
              {recentApps.map((app) => (
                <div
                  key={app.applicationSeq}
                  className={`py-3 px-4 cursor-pointer active:bg-gray-50 ${
                    ACTION_NEEDED.has(app.status) ? 'border-l-2 border-l-accent' : ''
                  }`}
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate(`/applications/${app.applicationSeq}`)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/applications/${app.applicationSeq}`); } }}
                >
                  <div className="flex items-start justify-between mb-1.5">
                    <div className="min-w-0 flex-1 mr-3">
                      <p className="font-semibold text-gray-900 truncate">{app.address}</p>
                      <p className="text-xs text-gray-400 mt-0.5">{app.postalCode}</p>
                    </div>
                    <div className="flex items-center gap-2 flex-shrink-0">
                      {app.kvaStatus === 'UNKNOWN' && <KvaPendingBadge />}
                      <PendingDocsBadge count={pendingDocCounts[app.applicationSeq] ?? 0} />
                      <StatusBadge status={app.status} />
                    </div>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <div className="flex items-center gap-3 text-gray-500">
                      <span>{app.kvaStatus === 'UNKNOWN' ? '— kVA' : `${app.selectedKva} kVA`}</span>
                      <span className="font-medium text-gray-800">
                        {app.kvaStatus === 'UNKNOWN' ? `From $${app.quoteAmount.toLocaleString()}` : `$${app.quoteAmount.toLocaleString()}`}
                      </span>
                    </div>
                    <span className="text-xs text-gray-400">{new Date(app.createdAt).toLocaleDateString()}</span>
                  </div>
                </div>
              ))}
            </div>
            {/* Desktop table view */}
            <div className="hidden sm:block overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-surface-tertiary text-left">
                    <th className="py-2.5 px-5 font-medium text-gray-500">Address</th>
                    <th className="py-2.5 px-2 font-medium text-gray-500">kVA</th>
                    <th className="py-2.5 px-2 font-medium text-gray-500 text-right">Amount</th>
                    <th className="py-2.5 px-2 font-medium text-gray-500">Status</th>
                    <th className="py-2.5 px-2 font-medium text-gray-500">Date</th>
                    <th className="py-2.5 px-5"></th>
                  </tr>
                </thead>
                <tbody>
                  {recentApps.map((app) => (
                    <tr
                      key={app.applicationSeq}
                      className={`border-b border-gray-50 last:border-0 hover:bg-surface-secondary cursor-pointer transition-colors focus-within:ring-2 focus-within:ring-primary/20 ${
                        ACTION_NEEDED.has(app.status) ? 'border-l-2 border-l-accent' : 'border-l-2 border-l-transparent'
                      }`}
                      tabIndex={0}
                      onClick={() => navigate(`/applications/${app.applicationSeq}`)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/applications/${app.applicationSeq}`); } }}
                    >
                      <td className="py-3 px-5">
                        {/* 닻 열: 주소(굵게) + 우편번호 강등 */}
                        <div className="font-semibold text-gray-900 truncate max-w-[220px]">{app.address}</div>
                        <div className="text-xs text-gray-400">{app.postalCode}</div>
                      </td>
                      <td className="py-3 px-2 text-gray-500">
                        {app.kvaStatus === 'UNKNOWN' ? <KvaPendingBadge /> : <>{app.selectedKva} kVA</>}
                      </td>
                      <td className="py-3 px-2 text-right font-semibold text-gray-900 tabular-nums">
                        {app.kvaStatus === 'UNKNOWN'
                          ? <span className="text-gray-500 font-medium">From ${app.quoteAmount.toLocaleString()}</span>
                          : `$${app.quoteAmount.toLocaleString()}`}
                      </td>
                      <td className="py-3 px-2">
                        <div className="flex items-center gap-2">
                          <StatusBadge status={app.status} />
                          <PendingDocsBadge count={pendingDocCounts[app.applicationSeq] ?? 0} />
                        </div>
                      </td>
                      <td className="py-3 px-2 text-gray-400 text-xs tabular-nums">
                        {new Date(app.createdAt).toLocaleDateString()}
                      </td>
                      <td className="py-3 px-5 text-right">
                        <ChevronRight className="w-4 h-4 text-gray-300 inline" />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
