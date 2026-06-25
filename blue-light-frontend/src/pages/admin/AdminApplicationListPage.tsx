import { useEffect, useState, useCallback, useRef } from 'react';
import { fullName } from '../../utils/formatName';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Card } from '../../components/ui/Card';
import { DataTable, type Column } from '../../components/data/DataTable';
import { Pagination } from '../../components/data/Pagination';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { PageHeader } from '../../components/ui/PageHeader';
import { Badge } from '../../components/ui/Badge';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import adminApi from '../../api/adminApi';
import { getBasePath } from '../../utils/routeUtils';
import { formatEmaStatus, getEmaStatusBadge, isEmaInFlight } from '../../utils/applicationUtils';
import type {
  AdminApplication,
  AdminDashboard,
  ApplicationStatus,
  EmaSubmissionStatus,
  KvaStatus,
} from '../../types';

// 닻 열 아바타 이니셜.
function initials(first?: string, last?: string): string {
  const a = (first ?? '').trim()[0] ?? '?';
  const b = (last ?? '').trim()[0] ?? '';
  return (a + b).toUpperCase();
}

// "액션 필요" 상태 — 행 좌측 레드 보더 + 칩 강조(§9-2 B).
const ACTION_NEEDED = new Set<string>(['PENDING_REVIEW', 'REVISION_REQUESTED']);

// 필터 칩 정의 + 카운트 매핑 키(AdminDashboard 필드).
const STATUS_CHIPS: { value: string; label: string; countKey: keyof AdminDashboard }[] = [
  { value: '', label: 'All', countKey: 'totalApplications' },
  { value: 'PENDING_REVIEW', label: 'Pending Review', countKey: 'pendingReview' },
  { value: 'REVISION_REQUESTED', label: 'Revision', countKey: 'revisionRequested' },
  { value: 'PENDING_PAYMENT', label: 'Pending Payment', countKey: 'pendingPayment' },
  { value: 'PAID', label: 'Paid', countKey: 'paid' },
  { value: 'IN_PROGRESS', label: 'In Progress', countKey: 'inProgress' },
  { value: 'COMPLETED', label: 'Completed', countKey: 'completed' },
  // 라이선스 만료는 신청 상태가 아니라 licenseStatus 필터 — 센티넬 값으로 분기.
  { value: 'LICENSE_EXPIRED', label: 'Expired', countKey: 'expired' },
];

// Phase 5 PR#3 — kVA Status filter (AC-P3)
const KVA_STATUS_OPTIONS = [
  { value: '', label: 'All kVA' },
  { value: 'UNKNOWN', label: 'kVA pending' },
  { value: 'CONFIRMED', label: 'Confirmed' },
];

// EMA 제출 추적 필터 (ema-submission-tracking-spec.md §8.3).
// 백엔드 목록 API 에 EMA 필터 파라미터가 없으므로 현재 페이지에 대한 클라이언트 사이드 필터.
// "In flight" = SUBMITTED/QUERY_RAISED/RESUBMITTED (정체 후보 묶음).
const EMA_STATUS_OPTIONS = [
  { value: '', label: 'All EMA' },
  { value: 'IN_FLIGHT', label: 'EMA in progress' },
  { value: 'NOT_SUBMITTED', label: 'Not submitted' },
  { value: 'SUBMITTED', label: 'Submitted' },
  { value: 'QUERY_RAISED', label: 'Query raised' },
  { value: 'RESUBMITTED', label: 'Resubmitted' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'WITHDRAWN', label: 'Withdrawn' },
];

/** SUBMITTED 후 N일 초과 시 정체 강조 (리마인더 기준과 동일 정신 — 클라이언트 보조 표시). */
const EMA_STALE_DAYS = 3;

function isEmaStale(app: AdminApplication): boolean {
  if (!isEmaInFlight(app.emaSubmissionStatus)) return false;
  if (!app.emaSubmittedAt) return false;
  const days = (Date.now() - new Date(app.emaSubmittedAt).getTime()) / (1000 * 60 * 60 * 24);
  return days > EMA_STALE_DAYS;
}

const PAGE_SIZE = 15;

