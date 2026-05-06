import { useEffect, useState, useCallback, useRef } from 'react';
import { fullName } from '../../utils/formatName';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Card } from '../../components/ui/Card';
import { DataTable, type Column } from '../../components/data/DataTable';
import { Pagination } from '../../components/data/Pagination';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { Badge } from '../../components/ui/Badge';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import adminApi from '../../api/adminApi';
import { getBasePath } from '../../utils/routeUtils';
import type { AdminApplication, ApplicationStatus, KvaStatus } from '../../types';

const STATUS_OPTIONS = [
  { value: '', label: 'All Statuses' },
  { value: 'PENDING_REVIEW', label: 'Pending Review' },
  { value: 'REVISION_REQUESTED', label: 'Revision Requested' },
  { value: 'PENDING_PAYMENT', label: 'Pending Payment' },
  { value: 'PAID', label: 'Paid' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'EXPIRED', label: 'Expired' },
];

// Phase 5 PR#3 — kVA Status filter (AC-P3)
const KVA_STATUS_OPTIONS = [
  { value: '', label: 'All kVA' },
  { value: 'UNKNOWN', label: 'kVA pending' },
  { value: 'CONFIRMED', label: 'Confirmed' },
];

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
  const [searchTerm, setSearchTerm] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined);

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
    adminApi
      .getApplications(
        page,
        PAGE_SIZE,
        statusFilter ? (statusFilter as ApplicationStatus) : undefined,
        debouncedSearch || undefined,
        kvaStatusFilter ? (kvaStatusFilter as KvaStatus) : undefined
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
      render: (app) => (
        <div>
          <div className="font-medium text-gray-800">{fullName(app.userFirstName, app.userLastName)}</div>
          <div className="text-xs text-gray-400">{app.userEmail}</div>
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
          {app.kvaStatus === 'UNKNOWN' ? (
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
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (app) => <StatusBadge status={app.status} />,
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
        // P2.C — LEW + 본인에게 배정 + PENDING_REVIEW + CoF 미finalize 시 "Review" 링크 노출.
        // 그 외는 기본 화살표로 상세 페이지(AdminApplicationDetailPage)로 이동.
        const showReviewLink =
          currentUser?.role === 'LEW' &&
          app.assignedLewSeq === currentUser.userSeq &&
          app.status === 'PENDING_REVIEW' &&
          !app.cofFinalized;
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
              Start CoF Review
              <svg className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
              </svg>
            </button>
          );
        }
        return <span className="text-gray-400">→</span>;
      },
    },
  ];

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">All Applications</h1>
        <p className="text-sm text-gray-500 mt-1">Monitor and manage all licence applications</p>
      </div>

      {/* Filters */}
      <Card>
        <div className="flex flex-col sm:flex-row gap-3">
          <div className="flex-1">
            <Input
              placeholder="Search by address, name, email, or ID..."
              value={searchTerm}
              onChange={(e) => handleSearchChange(e.target.value)}
            />
          </div>
          <div className="w-full sm:w-48">
            <Select
              value={statusFilter}
              onChange={(e) => handleStatusChange(e.target.value)}
              options={STATUS_OPTIONS}
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
        </div>
      </Card>

      {/* Application table */}
      <DataTable
        columns={columns}
        data={applications}
        loading={loading}
        keyExtractor={(app) => app.applicationSeq}
        onRowClick={(app) => navigate(`${basePath}/applications/${app.applicationSeq}`)}
        emptyIcon="📋"
        emptyTitle="No applications found"
        emptyDescription={
          statusFilter || kvaStatusFilter || debouncedSearch
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
            {app.assignedLewFirstName && (
              <div className="mt-1.5 text-xs text-gray-500">
                <span className="inline-flex items-center gap-1">
                  ⚡ {fullName(app.assignedLewFirstName, app.assignedLewLastName)}
                </span>
              </div>
            )}
            {/* P2.C — LEW + 배정 + PENDING_REVIEW일 때 모바일 카드에도 Review 진입 버튼 */}
            {currentUser?.role === 'LEW' &&
              app.assignedLewSeq === currentUser.userSeq &&
              app.status === 'PENDING_REVIEW' &&
              !app.cofFinalized && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    navigate(`/lew/applications/${app.applicationSeq}/review`);
                  }}
                  className="mt-2 inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-primary-700 bg-primary-50 border border-primary-200 rounded-md hover:bg-primary-100"
                >
                  Start CoF Review →
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
