import { useEffect, useState } from 'react';
import { fullName } from '../../utils/formatName';
import { useNavigate } from 'react-router-dom';
import {
  ClipboardList, FilePen, CreditCard, UserPlus, Files, BadgeCheck,
  RefreshCw, Flag, AlarmClock, Users, ChevronRight, ArrowRight,
} from 'lucide-react';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { EmptyState } from '../../components/ui/EmptyState';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import adminApi from '../../api/adminApi';
import { getBasePath } from '../../utils/routeUtils';
import type { AdminApplication, AdminDashboard } from '../../types';

/** 이름 이니셜 (닻 열 아바타용). */
function initials(first?: string, last?: string): string {
  const a = (first ?? '').trim()[0] ?? '?';
  const b = (last ?? '').trim()[0] ?? '';
  return (a + b).toUpperCase();
}

const ACTION_NEEDED = new Set(['PENDING_REVIEW', 'REVISION_REQUESTED']);

export default function AdminDashboardPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const { user: currentUser } = useAuthStore();
  const isAdmin = currentUser?.role === 'ADMIN' || currentUser?.role === 'SYSTEM_ADMIN';
  const basePath = getBasePath(currentUser?.role);

  const [dashboard, setDashboard] = useState<AdminDashboard | null>(null);
  const [recentApps, setRecentApps] = useState<AdminApplication[]>([]);
  const [loading, setLoading] = useState(true);
  const [lewRegistrationOpen, setLewRegistrationOpen] = useState(true);
  const [settingsLoading, setSettingsLoading] = useState(false);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const promises: Promise<unknown>[] = [adminApi.getDashboard(), adminApi.getApplications(0, 5)];
        if (isAdmin) promises.push(adminApi.getSettings());
        const results = await Promise.all(promises);
        setDashboard(results[0] as AdminDashboard);
        setRecentApps((results[1] as { content: AdminApplication[] }).content);
        if (isAdmin && results[2]) {
          const settings = results[2] as Record<string, string>;
          setLewRegistrationOpen(settings['lew_registration_open'] === 'true');
        }
      } catch (err: unknown) {
        toast.error((err as { message?: string }).message || 'Failed to load dashboard data');
      } finally {
        setLoading(false);
      }
    };
    fetchData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleToggleLewRegistration = async () => {
    const newValue = !lewRegistrationOpen;
    setSettingsLoading(true);
    try {
      await adminApi.updateSettings({ lew_registration_open: String(newValue) });
      setLewRegistrationOpen(newValue);
      toast.success(newValue ? 'LEW registration opened' : 'LEW registration closed');
    } catch {
      toast.error('Failed to update setting');
    } finally {
      setSettingsLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading dashboard..." />
      </div>
    );
  }

  const d = dashboard;
  const go = (q: string) => navigate(`${basePath}/applications?status=${q}`);

  // 작업 큐(액션 필요) — 합계로 Hero 보조 카피.
  const reviewCount = d?.pendingReview ?? 0;
  const revisionCount = d?.revisionRequested ?? 0;
  const unassignedCount = d?.unassigned ?? 0;
  const paymentCount = d?.pendingPayment ?? 0;

  // 2차 KPI(참고용) — 시각 비중 낮춤.
  const secondary = [
    { label: 'Total', value: d?.totalApplications ?? 0, icon: Files, q: '' },
    { label: 'Paid', value: d?.paid ?? 0, icon: BadgeCheck, q: 'PAID' },
    { label: 'In Progress', value: d?.inProgress ?? 0, icon: RefreshCw, q: 'IN_PROGRESS' },
    { label: 'Completed', value: d?.completed ?? 0, icon: Flag, q: 'COMPLETED' },
    { label: 'Expired', value: d?.expired ?? 0, icon: AlarmClock, q: 'EXPIRED' },
    ...(isAdmin ? [{ label: 'Users', value: d?.totalUsers ?? 0, icon: Users, q: 'USERS' }] : []),
  ];

  return (
    // 3단 표면(§9-1): 페이지 배경 = canvas(Layout 전역), 카드 = 흰색.
    <div className="max-w-7xl mx-auto">
      <div className="space-y-6">
        {/* 페이지 헤더 — 로고 레드 슬래시 모티프(좌측 액센트 바) */}
        <div className="flex items-center gap-3">
          <span className="block w-1 h-9 rounded-full bg-accent" aria-hidden />
          <div>
            <h1 className="text-2xl font-bold text-gray-900">
              {isAdmin ? 'Admin Dashboard' : 'LEW Dashboard'}
            </h1>
            <p className="text-sm text-gray-500">
              {reviewCount + revisionCount > 0
                ? `${reviewCount + revisionCount} item${reviewCount + revisionCount > 1 ? 's' : ''} need your attention`
                : 'All caught up — nothing pending review'}
            </p>
          </div>
        </div>

        {/* Hero 행: 주인공 지표(검토 대기) + 작업 큐 요약 */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {/* Hero KPI — 검토 대기. 큰 숫자 + 레드 상단 보더 + 강한 elevation */}
          <button
            type="button"
            onClick={() => go('PENDING_REVIEW')}
            className="group lg:col-span-1 text-left bg-white rounded-xl border-t-[3px] border-accent shadow-dropdown p-6 hover:shadow-hero transition-shadow focus:outline-none focus:ring-2 focus:ring-accent/30"
          >
            <div className="flex items-center gap-2 text-accent-600 mb-3">
              <ClipboardList className="w-5 h-5" />
              <span className="text-sm font-semibold">Needs your review</span>
            </div>
            <div className="text-5xl font-bold text-gray-900 tabular-nums leading-none">{reviewCount}</div>
            <div className="mt-3 flex items-center text-sm text-gray-500 group-hover:text-accent-600 transition-colors">
              Triage now <ChevronRight className="w-4 h-4 ml-0.5" />
            </div>
          </button>

          {/* 작업 큐 요약 — 액션 필요 3종 */}
          <div className="lg:col-span-2 bg-white rounded-xl border border-primary-100 shadow-card p-6">
            <h3 className="text-sm font-semibold text-gray-800 mb-4">Action queue</h3>
            <div className="grid grid-cols-3 gap-4">
              <QueueStat icon={FilePen} label="Revision Requested" value={revisionCount}
                onClick={() => go('REVISION_REQUESTED')} highlight={revisionCount > 0} />
              <QueueStat icon={CreditCard} label="Pending Payment" value={paymentCount}
                onClick={() => go('PENDING_PAYMENT')} />
              <QueueStat icon={UserPlus} label="Unassigned" value={unassignedCount}
                highlight={unassignedCount > 0} />
            </div>
          </div>
        </div>

        {/* 2차 KPI — 작은 칩 행(참고용, 시각 비중 낮춤) */}
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3">
          {secondary.map(({ label, value, icon: Icon, q }) => (
            <button
              key={label}
              type="button"
              onClick={() => (q === 'USERS' ? navigate('/admin/users') : q ? go(q) : navigate(`${basePath}/applications`))}
              className="flex items-center gap-3 bg-white rounded-lg border border-primary-100 px-4 py-3 hover:border-primary-300 transition-colors text-left"
            >
              <Icon className="w-4 h-4 text-gray-400 shrink-0" />
              <div className="min-w-0">
                <div className="text-lg font-semibold text-gray-900 tabular-nums leading-none">{value}</div>
                <div className="text-xs text-gray-500 truncate">{label}</div>
              </div>
            </button>
          ))}
        </div>

        {/* 작업 큐(Recent Applications) — 주인공: 폭 넓게 + 강한 elevation */}
        <div className="bg-white rounded-xl border border-primary-100 shadow-dropdown overflow-hidden">
          <div className="flex items-center justify-between px-5 py-4 border-b border-primary-100">
            <h2 className="text-base font-semibold text-gray-900">Recent Applications</h2>
            {recentApps.length > 0 && (
              <button
                type="button"
                onClick={() => navigate(`${basePath}/applications`)}
                className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:text-accent-600 transition-colors"
              >
                View all <ArrowRight className="w-4 h-4" />
              </button>
            )}
          </div>

          {recentApps.length === 0 ? (
            <div className="p-6">
              <EmptyState
                icon="📭"
                title="No applications yet"
                description="Applications will appear here once users start submitting them."
              />
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-surface-tertiary text-left">
                  <th className="py-2.5 px-5 font-medium text-gray-500">Applicant</th>
                  <th className="py-2.5 px-2 font-medium text-gray-500 hidden md:table-cell">Address</th>
                  <th className="py-2.5 px-2 font-medium text-gray-500 text-right">kVA</th>
                  <th className="py-2.5 px-2 font-medium text-gray-500 text-right">Amount</th>
                  <th className="py-2.5 px-2 font-medium text-gray-500">Status</th>
                  <th className="py-2.5 px-2 font-medium text-gray-500 hidden sm:table-cell">Date</th>
                  <th className="py-2.5 px-5"></th>
                </tr>
              </thead>
              <tbody>
                {recentApps.map((app) => {
                  const needsAction = ACTION_NEEDED.has(app.status);
                  return (
                    <tr
                      key={app.applicationSeq}
                      tabIndex={0}
                      onClick={() => navigate(`${basePath}/applications/${app.applicationSeq}`)}
                      onKeyDown={(e) => { if (e.key === 'Enter') navigate(`${basePath}/applications/${app.applicationSeq}`); }}
                      className={`border-b border-gray-50 last:border-0 hover:bg-surface-secondary cursor-pointer transition-colors ${
                        needsAction ? 'border-l-2 border-l-accent' : 'border-l-2 border-l-transparent'
                      }`}
                    >
                      {/* 닻 열: 아바타 이니셜 + 이름(굵게), 이메일 강등 */}
                      <td className="py-3 px-5">
                        <div className="flex items-center gap-3">
                          <span className="grid place-items-center w-8 h-8 rounded-full bg-primary-100 text-primary-800 text-xs font-semibold shrink-0">
                            {initials(app.userFirstName, app.userLastName)}
                          </span>
                          <div className="min-w-0">
                            <div className="font-semibold text-gray-900 truncate">{fullName(app.userFirstName, app.userLastName)}</div>
                            <div className="text-xs text-gray-400 truncate">{app.userEmail}</div>
                          </div>
                        </div>
                      </td>
                      <td className="py-3 px-2 hidden md:table-cell">
                        <div className="text-gray-600 truncate max-w-[200px]">{app.address}</div>
                      </td>
                      <td className="py-3 px-2 text-right text-gray-500 tabular-nums">{app.selectedKva}</td>
                      <td className="py-3 px-2 text-right font-semibold text-gray-900 tabular-nums">${app.quoteAmount.toLocaleString()}</td>
                      <td className="py-3 px-2"><StatusBadge status={app.status} /></td>
                      <td className="py-3 px-2 text-gray-400 text-xs hidden sm:table-cell tabular-nums">{new Date(app.createdAt).toLocaleDateString()}</td>
                      <td className="py-3 px-5 text-right"><ChevronRight className="w-4 h-4 text-gray-300 inline" /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        {/* ADMIN: LEW 등록 설정 — 하단, 작게 */}
        {isAdmin && (
          <div className="flex items-center justify-between bg-white rounded-xl border border-primary-100 px-5 py-4">
            <div>
              <h3 className="text-sm font-semibold text-gray-800">LEW Registration</h3>
              <p className="text-xs text-gray-500 mt-0.5">
                {lewRegistrationOpen ? 'New LEW sign-ups are currently allowed.' : 'New LEW sign-ups are currently blocked.'}
              </p>
            </div>
            <button
              type="button"
              disabled={settingsLoading}
              onClick={handleToggleLewRegistration}
              className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors focus:outline-none focus:ring-2 focus:ring-primary/20 disabled:opacity-50 ${
                lewRegistrationOpen ? 'bg-primary' : 'bg-gray-300'
              }`}
            >
              <span className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow transition ${lewRegistrationOpen ? 'translate-x-5' : 'translate-x-0'}`} />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/** 작업 큐 미드 스탯 — 액션 필요 시 레드 강조. */
function QueueStat({ icon: Icon, label, value, onClick, highlight }: {
  icon: typeof FilePen; label: string; value: number; onClick?: () => void; highlight?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="text-left rounded-lg p-3 -m-1 hover:bg-surface-secondary transition-colors focus:outline-none focus:ring-2 focus:ring-primary/20"
    >
      <div className={`flex items-center gap-1.5 mb-1.5 text-xs font-medium ${highlight ? 'text-accent-600' : 'text-gray-500'}`}>
        <Icon className="w-4 h-4" /> <span className="truncate">{label}</span>
      </div>
      <div className={`text-2xl font-bold tabular-nums leading-none ${highlight ? 'text-gray-900' : 'text-gray-700'}`}>{value}</div>
    </button>
  );
}
