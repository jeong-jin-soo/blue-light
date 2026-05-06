import { useEffect, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { DataTable, type Column } from '../../components/data/DataTable';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import applicationApi from '../../api/applicationApi';
import { usePendingDocumentCounts } from '../../hooks/usePendingDocumentCounts';
import { KvaPendingBadge } from '../../components/applicant/KvaPendingBadge';
import type { Application } from '../../types';

/** Phase 3 PR#3 — LEW 요청 대기 배지 (AC-AU3) */
function PendingDocsBadge({ count }: { count: number }) {
  if (count <= 0) return null;
  return (
    <span
      className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-semibold text-warning-800 bg-warning-50 border border-warning-500/40 rounded-full"
      title="Awaiting requested documents"
    >
      🟡 {count} awaiting
    </span>
  );
}

/** P2.C — CoF 발급 배지 (목록 행용, 작은 크기). */
function CofIssuedBadge() {
  return (
    <span
      className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-semibold text-success-700 bg-success-50 border border-success-500/40 rounded-full"
      title="Your LEW issued the Certificate of Fitness."
    >
      ✓ CoF
    </span>
  );
}

const STATUS_FILTER_OPTIONS = [
  { value: '', label: 'All Statuses' },
  { value: 'PENDING_REVIEW', label: 'Pending Review' },
  { value: 'REVISION_REQUESTED', label: 'Revision Requested' },
  { value: 'PENDING_PAYMENT', label: 'Pending Payment' },
  { value: 'PAID', label: 'Paid' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'EXPIRED', label: 'Expired' },
];

export default function ApplicationListPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [applications, setApplications] = useState<Application[]>([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState('');
  const [searchKeyword, setSearchKeyword] = useState('');
  const user = useAuthStore((s) => s.user);

  useEffect(() => {
    applicationApi
      .getMyApplications()
      .then(setApplications)
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load applications');
      })
      .finally(() => setLoading(false));
  }, []);

  const filteredApplications = useMemo(() => {
    let result = applications;

    // Status filter
    if (statusFilter) {
      result = result.filter((app) => app.status === statusFilter);
    }

    // Keyword search (address, postal code, building type, licence number, SP account)
    if (searchKeyword.trim()) {
      const keyword = searchKeyword.trim().toLowerCase();
      result = result.filter((app) =>
        app.address.toLowerCase().includes(keyword) ||
        app.postalCode.toLowerCase().includes(keyword) ||
        (app.buildingType && app.buildingType.toLowerCase().includes(keyword)) ||
        (app.licenseNumber && app.licenseNumber.toLowerCase().includes(keyword)) ||
        (app.spAccountNo && app.spAccountNo.toLowerCase().includes(keyword)) ||
        String(app.applicationSeq).includes(keyword) ||
        String(app.selectedKva).includes(keyword)
      );
    }

    return result;
  }, [applications, statusFilter, searchKeyword]);

  // Phase 3 PR#3 — pending 배지용 (AC-AU3)
  const pendingDocCounts = usePendingDocumentCounts(
    filteredApplications.map((a) => a.applicationSeq),
    user?.role === 'APPLICANT',
  );

  const columns: Column<Application>[] = [
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
      key: 'address',
      header: 'Address',
      sortable: true,
      render: (app) => (
        <div>
          <div className="font-medium text-gray-800 truncate max-w-[240px]">
            {app.address}
          </div>
          <div className="text-xs text-gray-400">{app.postalCode}</div>
        </div>
      ),
    },
    {
      key: 'buildingType',
      header: 'Building Type',
      className: 'hidden lg:table-cell',
      render: (app) => (
        <span className="text-gray-600">{app.buildingType || '-'}</span>
      ),
    },
    {
      key: 'selectedKva',
      header: 'kVA',
      sortable: true,
      align: 'right',
      className: 'hidden sm:table-cell',
      render: (app) => (
        app.kvaStatus === 'UNKNOWN'
          ? <KvaPendingBadge />
          : <span className="font-medium text-gray-700">{app.selectedKva} kVA</span>
      ),
    },
    {
      key: 'quoteAmount',
      header: 'Amount',
      sortable: true,
      align: 'right',
      render: (app) => (
        <span className={`font-medium ${app.kvaStatus === 'UNKNOWN' ? 'text-gray-500' : 'text-gray-800'}`}>
          {app.kvaStatus === 'UNKNOWN' ? 'From ' : ''}SGD ${app.quoteAmount.toLocaleString()}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (app) => (
        <div className="flex items-center gap-2 flex-wrap">
          <StatusBadge status={app.status} />
          {app.cofFinalized && <CofIssuedBadge />}
          <PendingDocsBadge count={pendingDocCounts[app.applicationSeq] ?? 0} />
        </div>
      ),
    },
    {
      key: 'createdAt',
      header: 'Date',
      sortable: true,
      className: 'hidden md:table-cell',
      render: (app) => (
        <span className="text-gray-500">
          {new Date(app.createdAt).toLocaleDateString()}
        </span>
      ),
    },
    {
      key: '_action',
      header: '',
      width: '40px',
      align: 'center',
      render: () => <span className="text-gray-400">→</span>,
    },
  ];

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">My Applications</h1>
          <p className="text-sm text-gray-500 mt-1">
            Track and manage your licence applications
          </p>
        </div>
        <Button onClick={() => navigate('/applications/new')}>
          + New Application
        </Button>
      </div>

      {/* Search & filter */}
      {applications.length > 0 && (
        <div className="flex flex-col sm:flex-row gap-3">
          <div className="flex-1 max-w-sm">
            <Input
              placeholder="Search by address, postal code, kVA..."
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
            />
          </div>
          <div className="w-48">
            <Select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              options={STATUS_FILTER_OPTIONS}
            />
          </div>
        </div>
      )}

      {/* Application list */}
      <DataTable
        columns={columns}
        data={filteredApplications}
        loading={loading}
        keyExtractor={(app) => app.applicationSeq}
        onRowClick={(app) => navigate(`/applications/${app.applicationSeq}`)}
        emptyIcon="📋"
        emptyTitle="No applications found"
        emptyDescription={
          searchKeyword || statusFilter
            ? `No applications found${statusFilter ? ` with status "${STATUS_FILTER_OPTIONS.find((s) => s.value === statusFilter)?.label}"` : ''}${searchKeyword ? ` matching "${searchKeyword}"` : ''}.`
            : "You haven't submitted any licence applications yet."
        }
        emptyAction={
          !statusFilter && !searchKeyword ? (
            <Button
              variant="outline"
              size="sm"
              onClick={() => navigate('/applications/new')}
            >
              Create Your First Application
            </Button>
          ) : undefined
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
                <p className="font-medium text-gray-800 truncate">{app.address}</p>
                <p className="text-xs text-gray-400 mt-0.5">{app.postalCode}</p>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0 flex-wrap justify-end">
                {app.kvaStatus === 'UNKNOWN' && <KvaPendingBadge />}
                {app.cofFinalized && <CofIssuedBadge />}
                <PendingDocsBadge count={pendingDocCounts[app.applicationSeq] ?? 0} />
                <StatusBadge status={app.status} />
              </div>
            </div>
            <div className="flex items-center justify-between text-sm">
              <div className="flex items-center gap-3 text-gray-500">
                <span>{app.kvaStatus === 'UNKNOWN' ? '— kVA' : `${app.selectedKva} kVA`}</span>
                <span className={`font-medium ${app.kvaStatus === 'UNKNOWN' ? 'text-gray-500' : 'text-gray-800'}`}>
                  {app.kvaStatus === 'UNKNOWN' ? 'From ' : ''}SGD ${app.quoteAmount.toLocaleString()}
                </span>
              </div>
              <span className="text-xs text-gray-400">{new Date(app.createdAt).toLocaleDateString()}</span>
            </div>
          </div>
        )}
      />
    </div>
  );
}
