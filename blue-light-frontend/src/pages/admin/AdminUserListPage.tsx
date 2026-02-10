import { useCallback, useEffect, useState } from 'react';
import { Card } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Badge } from '../../components/ui/Badge';
import { DataTable, type Column } from '../../components/data/DataTable';
import { Pagination } from '../../components/data/Pagination';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { useToastStore } from '../../stores/toastStore';
import adminApi from '../../api/adminApi';
import type { User, UserRole, ApprovalStatus } from '../../types';

const ROLE_OPTIONS = [
  { value: '', label: 'All Roles' },
  { value: 'APPLICANT', label: 'Applicant' },
  { value: 'LEW', label: 'LEW' },
  { value: 'ADMIN', label: 'Admin' },
];

const PAGE_SIZE = 20;

export default function AdminUserListPage() {
  const toast = useToastStore();
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [searchTerm, setSearchTerm] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const [roleChangeTarget, setRoleChangeTarget] = useState<{ user: User; newRole: UserRole } | null>(null);
  const [changingRole, setChangingRole] = useState(false);
  const [approvalTarget, setApprovalTarget] = useState<{ user: User; action: 'approve' | 'reject' } | null>(null);
  const [processingApproval, setProcessingApproval] = useState(false);

  const loadUsers = useCallback((currentPage: number, role: string, search: string) => {
    setLoading(true);
    adminApi
      .getUsers(currentPage, PAGE_SIZE, role || undefined, search || undefined)
      .then((data) => {
        setUsers(data.content);
        setTotalPages(data.totalPages);
        setTotalElements(data.totalElements);
      })
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load users');
      })
      .finally(() => setLoading(false));
  }, [toast]);

  useEffect(() => {
    loadUsers(page, roleFilter, searchTerm);
  }, [page, roleFilter, searchTerm]);

  // 검색 debounce: Enter 또는 버튼 클릭 시 적용
  const handleSearch = () => {
    setPage(0);
    setSearchTerm(searchInput);
  };

  const handleSearchKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      handleSearch();
    }
  };

  const handleClearSearch = () => {
    setSearchInput('');
    setPage(0);
    setSearchTerm('');
  };

  const handleRoleFilterChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setRoleFilter(e.target.value);
    setPage(0);
  };

  const handlePageChange = (newPage: number) => {
    setPage(newPage);
  };

  const handleRoleChange = async () => {
    if (!roleChangeTarget) return;
    setChangingRole(true);
    try {
      await adminApi.changeUserRole(roleChangeTarget.user.userSeq, { role: roleChangeTarget.newRole });
      toast.success(`${roleChangeTarget.user.name}'s role changed to ${roleChangeTarget.newRole}`);
      loadUsers(page, roleFilter, searchTerm);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to change role';
      toast.error(message);
    } finally {
      setChangingRole(false);
      setRoleChangeTarget(null);
    }
  };

  const handleApproval = async () => {
    if (!approvalTarget) return;
    setProcessingApproval(true);
    try {
      if (approvalTarget.action === 'approve') {
        await adminApi.approveLew(approvalTarget.user.userSeq);
        toast.success(`${approvalTarget.user.name} has been approved as LEW`);
      } else {
        await adminApi.rejectLew(approvalTarget.user.userSeq);
        toast.success(`${approvalTarget.user.name}'s LEW registration has been rejected`);
      }
      loadUsers(page, roleFilter, searchTerm);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to process approval';
      toast.error(message);
    } finally {
      setProcessingApproval(false);
      setApprovalTarget(null);
    }
  };

  const getRoleBadgeVariant = (role: string) => {
    switch (role) {
      case 'ADMIN': return 'primary' as const;
      case 'LEW': return 'info' as const;
      default: return 'gray' as const;
    }
  };

  const getApprovalBadgeVariant = (status?: ApprovalStatus) => {
    switch (status) {
      case 'APPROVED': return 'success' as const;
      case 'REJECTED': return 'error' as const;
      case 'PENDING': return 'warning' as const;
      default: return 'gray' as const;
    }
  };

  const columns: Column<User>[] = [
    {
      key: 'userSeq',
      header: 'ID',
      width: '60px',
      render: (user) => (
        <span className="font-mono text-xs text-gray-500">#{user.userSeq}</span>
      ),
    },
    {
      key: 'name',
      header: 'Name',
      sortable: true,
      render: (user) => (
        <div>
          <span className="font-medium text-gray-800">{user.name}</span>
          {user.companyName && (
            <div className="text-xs text-gray-500 mt-0.5">{user.companyName}{user.uen ? ` (${user.uen})` : ''}</div>
          )}
        </div>
      ),
    },
    {
      key: 'email',
      header: 'Email',
      sortable: true,
      render: (user) => (
        <span className="text-gray-600">{user.email}</span>
      ),
    },
    {
      key: 'phone',
      header: 'Phone',
      render: (user) => (
        <span className="text-gray-600">{user.phone || '-'}</span>
      ),
    },
    {
      key: 'role',
      header: 'Role',
      render: (user) => (
        <div className="flex items-center gap-2">
          <Badge variant={getRoleBadgeVariant(user.role)}>
            {user.role}
          </Badge>
          {user.role !== 'ADMIN' && (
            <button
              onClick={() => {
                const newRole: UserRole = user.role === 'APPLICANT' ? 'LEW' : 'APPLICANT';
                setRoleChangeTarget({ user, newRole });
              }}
              className="text-xs text-primary hover:text-primary/80 hover:underline"
              title={`Change to ${user.role === 'APPLICANT' ? 'LEW' : 'APPLICANT'}`}
            >
              Change
            </button>
          )}
        </div>
      ),
    },
    {
      key: 'approvedStatus' as keyof User,
      header: 'Approval',
      render: (user) => {
        if (user.role !== 'LEW') return <span className="text-gray-400">-</span>;
        return (
          <div className="flex items-center gap-2">
            <Badge variant={getApprovalBadgeVariant(user.approvedStatus)}>
              {user.approvedStatus || 'N/A'}
            </Badge>
            {user.approvedStatus !== 'APPROVED' && (
              <button
                onClick={() => setApprovalTarget({ user, action: 'approve' })}
                className="text-xs text-success-600 hover:text-success-700 hover:underline"
              >
                Approve
              </button>
            )}
            {user.approvedStatus === 'PENDING' && (
              <button
                onClick={() => setApprovalTarget({ user, action: 'reject' })}
                className="text-xs text-error-600 hover:text-error-700 hover:underline"
              >
                Reject
              </button>
            )}
          </div>
        );
      },
    },
    {
      key: 'lewLicenceNo' as keyof User,
      header: 'Licence No.',
      render: (user) => (
        <span className={user.lewLicenceNo ? 'text-gray-700 font-mono text-xs' : 'text-gray-400'}>
          {user.lewLicenceNo || '-'}
        </span>
      ),
    },
    {
      key: 'createdAt',
      header: 'Registered',
      sortable: true,
      render: (user) => (
        <span className="text-gray-500 text-xs">
          {new Date(user.createdAt).toLocaleDateString()}
        </span>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">User Management</h1>
        <p className="text-sm text-gray-500 mt-1">View and manage registered users</p>
      </div>

      {/* Search & Filter */}
      <Card>
        <div className="flex flex-col sm:flex-row gap-3">
          <div className="flex-1 flex gap-2">
            <Input
              placeholder="Search by name, email, company, or UEN..."
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              onKeyDown={handleSearchKeyDown}
            />
            <button
              onClick={handleSearch}
              className="px-4 py-2 bg-primary text-white text-sm rounded-lg hover:bg-primary/90 transition-colors whitespace-nowrap"
            >
              Search
            </button>
            {searchTerm && (
              <button
                onClick={handleClearSearch}
                className="px-3 py-2 text-sm text-gray-500 hover:text-gray-700 transition-colors whitespace-nowrap"
              >
                Clear
              </button>
            )}
          </div>
          <select
            value={roleFilter}
            onChange={handleRoleFilterChange}
            className="px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary sm:w-40"
          >
            {ROLE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>
      </Card>

      {/* User table */}
      <DataTable
        columns={columns}
        data={users}
        loading={loading}
        keyExtractor={(user) => user.userSeq}
        emptyIcon="👥"
        emptyTitle="No users found"
        emptyDescription={
          searchTerm || roleFilter
            ? 'No users matching the current filters.'
            : 'Registered users will be listed here.'
        }
      />

      {/* Pagination + Summary */}
      {!loading && totalElements > 0 && (
        <div className="flex flex-col items-center gap-2">
          <Pagination page={page} totalPages={totalPages} onPageChange={handlePageChange} />
          <div className="text-sm text-gray-500">
            Showing {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, totalElements)} of {totalElements} users
            {(searchTerm || roleFilter) && ' (filtered)'}
            {totalPages > 1 && ` · Page ${page + 1} of ${totalPages}`}
          </div>
        </div>
      )}

      {/* Role change confirmation */}
      <ConfirmDialog
        isOpen={!!roleChangeTarget}
        title="Change User Role"
        message={
          roleChangeTarget
            ? `Are you sure you want to change ${roleChangeTarget.user.name}'s role from ${roleChangeTarget.user.role} to ${roleChangeTarget.newRole}?`
            : ''
        }
        confirmLabel="Change Role"
        loading={changingRole}
        onConfirm={handleRoleChange}
        onClose={() => setRoleChangeTarget(null)}
      />

      {/* LEW approval confirmation */}
      <ConfirmDialog
        isOpen={!!approvalTarget}
        title={approvalTarget?.action === 'approve' ? 'Approve LEW' : 'Reject LEW'}
        message={
          approvalTarget
            ? approvalTarget.action === 'approve'
              ? `Are you sure you want to approve ${approvalTarget.user.name} as LEW? They will be able to manage applications after re-login.`
              : `Are you sure you want to reject ${approvalTarget.user.name}'s LEW registration? They will not be able to access the system.`
            : ''
        }
        confirmLabel={approvalTarget?.action === 'approve' ? 'Approve' : 'Reject'}
        variant={approvalTarget?.action === 'reject' ? 'danger' : 'primary'}
        loading={processingApproval}
        onConfirm={handleApproval}
        onClose={() => setApprovalTarget(null)}
      />
    </div>
  );
}