export default function AdminApplicationListPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const { user: currentUser } = useAuthStore();
  const basePath = getBasePath(currentUser?.role);
  const [searchParams, setSearchParams] = useSearchParams();

  const initialStatus = searchParams.get('status') || '';
  const initialKvaStatus = searchParams.get('kvaStatus') || '';

  const [applications, setApplications] = useState<AdminApplication[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [statusFilter, setStatusFilter] = useState(initialStatus);
  const [kvaStatusFilter, setKvaStatusFilter] = useState(initialKvaStatus);
  const [emaStatusFilter, setEmaStatusFilter] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [counts, setCounts] = useState<AdminDashboard | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  // 필터 칩 카운트(상태별 건수) — 대시보드 집계 1회 로드.
  useEffect(() => {
    adminApi.getDashboard().then(setCounts).catch(() => { /* 칩 카운트는 보조 정보 — 실패해도 무시 */ });
  }, []);

  // Debounce search input (300ms)
  const handleSearchChange = useCallback((value: string) => {
    setSearchTerm(value);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setDebouncedSearch(value);
      setPage(0);
    }, 300);
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    const licenseExpiredFilter = statusFilter === 'LICENSE_EXPIRED';
    adminApi
      .getApplications(
        page,
        PAGE_SIZE,
        licenseExpiredFilter || !statusFilter ? undefined : (statusFilter as ApplicationStatus),
        debouncedSearch || undefined,
        kvaStatusFilter ? (kvaStatusFilter as KvaStatus) : undefined,
        licenseExpiredFilter ? 'EXPIRED' : undefined
      )
      .then((data) => {
        setApplications(data.content);
        setTotalPages(data.totalPages);
      })
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load applications');
      })
      .finally(() => setLoading(false));
  }, [page, statusFilter, kvaStatusFilter, debouncedSearch]);

  const syncQuery = (next: { status?: string; kvaStatus?: string }) => {
    const params: Record<string, string> = {};
    if (next.status) params.status = next.status;
    if (next.kvaStatus) params.kvaStatus = next.kvaStatus;
    setSearchParams(params);
  };

  const handleStatusChange = (value: string) => {
    setStatusFilter(value);
    setPage(0);
    syncQuery({ status: value, kvaStatus: kvaStatusFilter });
  };

  const handleKvaStatusChange = (value: string) => {
    setKvaStatusFilter(value);
    setPage(0);
    syncQuery({ status: statusFilter, kvaStatus: value });
  };

  // EMA 필터는 현재 페이지에 대한 클라이언트 사이드 필터(백엔드 목록 API 미지원).
  const visibleApplications = applications.filter((app) => {
    if (!emaStatusFilter) return true;
    if (emaStatusFilter === 'IN_FLIGHT') return isEmaInFlight(app.emaSubmissionStatus);
    return (app.emaSubmissionStatus ?? 'NOT_SUBMITTED') === (emaStatusFilter as EmaSubmissionStatus);
  });

  const columns: Column<AdminApplication>[] = [
    {
      key: 'applicationSeq',
      header: 'ID',
      width: '60px',
      render: (app) => (
        <span className="font-mono text-xs text-gray-500">#{app.applicationSeq}</span>
      ),
    },
    {
      key: 'applicationType',
      header: 'Type',
      width: '80px',
      render: (app) => (
        <Badge variant={app.applicationType === 'RENEWAL' ? 'warning' : 'info'}>
          {app.applicationType === 'RENEWAL' ? 'Renewal' : 'New'}
        </Badge>
      ),
    },
    {
      key: 'userName',
      header: 'Applicant',
      // 닻 열: 아바타 이니셜 + 이름(굵게) — 시선이 이름→상태→금액으로 흐르게(§9-2 B).
      render: (app) => (
        <div className="flex items-center gap-3">
          <span className="grid place-items-center w-8 h-8 rounded-full bg-primary-100 text-primary-800 text-xs font-semibold shrink-0">
            {initials(app.userFirstName, app.userLastName)}
          </span>
          <div className="min-w-0">
            <div className="font-semibold text-gray-900 truncate">{fullName(app.userFirstName, app.userLastName)}</div>
            <div className="text-xs text-gray-400 truncate">{app.userEmail}</div>
          </div>
        </div>
      ),
    },
    {
      key: 'address',
      header: 'Address',
      sortable: true,
      render: (app) => (
        <div>
          <div className="text-gray-700 truncate max-w-[200px]">{app.address}</div>
          <div className="text-xs text-gray-400">{app.postalCode}</div>
        </div>
      ),
    },
    {
      key: 'selectedKva',
      header: 'kVA',
      align: 'right',
      width: '110px',
      render: (app) => (
        <div className="flex items-center justify-end gap-1.5">
          {app.kvaStatus !== 'CONFIRMED' && app.kvaSource === 'USER_INPUT' ? (
            // 신청자 신고값 — LEW 미확정. 신고값 + 검토 대기 배지.
            <>
              <span className="text-gray-600">{app.selectedKva}</span>
              <Badge variant="warning" className="text-[10px]">review</Badge>
            </>
          ) : app.kvaStatus === 'UNKNOWN' ? (
            <Badge variant="warning" className="text-[10px]">kVA pending</Badge>
          ) : (
            <>
              <span className="text-gray-600">{app.selectedKva}</span>
              {app.kvaSource === 'LEW_VERIFIED' && (
                <Badge variant="success" className="text-[10px]">LEW verified</Badge>
              )}
            </>
          )}
        </div>
      ),
    },
    {
      key: 'quoteAmount',
      header: 'Amount',
      align: 'right',
      render: (app) => (
        <span className="font-medium text-gray-800">
          ${app.quoteAmount.toLocaleString()}
        </span>
      ),
    },
    {
      key: 'assignedLewName',
      header: 'Assigned LEW',
      render: (app) => (
        <span className={app.assignedLewFirstName ? 'text-gray-700' : 'text-gray-400 italic'}>
          {fullName(app.assignedLewFirstName, app.assignedLewLastName) || 'Unassigned'}
          {app.assignedLewGradeMismatch && (
            <span
              className="ml-1 text-error-600"
              title="Assigned LEW grade cannot handle the current kVA — reassign to a higher-grade LEW"
            >
              ⚠
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (app) => <StatusBadge status={app.status} />,
    },
    {
      key: 'emaStatus',
      header: 'EMA',
      width: '130px',
      render: (app) => {
        // IN_PROGRESS 가 아니면 EMA 단계가 아직 아님 → 흐리게 표시.
        if (app.status !== 'IN_PROGRESS' && (app.emaSubmissionStatus ?? 'NOT_SUBMITTED') === 'NOT_SUBMITTED') {
          return <span className="text-gray-300 text-xs">—</span>;
        }
        const stale = isEmaStale(app);
        return (
          <span className="inline-flex items-center gap-1">
            <Badge variant={getEmaStatusBadge(app.emaSubmissionStatus)} className="text-[10px]">
              {formatEmaStatus(app.emaSubmissionStatus)}
            </Badge>
            {app.emaGrandfathered && (
              <span title="Auto-approved before EMA tracking">
                <Badge variant="gray" className="text-[10px]">legacy</Badge>
              </span>
            )}
            {stale && (
              <span
                className="text-warning-600 text-xs"
                title={`No EMA change for more than ${EMA_STALE_DAYS} days`}
                aria-label="EMA stale"
              >
                ⏱
              </span>
            )}
          </span>
        );
      },
    },
    {
      key: 'createdAt',
      header: 'Date',
      sortable: true,
      render: (app) => (
        <span className="text-gray-500 text-xs">
          {new Date(app.createdAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      key: '_action',
      header: '',
      width: '120px',
      align: 'center',
      render: (app) => {
        // LEW + 본인에게 배정 시 "Review" 링크 노출 — 단계와 무관하게 항상 진입 가능
        // (완료 후에는 리뷰 화면이 읽기 전용). 미배정은 기본 화살표로 상세 페이지 이동.
        const showReviewLink =
          currentUser?.role === 'LEW' &&
          app.assignedLewSeq === currentUser.userSeq;
        if (showReviewLink) {
          return (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                navigate(`/lew/applications/${app.applicationSeq}/review`);
              }}
              className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-primary-700 bg-primary-50 border border-primary-200 rounded-md hover:bg-primary-100 focus:outline-none focus:ring-2 focus:ring-primary/20"
            >
              Review
              <svg className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
              </svg>
            </button>
          );
        }
        return <ChevronRight className="w-4 h-4 text-gray-300 inline" />;
      },
    },
  ];

  return (
    <div className="max-w-7xl mx-auto space-y-6">
      <PageHeader title="All Applications" subtitle="Monitor and manage all licence applications" />

      {/* 상태 필터 칩(카운트 배지) — 드롭다운 대체(§9-2 B) */}
      <div className="flex flex-wrap gap-2">
        {STATUS_CHIPS.map((chip) => {
          const active = statusFilter === chip.value;
          const n = counts ? (counts[chip.countKey] as number) : undefined;
          const action = ACTION_NEEDED.has(chip.value);
          return (
            <button
              key={chip.value || 'all'}
              type="button"
              onClick={() => handleStatusChange(chip.value)}
              aria-pressed={active}
              className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm font-medium border transition-colors
                ${active
                  ? 'bg-primary text-white border-primary'
                  : `bg-white border-primary-100 text-gray-600 hover:border-primary-300 ${action ? 'border-l-2 border-l-accent' : ''}`}`}
            >
              {chip.label}
              {n !== undefined && n > 0 && (
                <span className={`text-xs tabular-nums rounded-full px-1.5 ${
                  active ? 'bg-white/25 text-white' : action ? 'bg-accent-50 text-accent-600' : 'bg-primary-50 text-primary-700'
                }`}>{n}</span>
              )}
            </button>
          );
        })}
      </div>

      {/* 검색 + kVA 필터 */}
      <Card>
        <div className="flex flex-col sm:flex-row gap-3">
          <div className="flex-1">
            <Input
              placeholder="Search by address, name, email, or ID..."
              value={searchTerm}
              onChange={(e) => handleSearchChange(e.target.value)}
            />
          </div>
          <div className="w-full sm:w-44">
            <Select
              aria-label="kVA Status"
              value={kvaStatusFilter}
              onChange={(e) => handleKvaStatusChange(e.target.value)}
              options={KVA_STATUS_OPTIONS}
            />
          </div>
          <div className="w-full sm:w-44">
            <Select
              aria-label="EMA Status"
              value={emaStatusFilter}
              onChange={(e) => { setEmaStatusFilter(e.target.value); }}
              options={EMA_STATUS_OPTIONS}
            />
          </div>
        </div>
        <p className="mt-2 text-xs text-gray-400">EMA filter applies to the current page.</p>
      </Card>

      {/* Application table */}
      <DataTable
        columns={columns}
        data={visibleApplications}
        loading={loading}
        keyExtractor={(app) => app.applicationSeq}
        onRowClick={(app) => navigate(`${basePath}/applications/${app.applicationSeq}`)}
        rowClassName={(app) => (ACTION_NEEDED.has(app.status) ? 'border-l-2 border-l-accent' : '')}
        emptyIcon="📋"
        emptyTitle="No applications found"
        emptyDescription={
          statusFilter || kvaStatusFilter || emaStatusFilter || debouncedSearch
            ? 'No applications match your search criteria.'
            : 'Applications will appear here once users start submitting them.'
        }
        mobileCardRender={(app) => (
          <div className="p-4 border-b border-gray-100">
            <div className="flex items-start justify-between mb-2">
              <div className="min-w-0 flex-1 mr-3">
                <div className="flex items-center gap-2 mb-0.5">
                  <Badge variant={app.applicationType === 'RENEWAL' ? 'warning' : 'info'} className="text-[10px]">
                    {app.applicationType === 'RENEWAL' ? 'Renewal' : 'New'}
                  </Badge>
                </div>
                <p className="font-medium text-gray-800">{fullName(app.userFirstName, app.userLastName)}</p>
                <p className="text-xs text-gray-400">{app.userEmail}</p>
              </div>
              <StatusBadge status={app.status} />
            </div>
            <p className="text-sm text-gray-700 truncate mb-1">{app.address}</p>
            <div className="flex items-center justify-between text-sm">
              <div className="flex items-center gap-2 text-gray-500 flex-wrap">
                {app.kvaStatus === 'UNKNOWN' ? (
                  <Badge variant="warning" className="text-[10px]">kVA pending</Badge>
                ) : (
                  <>
                    <span>{app.selectedKva} kVA</span>
                    {app.kvaSource === 'LEW_VERIFIED' && (
                      <Badge variant="success" className="text-[10px]">LEW verified</Badge>
                    )}
                  </>
                )}
                <span className="font-medium text-gray-800">${app.quoteAmount.toLocaleString()}</span>
              </div>
              <span className="text-xs text-gray-400">{new Date(app.createdAt).toLocaleDateString()}</span>
            </div>
            {(app.status === 'IN_PROGRESS' || (app.emaSubmissionStatus && app.emaSubmissionStatus !== 'NOT_SUBMITTED')) && (
              <div className="mt-1.5 flex items-center gap-1.5">
                <span className="text-xs text-gray-400">EMA</span>
                <Badge variant={getEmaStatusBadge(app.emaSubmissionStatus)} className="text-[10px]">
                  {formatEmaStatus(app.emaSubmissionStatus)}
                </Badge>
                {isEmaStale(app) && <span className="text-warning-600 text-xs" title="EMA stale">⏱</span>}
              </div>
            )}
            {app.assignedLewFirstName && (
              <div className="mt-1.5 text-xs text-gray-500">
                <span className="inline-flex items-center gap-1">
                  ⚡ {fullName(app.assignedLewFirstName, app.assignedLewLastName)}
                </span>
              </div>
            )}
            {/* LEW + 배정 시 모바일 카드에도 Review 진입 버튼 — 단계 무관 항상 노출 */}
            {currentUser?.role === 'LEW' &&
              app.assignedLewSeq === currentUser.userSeq && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(`/lew/applications/${app.applicationSeq}/review`);
                  }}
                  className="mt-2 inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-primary-700 bg-primary-50 border border-primary-200 rounded-md hover:bg-primary-100"
                >
                  Review →
                </button>
              )}
          </div>
        )}
      />

      {/* Pagination */}
      <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}
