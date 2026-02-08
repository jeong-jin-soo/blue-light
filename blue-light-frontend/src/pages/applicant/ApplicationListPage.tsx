import { useEffect, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/ui/Button';
import { Select } from '../../components/ui/Select';
import { DataTable, type Column } from '../../components/data/DataTable';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { useToastStore } from '../../stores/toastStore';
import applicationApi from '../../api/applicationApi';
import type { Application } from '../../types';

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
    if (!statusFilter) return applications;
    return applications.filter((app) => app.status === statusFilter);
  }, [applications, statusFilter]);

  const columns: Column<Application>[] = [
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
        <span className="font-medium text-gray-700">{app.selectedKva} kVA</span>
      ),
    },
    {
      key: 'quoteAmount',
      header: 'Amount',
      sortable: true,
      align: 'right',
      render: (app) => (
        <span className="font-medium text-gray-800">
          SGD ${app.quoteAmount.toLocaleString()}
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

      {/* Status filter */}
      {applications.length > 0 && (
        <div className="w-48">
          <Select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            options={STATUS_FILTER_OPTIONS}
          />
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
          statusFilter
            ? `No applications with status "${STATUS_FILTER_OPTIONS.find((s) => s.value === statusFilter)?.label}".`
            : "You haven't submitted any licence applications yet."
        }
        emptyAction={
          !statusFilter ? (
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
                <p className="font-medium text-gray-800 truncate">{app.address}</p>
                <p className="text-xs text-gray-400 mt-0.5">{app.postalCode}</p>
              </div>
              <StatusBadge status={app.status} />
            </div>
            <div className="flex items-center justify-between text-sm">
              <div className="flex items-center gap-3 text-gray-500">
                <span>{app.selectedKva} kVA</span>
                <span className="font-medium text-gray-800">SGD ${app.quoteAmount.toLocaleString()}</span>
              </div>
              <span className="text-xs text-gray-400">{new Date(app.createdAt).toLocaleDateString()}</span>
            </div>
          </div>
        )}
      />
    </div>
  );
}
