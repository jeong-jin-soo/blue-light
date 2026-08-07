import { useCallback, useEffect, useState } from 'react';
import { Copy, Check } from 'lucide-react';
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
import { LEW_GRADES as LEW_GRADE_OPTIONS } from '../../constants/lewGrade';
import { useShallow } from 'zustand/react/shallow';
import { useRoleStore, selectRoleLabels, selectAssignableRoles, selectFilterableRoles } from '../../stores/roleStore';

const PAGE_SIZE = 20;

export default function AdminUserListPage() {
  // 전체 스토어 구독 금지: toasts 배열 변경마다 재렌더 → loadUsers 재생성 → useEffect 재실행 → 에러 토스트 무한루프
  const toastError = useToastStore((s) => s.error);
  const toastSuccess = useToastStore((s) => s.success);
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
  // PayNow reveal (지급용 전체값 — 클릭 시 서버가 열람 감사 기록)
  const [revealedPaynow, setRevealedPaynow] = useState<Record<number, string>>({});
  const [revealingId, setRevealingId] = useState<number | null>(null);
  const [copiedPaynowId, setCopiedPaynowId] = useState<number | null>(null);
  // 상세 보기 모달 — 목록에서 뺀 항목 + 미노출 정보를 팝업으로
  const [detailUser, setDetailUser] = useState<User | null>(null);

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
        toastError(err.message || 'Failed to load users');
      })
      .finally(() => setLoading(false));
  }, [toastError]);

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
      toastError('LEW licence number and grade are required');
      return;
    }
    setChangingRole(true);
    try {
      await adminApi.changeUserRole(roleChangeTarget.user.userSeq, {
        role: roleChangeTarget.newRole,
        ...(isLew ? { lewLicenceNo: lewLicenceNo.trim(), lewGrade } : {}),
      });
      toastSuccess(`${fullName(roleChangeTarget.user.firstName, roleChangeTarget.user.lastName)}'s role changed to ${roleChangeTarget.newRole}`);
      loadUsers(page, roleFilter, searchTerm);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to change role';
      toastError(message);
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
        toastSuccess(`${fullName(approvalTarget.user.firstName, approvalTarget.user.lastName)} has been approved as LEW`);
      } else {
        await adminApi.rejectLew(approvalTarget.user.userSeq);
        toastSuccess(`${fullName(approvalTarget.user.firstName, approvalTarget.user.lastName)}'s LEW registration has been rejected`);
      }
      loadUsers(page, roleFilter, searchTerm);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to process approval';
      toastError(message);
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
      toastSuccess(`Invitation sent to ${inviteForm.email.trim()}`);
      closeInvite();
      loadUsers(page, roleFilter, searchTerm);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to send invitation';
      toastError(message);
    } finally {
      setInviting(false);
    }
  };

  const handleResendInvite = async (user: User) => {
    setResendingId(user.userSeq);
    try {
      await adminApi.resendLewInvite(user.userSeq);
      toastSuccess(`Invitation resent to ${user.email}`);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to resend invitation';
      toastError(message);
    } finally {
      setResendingId(null);
    }
  };

  const handleRevealPaynow = async (user: User) => {
    setRevealingId(user.userSeq);
    try {
      const res = await adminApi.revealPaynow(user.userSeq);
      setRevealedPaynow((m) => ({ ...m, [user.userSeq]: res.paynowValue ?? '-' }));
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to reveal PayNow';
      toastError(message);
    } finally {
      setRevealingId(null);
    }
  };

  const handleCopyPaynow = async (user: User) => {
    const value = revealedPaynow[user.userSeq];
    if (!value || value === '-') return;
    try {
      await navigator.clipboard.writeText(value);
      setCopiedPaynowId(user.userSeq);
      toastSuccess('PayNow copied to clipboard');
      window.setTimeout(() => {
        setCopiedPaynowId((id) => (id === user.userSeq ? null : id));
      }, 1500);
    } catch {
      toastError('Failed to copy PayNow');
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
      key: 'detail' as keyof User,
      header: '',
      width: '90px',
      render: (user) => (
        <button
          onClick={() => setDetailUser(user)}
          className="text-xs px-2.5 py-1 border border-gray-200 rounded-md text-primary hover:border-primary hover:bg-primary/5 transition-colors whitespace-nowrap"
          aria-label={`View details for ${fullName(user.firstName, user.lastName)}`}
        >
          Details
        </button>
      ),
    },
  ];

  const gradeText = (g?: string | null) => {
    if (!g) return null;
    const n = g.replace('GRADE_', '');
    const max = g === 'GRADE_7' ? '≤ 45 kVA' : g === 'GRADE_8' ? '≤ 500 kVA' : '≤ 400 kV';
    return `Grade ${n} (${max})`;
  };

  const DetailRow = ({ label, value }: { label: string; value: React.ReactNode }) => (
    <div className="flex justify-between gap-4 py-2 border-b border-gray-50 last:border-b-0">
      <span className="text-xs text-gray-500 shrink-0 pt-0.5">{label}</span>
      <span className="text-sm text-gray-800 text-right break-all">
        {value === null || value === undefined || value === ''
          ? <span className="text-gray-400">-</span>
          : value}
      </span>
    </div>
  );

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

      {/* 사용자 상세 — 목록에서 뺀 항목 + 미노출 정보 */}
      <Modal isOpen={!!detailUser} onClose={() => setDetailUser(null)} ariaLabelledBy="user-detail-title">
        {detailUser && (
          <>
            <ModalHeader
              title={fullName(detailUser.firstName, detailUser.lastName)}
              onClose={() => setDetailUser(null)}
            />
            <ModalBody className="space-y-5">
              {/* 계정 */}
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-wide text-gray-400 mb-1">Account</p>
                <DetailRow label="ID" value={<span className="font-mono">#{detailUser.userSeq}</span>} />
                <DetailRow label="Email" value={detailUser.email} />
                <DetailRow label="Phone" value={detailUser.phone} />
                <DetailRow label="Role" value={<Badge variant={getRoleBadgeVariant(detailUser.role)}>{roleLabels[detailUser.role] ?? detailUser.role}</Badge>} />
                <DetailRow
                  label="Account status"
                  value={
                    detailUser.status === 'PENDING_ACTIVATION'
                      ? <Badge variant="warning">Invited (pending activation)</Badge>
                      : <Badge variant={detailUser.status === 'ACTIVE' ? 'success' : 'gray'}>{detailUser.status ?? '-'}</Badge>
                  }
                />
                <DetailRow label="Registered" value={detailUser.createdAt ? new Date(detailUser.createdAt).toLocaleString() : null} />
              </div>

              {/* LEW 자격 */}
              {detailUser.role === 'LEW' && (
                <div>
                  <p className="text-[11px] font-semibold uppercase tracking-wide text-gray-400 mb-1">LEW</p>
                  <DetailRow
                    label="Approval"
                    value={<Badge variant={getApprovalBadgeVariant(detailUser.approvedStatus)}>{detailUser.approvedStatus ?? 'N/A'}</Badge>}
                  />
                  <DetailRow label="Licence No." value={detailUser.lewLicenceNo && <span className="font-mono">{detailUser.lewLicenceNo}</span>} />
                  <DetailRow label="Grade" value={gradeText(detailUser.lewGrade)} />
                  <DetailRow
                    label="PayNow"
                    value={
                      detailUser.paynowValueMasked ? (
                        <span className="inline-flex items-center gap-2">
                          <span className="font-mono">{revealedPaynow[detailUser.userSeq] ?? detailUser.paynowValueMasked}</span>
                          <span className="text-[10px] text-gray-400">{detailUser.paynowType === 'MOBILE' ? 'Mobile' : 'UEN'}</span>
                          {!revealedPaynow[detailUser.userSeq] ? (
                            <button
                              onClick={() => handleRevealPaynow(detailUser)}
                              disabled={revealingId === detailUser.userSeq}
                              className="text-xs text-primary hover:underline disabled:opacity-50"
                            >
                              {revealingId === detailUser.userSeq ? '…' : 'Reveal'}
                            </button>
                          ) : (
                            <button
                              type="button"
                              onClick={() => handleCopyPaynow(detailUser)}
                              title="Copy PayNow"
                              aria-label="Copy PayNow"
                              className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                            >
                              {copiedPaynowId === detailUser.userSeq ? (
                                <><Check className="w-3.5 h-3.5" /> Copied</>
                              ) : (
                                <><Copy className="w-3.5 h-3.5" /> Copy</>
                              )}
                            </button>
                          )}
                        </span>
                      ) : null
                    }
                  />
                </div>
              )}

              {/* 사업자/연락 정보 */}
              <div>
                <p className="text-[11px] font-semibold uppercase tracking-wide text-gray-400 mb-1">Business / Correspondence</p>
                <DetailRow label="Company" value={detailUser.companyName} />
                <DetailRow label="UEN" value={detailUser.uen} />
                <DetailRow label="Designation" value={detailUser.designation} />
                <DetailRow label="Address" value={detailUser.correspondenceAddress} />
                <DetailRow label="Postal code" value={detailUser.correspondencePostalCode} />
              </div>
            </ModalBody>
            <ModalFooter>
              <Button variant="ghost" onClick={() => setDetailUser(null)}>Close</Button>
            </ModalFooter>
          </>
        )}
      </Modal>
    </div>
  );
}
