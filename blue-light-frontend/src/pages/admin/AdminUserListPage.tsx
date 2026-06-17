import { useCallback, useEffect, useState } from 'react';
import { fullName } from '../../utils/formatName';
import { Card } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../components/ui/Modal';
import { DataTable, type Column } from '../../components/data/DataTable';
import { Pagination } from '../../components/data/Pagination';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { PageHeader } from '../../components/ui/PageHeader';
import { useToastStore } from '../../stores/toastStore';
import adminApi from '../../api/adminApi';
import type { User, UserRole, ApprovalStatus, LewGrade } from '../../types';

const LEW_GRADE_OPTIONS: { value: LewGrade; label: string; desc: string }[] = [
  { value: 'GRADE_7', label: 'Grade 7', desc: '≤ 45 kVA' },
  { value: 'GRADE_8', label: 'Grade 8', desc: '≤ 500 kVA' },
  { value: 'GRADE_9', label: 'Grade 9', desc: '≤ 400 kV' },
];
import { useShallow } from 'zustand/react/shallow';
import { useRoleStore, selectRoleLabels, selectAssignableRoles, selectFilterableRoles } from '../../stores/roleStore';

const PAGE_SIZE = 20;

export default function AdminUserListPage() {
  const toast = useToastStore();
  // useShallow: 각 selector가 매 호출마다 새 객체/배열을 반환하므로 얕은 비교 필수
  const roleLabels = useRoleStore(useShallow(selectRoleLabels));
  const assignableRoles = useRoleStore(useShallow(selectAssignableRoles));
  const filterableRoles = useRoleStore(useShallow(selectFilterableRoles));
  const roleOptions = [
    { value: '', label: 'All Roles' },
    ...filterableRoles.map((role) => ({ value: role, label: roleLabels[role] })),
  ];
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
  // LEW 승격 시 함께 등록하는 면허번호·등급
  const [lewLicenceNo, setLewLicenceNo] = useState('');
  const [lewGrade, setLewGrade] = useState<LewGrade | ''>('');
  const [approvalTarget, setApprovalTarget] = useState<{ user: User; action: 'approve' | 'reject' } | null>(null);
  const [processingApproval, setProcessingApproval] = useState(false);
  // LEW 초대
  const [inviteOpen, setInviteOpen] = useState(false);
  const [inviteForm, setInviteForm] = useState({ email: '', firstName: '', lastName: '' });
  const [inviting, setInviting] = useState(false);
  const [resendingId, setResendingId] = useState<number | null>(null);

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

  const closeRoleChange = () => {
    setRoleChangeTarget(null);
    setLewLicenceNo('');
    setLewGrade('');
  };

  const handleRoleChange = async () => {
    if (!roleChangeTarget) return;
    const isLew = roleChangeTarget.newRole === 'LEW';
    if (isLew && (!lewLicenceNo.trim() || !lewGrade)) {
      toast.error('LEW licence number and grade are required');
      return;
    }
    setChangingRole(true);
    try {
      await adminApi.changeUserRole(roleChangeTarget.user.userSeq, {
        role: roleChangeTarget.newRole,
        ...(isLew ? { lewLicenceNo: lewLicenceNo.trim(), lewGrade } : {}),
      });
      toast.success(`${fullName(roleChangeTarget.user.firstName, roleChangeTarget.user.lastName)}'s role changed to ${roleChangeTarget.newRole}`);
      loadUsers(page, roleFilter, searchTerm);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to change role';
      toast.error(message);
    } finally {
      setChangingRole(false);
      closeRoleChange();
    }
  };

  const handleApproval = async () => {
    if (!approvalTarget) return;
    setProcessingApproval(true);
    try {
      if (approvalTarget.action === 'approve') {
        await adminApi.approveLew(approvalTarget.user.userSeq);
        toast.success(`${fullName(approvalTarget.user.firstName, approvalTarget.user.lastName)} has been approved as LEW`);
      } else {
        await adminApi.rejectLew(approvalTarget.user.userSeq);
        toast.success(`${fullName(approvalTarget.user.firstName, approvalTarget.user.lastName)}'s LEW registration has been rejected`);
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

  const closeInvite = () => {
    setInviteOpen(false);
    setInviteForm({ email: '', firstName: '', lastName: '' });
  };

  const inviteValid =
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(inviteForm.email.trim()) &&
    inviteForm.firstName.trim().length > 0 &&
    inviteForm.lastName.trim().length > 0;

  const handleInvite = async () => {
    if (!inviteValid) return;
    setInviting(true);
    try {
      await adminApi.inviteLew({
        email: inviteForm.email.trim(),
        firstName: inviteForm.firstName.trim(),
        lastName: inviteForm.lastName.trim(),
      });
      toast.success(`Invitation sent to ${inviteForm.email.trim()}`);
      closeInvite();
      loadUsers(page, roleFilter, searchTerm);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to send invitation';
      toast.error(message);
    } finally {
      setInviting(false);
    }
  };

  const handleResendInvite = async (user: User) => {
    setResendingId(user.userSeq);
    try {
      await adminApi.resendLewInvite(user.userSeq);
      toast.success(`Invitation resent to ${user.email}`);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to resend invitation';
      toast.error(message);
    } finally {
      setResendingId(null);
    }
  };

  const getRoleBadgeVariant = (role: string) => {
    switch (role) {
      case 'ADMIN': return 'primary' as const;
      case 'LEW': return 'info' as const;
      case 'SLD_MANAGER': return 'warning' as const;
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
          <span className="font-medium text-gray-800">{fullName(user.firstName, user.lastName)}</span>
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
          {user.role !== 'ADMIN' && user.role !== 'SYSTEM_ADMIN' && (
            <select
              className="text-xs border border-gray-200 rounded px-1 py-0.5 text-primary cursor-pointer hover:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
              defaultValue=""
              onChange={(e) => {
                if (e.target.value) {
                  const newRole = e.target.value as UserRole;
                  // LEW 승격이면 기존 면허/등급이 있으면 프리필
                  setLewLicenceNo(newRole === 'LEW' ? (user.lewLicenceNo ?? '') : '');
                  setLewGrade(newRole === 'LEW' ? (user.lewGrade ?? '') : '');
                  setRoleChangeTarget({ user, newRole });
                  e.target.value = '';
                }
              }}
              aria-label={`Change ${fullName(user.firstName, user.lastName)}'s role`}
            >
              <option value="" disabled>Change</option>
              {assignableRoles.filter((r) => r !== user.role).map((r) => (
                <option key={r} value={r}>{roleLabels[r]}</option>
              ))}
            </select>
          )}
        </div>
      ),
    },
    {
      key: 'approvedStatus' as keyof User,
      header: 'Approval',
      render: (user) => {
        if (user.role !== 'LEW') return <span className="text-gray-400">-</span>;
        // 초대된 LEW(미활성): 셋업 완료 시 자동 승인되므로 Approve/Reject 대신 "Invited" + Resend.
        if (user.status === 'PENDING_ACTIVATION') {
          return (
            <div className="flex items-center gap-2">
              <Badge variant="warning">Invited</Badge>
              <button
                onClick={() => handleResendInvite(user)}
                disabled={resendingId === user.userSeq}
                className="text-xs text-primary hover:underline disabled:opacity-50"
                aria-label={`Resend invitation to ${user.email}`}
              >
                {resendingId === user.userSeq ? 'Resending…' : 'Resend'}
              </button>
            </div>
          );
        }
        return (
          <div className="flex items-center gap-2">
            <Badge variant={getApprovalBadgeVariant(user.approvedStatus)}>
              {user.approvedStatus || 'N/A'}
            </Badge>
            {user.approvedStatus !== 'APPROVED' && (
              <button
                onClick={() => setApprovalTarget({ user, action: 'approve' })}
                className="text-xs text-success-600 hover:text-success-700 hover:underline"
                aria-label={`Approve ${fullName(user.firstName, user.lastName)} as LEW`}
              >
                Approve
              </button>
            )}
            {user.approvedStatus === 'PENDING' && (
              <button
                onClick={() => setApprovalTarget({ user, action: 'reject' })}
                className="text-xs text-error-600 hover:text-error-700 hover:underline"
                aria-label={`Reject ${fullName(user.firstName, user.lastName)}'s LEW registration`}
              >
                Reject
              </button>
            )}
          </div>
        );
      },
    },
    {
      key: 'lewGrade' as keyof User,
      header: 'Grade',
      render: (user) => {
        if (user.role !== 'LEW' || !user.lewGrade) return <span className="text-gray-400">-</span>;
        const gradeNum = user.lewGrade.replace('GRADE_', '');
        const maxKva = user.lewGrade === 'GRADE_7' ? 45 : user.lewGrade === 'GRADE_8' ? 500 : 9999;
        return (
          <Badge variant="info" className="text-[10px]">
            G{gradeNum} (≤{maxKva === 9999 ? '400kV' : `${maxKva}kVA`})
          </Badge>
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
    <div className="max-w-7xl mx-auto space-y-6">
      <PageHeader
        title="User Management"
        subtitle="View and manage registered users"
        actions={
          <Button onClick={() => setInviteOpen(true)}>
            + Invite LEW
          </Button>
        }
      />

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
          <div className="sm:w-40">
            <Select
              value={roleFilter}
              onChange={handleRoleFilterChange}
              options={roleOptions}
            />
          </div>
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

      {/* Role change — LEW 승격은 면허·등급 입력 모달, 그 외는 단순 확인 */}
      <ConfirmDialog
        isOpen={!!roleChangeTarget && roleChangeTarget.newRole !== 'LEW'}
        title="Change User Role"
        message={
          roleChangeTarget
            ? `Are you sure you want to change ${fullName(roleChangeTarget.user.firstName, roleChangeTarget.user.lastName)}'s role from ${roleChangeTarget.user.role} to ${roleChangeTarget.newRole}?`
            : ''
        }
        confirmLabel="Change Role"
        loading={changingRole}
        onConfirm={handleRoleChange}
        onClose={closeRoleChange}
      />

      <Modal
        isOpen={!!roleChangeTarget && roleChangeTarget.newRole === 'LEW'}
        onClose={closeRoleChange}
        ariaLabelledBy="promote-lew-title"
      >
        <ModalHeader title="Promote to LEW" onClose={closeRoleChange} />
        <ModalBody className="space-y-4">
          {roleChangeTarget && (
            <p className="text-sm text-gray-600">
              Promoting{' '}
              <span className="font-medium text-gray-800">
                {fullName(roleChangeTarget.user.firstName, roleChangeTarget.user.lastName)}
              </span>{' '}
              ({roleChangeTarget.user.role}) to LEW. Register their licence details below.
            </p>
          )}
          <p className="text-xs text-warning-600">
            ⚠ The user will be set to <strong>PENDING approval</strong> and must be approved before they can manage applications.
          </p>
          <Input
            label="LEW Licence Number"
            required
            maxLength={50}
            value={lewLicenceNo}
            onChange={(e) => setLewLicenceNo(e.target.value)}
            placeholder="0/00000"
            hint="The EMA-issued LEW licence number (format: grade/serial)"
          />
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              LEW Grade<span className="text-error-500 ml-0.5">*</span>
            </label>
            <div className="grid grid-cols-3 gap-2">
              {LEW_GRADE_OPTIONS.map((g) => (
                <button
                  key={g.value}
                  type="button"
                  onClick={() => setLewGrade(g.value)}
                  className={`p-2.5 border-2 rounded-lg text-center transition-all ${
                    lewGrade === g.value
                      ? 'border-primary bg-primary/5 text-primary'
                      : 'border-gray-200 bg-white text-gray-600 hover:border-gray-300'
                  }`}
                >
                  <div className="text-sm font-medium">{g.label}</div>
                  <div className="text-xs text-gray-500 mt-0.5">{g.desc}</div>
                </button>
              ))}
            </div>
            <p className="text-xs text-gray-500 mt-1">Select the grade on the EMA licence</p>
          </div>
        </ModalBody>
        <ModalFooter>
          <Button variant="ghost" onClick={closeRoleChange} disabled={changingRole}>
            Cancel
          </Button>
          <Button
            onClick={handleRoleChange}
            loading={changingRole}
            disabled={!lewLicenceNo.trim() || !lewGrade}
          >
            Promote to LEW
          </Button>
        </ModalFooter>
      </Modal>

      {/* LEW 초대 모달 */}
      <Modal isOpen={inviteOpen} onClose={closeInvite} ariaLabelledBy="invite-lew-title">
        <ModalHeader title="Invite a LEW" onClose={closeInvite} />
        <ModalBody className="space-y-4">
          <p className="text-sm text-gray-600">
            Send an invitation email. The LEW will set their own password, licence number, grade
            and PayNow details, and the account is approved automatically on completion.
          </p>
          <Input
            label="Email"
            type="email"
            required
            maxLength={100}
            value={inviteForm.email}
            onChange={(e) => setInviteForm((f) => ({ ...f, email: e.target.value }))}
            placeholder="lew@example.com"
          />
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="First name"
              required
              maxLength={50}
              value={inviteForm.firstName}
              onChange={(e) => setInviteForm((f) => ({ ...f, firstName: e.target.value }))}
            />
            <Input
              label="Last name"
              required
              maxLength={50}
              value={inviteForm.lastName}
              onChange={(e) => setInviteForm((f) => ({ ...f, lastName: e.target.value }))}
            />
          </div>
        </ModalBody>
        <ModalFooter>
          <Button variant="ghost" onClick={closeInvite} disabled={inviting}>
            Cancel
          </Button>
          <Button onClick={handleInvite} loading={inviting} disabled={!inviteValid}>
            Send invitation
          </Button>
        </ModalFooter>
      </Modal>

      {/* LEW approval confirmation */}
      <ConfirmDialog
        isOpen={!!approvalTarget}
        title={approvalTarget?.action === 'approve' ? 'Approve LEW' : 'Reject LEW'}
        message={
          approvalTarget
            ? approvalTarget.action === 'approve'
              ? `Are you sure you want to approve ${fullName(approvalTarget.user.firstName, approvalTarget.user.lastName)} as LEW? They will be able to manage applications after re-login.`
              : `Are you sure you want to reject ${fullName(approvalTarget.user.firstName, approvalTarget.user.lastName)}'s LEW registration? They will not be able to access the system.`
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
