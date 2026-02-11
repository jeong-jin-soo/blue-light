import { useEffect, useState, useCallback, useRef } from 'react';
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
import type { AdminApplication, ApplicationStatus } from '../../types';

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

const PAGE_SIZE = 15;

export default function AdminApplicationListPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const { user: currentUser } = useAuthStore();
  const basePath = getBasePath(currentUser?.role);
  const [searchParams, setSearchParams] = useSearchParams();

  const initialStatus = searchParams.get('status') || '';

  const [applications, setApplications] = useState<AdminApplication[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [statusFilter, setStatusFilter] = useState(initialStatus);
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
    setLoading(true);
    adminApi
      .getApplications(
        page,
        PAGE_SIZE,
        statusFilter ? (statusFilter as ApplicationStatus) : undefined,
        debouncedSearch || undefined
      )
      .then((data) => {
        setApplications(data.content);
        setTotalPages(data.totalPages);
      })
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load applications');
      })
      .finally(() => setLoading(false));
  }, [page, statusFilter, debouncedSearch]);

  const handleStatusChange = (value: string) => {
    setStatusFilter(value);
    setPage(0);
    if (value) {
      setSearchParams({ status: value });
    } else {
      setSearchParams({});
    }
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
          <div className="font-medium text-gray-800">{app.userName}</div>
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
      width: '80px',
      render: (app) => (
        <span className="text-gray-600">{app.selectedKva}</span>
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
        <span className={app.assignedLewName ? 'text-gray-700' : 'text-gray-400 italic'}>
          {app.assignedLewName || 'Unassigned'}
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
      width: '40px',
      align: 'center',
      render: () => <span className="text-gray-400">→</span>,
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
          statusFilter || debouncedSearch
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
                <p className="font-medium text-gray-800">{app.userName}</p>
                <p className="text-xs text-gray-400">{app.userEmail}</p>
              </div>
              <StatusBadge status={app.status} />
            </div>
            <p className="text-sm text-gray-700 truncate mb-1">{app.address}</p>
            <div className="flex items-center justify-between text-sm">
              <div className="flex items-center gap-3 text-gray-500">
                <span>{app.selectedKva} kVA</span>
                <span className="font-medium text-gray-800">${app.quoteAmount.toLocaleString()}</span>
              </div>
              <span className="text-xs text-gray-400">{new Date(app.createdAt).toLocaleDateString()}</span>
            </div>
            {app.assignedLewName && (
              <div className="mt-1.5 text-xs text-gray-500">
                <span className="inline-flex items-center gap-1">
                  ⚡ {app.assignedLewName}
                </span>
              </div>
            )}
          </div>
        )}
      />

      {/* Pagination */}
      <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}
