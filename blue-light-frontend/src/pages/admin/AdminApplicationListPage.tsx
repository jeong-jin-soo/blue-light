import { useEffect, useState, useCallback, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Card } from '../../components/ui/Card';
import { DataTable, type Column } from '../../components/data/DataTable';
import { Pagination } from '../../components/data/Pagination';
import { StatusBadge } from '../../components/domain/StatusBadge';
import adminApi from '../../api/adminApi';
import type { AdminApplication, ApplicationStatus } from '../../types';

const STATUS_OPTIONS = [
  { value: '', label: 'All Statuses' },
  { value: 'PENDING_PAYMENT', label: 'Pending Payment' },
  { value: 'PAID', label: 'Paid' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'EXPIRED', label: 'Expired' },
];

const PAGE_SIZE = 15;

export default function AdminApplicationListPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const initialStatus = searchParams.get('status') || '';

  const [applications, setApplications] = useState<AdminApplication[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [statusFilter, setStatusFilter] = useState(initialStatus);
  const [searchTerm, setSearchTerm] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const debounceRef = useRef<ReturnType<typeof setTimeout>>();

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
      .catch(() => {
        // Handled by axios interceptor
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
        <h1 className="text-2xl font-bold text-gray-800">All Applications</h1>
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
        onRowClick={(app) => navigate(`/admin/applications/${app.applicationSeq}`)}
        emptyIcon="📋"
        emptyTitle="No applications found"
        emptyDescription={
          statusFilter || debouncedSearch
            ? 'No applications match your search criteria.'
            : 'Applications will appear here once users start submitting them.'
        }
      />

      {/* Pagination */}
      <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}
