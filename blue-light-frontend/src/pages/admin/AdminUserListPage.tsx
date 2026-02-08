import { useEffect, useState } from 'react';
import { Card } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Badge } from '../../components/ui/Badge';
import { DataTable, type Column } from '../../components/data/DataTable';
import { useToastStore } from '../../stores/toastStore';
import adminApi from '../../api/adminApi';
import type { User } from '../../types';

export default function AdminUserListPage() {
  const toast = useToastStore();
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');

  useEffect(() => {
    adminApi
      .getUsers()
      .then(setUsers)
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load users');
      })
      .finally(() => setLoading(false));
  }, []);

  // Client-side search
  const filteredUsers = searchTerm
    ? users.filter(
        (user) =>
          user.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
          user.email.toLowerCase().includes(searchTerm.toLowerCase()) ||
          user.role.toLowerCase().includes(searchTerm.toLowerCase())
      )
    : users;

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
        <Badge variant={user.role === 'ADMIN' ? 'primary' : 'gray'}>
          {user.role}
        </Badge>
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
        <h1 className="text-2xl font-bold text-gray-800">User Management</h1>
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
          <div className="flex gap-4">
            <span>
              Applicants: {users.filter((u) => u.role === 'APPLICANT').length}
            </span>
            <span>
              Admins: {users.filter((u) => u.role === 'ADMIN').length}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
