import { useEffect, useState } from 'react';
import { Card } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Badge } from '../../components/ui/Badge';
import { DataTable, type Column } from '../../components/data/DataTable';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { useToastStore } from '../../stores/toastStore';
import adminApi from '../../api/adminApi';
import type { User, UserRole, ApprovalStatus } from '../../types';

export default function AdminUserListPage() {
  const toast = useToastStore();
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [roleChangeTarget, setRoleChangeTarget] = useState<{ user: User; newRole: UserRole } | null>(null);
  const [changingRole, setChangingRole] = useState(false);
  const [approvalTarget, setApprovalTarget] = useState<{ user: User; action: 'approve' | 'reject' } | null>(null);
  const [processingApproval, setProcessingApproval] = useState(false);

  const loadUsers = () => {
    setLoading(true);
    adminApi
      .getUsers()
      .then(setUsers)
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load users');
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadUsers();
  }, []);

  const handleRoleChange = async () => {
    if (!roleChangeTarget) return;
    setChangingRole(true);
    try {
      await adminApi.changeUserRole(roleChangeTarget.user.userSeq, { role: roleChangeTarget.newRole });
      toast.success(`${roleChangeTarget.user.name}'s role changed to ${roleChangeTarget.newRole}`);
      loadUsers();
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to change role';
      toast.error(message);
    } finally {
      setChangingRole(false);
      setRoleChangeTarget(null);
    }
  };

  // Client-side search
  const filteredUsers = searchTerm
    ? users.filter(
        (user) =>
          user.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
          user.email.toLowerCase().includes(searchTerm.toLowerCase()) ||
          user.role.toLowerCase().includes(searchTerm.toLowerCase())
      )
    : users;

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
      loadUsers();
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
        <span className="font-medium text-gray-800">{user.name}</span>
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

      {/* Search */}
      <Card>
        <Input
          placeholder="Search by name, email, or role..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
        />
      </Card>

      {/* User table */}
      <DataTable
        columns={columns}
        data={filteredUsers}
        loading={loading}
        keyExtractor={(user) => user.userSeq}
        emptyIcon="👥"
        emptyTitle="No users found"
        emptyDescription={
          searchTerm
            ? `No users matching "${searchTerm}".`
            : 'Registered users will be listed here.'
        }
      />

      {/* Summary */}
      {!loading && users.length > 0 && (
        <div className="flex items-center justify-between text-sm text-gray-500 px-1">
          <span>
            Showing {filteredUsers.length} of {users.length} users
          </span>
          <div className="flex gap-4 flex-wrap">
            <span>
              Applicants: {users.filter((u) => u.role === 'APPLICANT').length}
            </span>
            <span>
              LEWs: {users.filter((u) => u.role === 'LEW').length}
            </span>
            {users.filter((u) => u.role === 'LEW' && u.approvedStatus === 'PENDING').length > 0 && (
              <span className="text-warning-600 font-medium">
                Pending LEW: {users.filter((u) => u.role === 'LEW' && u.approvedStatus === 'PENDING').length}
              </span>
            )}
            <span>
              Admins: {users.filter((u) => u.role === 'ADMIN').length}
            </span>
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
