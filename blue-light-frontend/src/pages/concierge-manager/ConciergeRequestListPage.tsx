/**
 * ConciergeRequestListPage
 * - Kaki Concierge v1.5 Phase 1 PR#4 Stage B
 * - /concierge-manager/requests
 * - DataTable + Pagination + status/search 필터
 */

import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { DataTable, type Column } from '../../components/data/DataTable';
import { Pagination } from '../../components/data/Pagination';
import { ConciergeStatusBadge } from '../../components/concierge/ConciergeStatusBadge';
import conciergeManagerApi, {
  type ConciergeRequestSummary,
  type ConciergeStatus,
} from '../../api/conciergeManagerApi';

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'All statuses' },
  { value: 'SUBMITTED', label: 'Submitted' },
  { value: 'ASSIGNED', label: 'Assigned' },
  { value: 'CONTACTING', label: 'Contacting' },
  { value: 'APPLICATION_CREATED', label: 'Application ready' },
  { value: 'AWAITING_APPLICANT_LOA_SIGN', label: 'Awaiting LOA' },
  { value: 'AWAITING_LICENCE_PAYMENT', label: 'Awaiting payment' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

const PAGE_SIZE = 20;

function fmtDate(at: string): string {
  try {
    return new Date(at).toLocaleDateString();
  } catch {
    return at;
  }
}

export default function ConciergeRequestListPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<ConciergeRequestSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [status, setStatus] = useState<ConciergeStatus | ''>('');
  const [searchInput, setSearchInput] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');

  const load = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await conciergeManagerApi.list({
        status: status || undefined,
        q: appliedSearch || undefined,
        page,
        size: PAGE_SIZE,
      });
      setItems(res.content);
      setTotalPages(res.totalPages);
    } catch (err) {
      const msg =
        err && typeof err === 'object' && 'message' in err
          ? String((err as { message: unknown }).message)
          : 'Failed to load requests';
      setError(msg);
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [status, appliedSearch, page]);

  useEffect(() => {
    load();
  }, [load]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    setAppliedSearch(searchInput.trim());
  };

  const handleStatusChange = (value: string) => {
    setPage(0);
    setStatus((value as ConciergeStatus) || '');
  };

  const columns: Column<ConciergeRequestSummary>[] = [
    {
      key: 'publicCode',
      header: 'Code',
      render: (r) => (
        <span className="font-mono text-xs text-gray-600">{r.publicCode}</span>
      ),
      width: '140px',
    },
    {
      key: 'submitterName',
      header: 'Submitter',
      render: (r) => (
        <div className="min-w-0">
          <div className="text-sm font-medium text-gray-900 truncate">
            {r.submitterName}
          </div>
          <div className="text-xs text-gray-500 truncate">{r.submitterEmail}</div>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (r) => (
        <div className="flex flex-col gap-1 items-start">
          <ConciergeStatusBadge status={r.status} />
          {r.slaBreached && <Badge variant="error">SLA Breach</Badge>}
        </div>
      ),
    },
    {
      key: 'applicantUserStatus',
      header: 'Account',
      className: 'hidden md:table-cell',
      render: (r) =>
        r.applicantUserStatus === 'PENDING_ACTIVATION' ? (
          <Badge variant="warning">Pending activation</Badge>
        ) : r.applicantUserStatus === 'ACTIVE' ? (
          <Badge variant="success">Active</Badge>
        ) : r.applicantUserStatus === 'SUSPENDED' ? (
          <Badge variant="error">Suspended</Badge>
        ) : (
          <Badge variant="gray">—</Badge>
        ),
    },
    {
      key: 'assignedManagerName',
      header: 'Manager',
      className: 'hidden lg:table-cell',
      render: (r) =>
        r.assignedManagerName ? (
          <span className="text-sm text-gray-700">{r.assignedManagerName}</span>
        ) : (
          <span className="text-sm text-gray-400">Unassigned</span>
        ),
    },
    {
      key: 'createdAt',
      header: 'Submitted',
      className: 'hidden sm:table-cell',
      render: (r) => (
        <span className="text-sm text-gray-600">{fmtDate(r.createdAt)}</span>
      ),
      width: '120px',
    },
  ];

  return (
    <div className="max-w-6xl mx-auto">
      <div className="mb-4">
        <h1 className="text-2xl font-bold text-gray-900">Concierge Requests</h1>
        <p className="text-sm text-gray-600 mt-1">
          Kaki Concierge service requests
        </p>
      </div>

      <form
        onSubmit={handleSearch}
        className="mb-4 flex flex-col sm:flex-row gap-2 bg-surface rounded-lg p-3 shadow-card"
      >
        <div className="sm:w-60">
          <Select
            name="status-filter"
            value={status}
            onChange={(e) => handleStatusChange(e.target.value)}
            options={STATUS_OPTIONS}
            aria-label="Filter by status"
          />
        </div>
        <div className="flex-1">
          <Input
            name="search"
            type="search"
            placeholder="Search by code, name, or email"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            aria-label="Search requests"
          />
        </div>
        <Button type="submit" variant="primary">
          Search
        </Button>
      </form>

      {error && (
        <div
          role="alert"
          className="mb-4 p-3 rounded-md bg-error-50 border border-error-200 text-sm text-error-700"
        >
          {error}
        </div>
      )}

      <DataTable
        columns={columns}
        data={items}
        loading={loading}
        keyExtractor={(r) => r.conciergeRequestSeq}
        emptyTitle="No concierge requests"
        emptyDescription={
          status || appliedSearch
            ? 'Try changing the filters.'
            : 'Nothing has been submitted yet.'
        }
        onRowClick={(r) =>
          navigate(`/concierge-manager/requests/${r.conciergeRequestSeq}`)
        }
        mobileCardRender={(r) => (
          <div className="p-4">
            <div className="flex items-start justify-between gap-3 mb-2">
              <div className="min-w-0 flex-1">
                <div className="font-mono text-xs text-gray-500">
                  {r.publicCode}
                </div>
                <div className="text-sm font-medium text-gray-900 truncate">
                  {r.submitterName}
                </div>
                <div className="text-xs text-gray-500 truncate">
                  {r.submitterEmail}
                </div>
              </div>
              <div className="flex flex-col items-end gap-1 flex-shrink-0">
                <ConciergeStatusBadge status={r.status} />
                {r.slaBreached && <Badge variant="error">SLA Breach</Badge>}
              </div>
            </div>
            <div className="flex items-center justify-between text-xs text-gray-500">
              <span>{fmtDate(r.createdAt)}</span>
              <span>
                {r.assignedManagerName
                  ? `Manager: ${r.assignedManagerName}`
                  : 'Unassigned'}
              </span>
            </div>
          </div>
        )}
      />

      <Pagination
        page={page}
        totalPages={totalPages}
        onPageChange={setPage}
      />
    </div>
  );
}
