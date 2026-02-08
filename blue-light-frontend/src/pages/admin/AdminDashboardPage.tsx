import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { DashboardCard } from '../../components/domain/DashboardCard';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { EmptyState } from '../../components/ui/EmptyState';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import adminApi from '../../api/adminApi';
import type { AdminApplication, AdminDashboard } from '../../types';

export default function AdminDashboardPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const { user: currentUser } = useAuthStore();
  const isAdmin = currentUser?.role === 'ADMIN';

  const [dashboard, setDashboard] = useState<AdminDashboard | null>(null);
  const [recentApps, setRecentApps] = useState<AdminApplication[]>([]);
  const [loading, setLoading] = useState(true);
  const [lewRegistrationOpen, setLewRegistrationOpen] = useState(true);
  const [settingsLoading, setSettingsLoading] = useState(false);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const promises: Promise<unknown>[] = [
          adminApi.getDashboard(),
          adminApi.getApplications(0, 5),
        ];
        // ADMIN만 설정 조회
        if (isAdmin) {
          promises.push(adminApi.getSettings());
        }

        const results = await Promise.all(promises);
        setDashboard(results[0] as AdminDashboard);
        setRecentApps((results[1] as { content: AdminApplication[] }).content);

        if (isAdmin && results[2]) {
          const settings = results[2] as Record<string, string>;
          setLewRegistrationOpen(settings['lew_registration_open'] === 'true');
        }
      } catch (err: unknown) {
        const error = err as { message?: string };
        toast.error(error.message || 'Failed to load dashboard data');
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, []);

  const handleToggleLewRegistration = async () => {
    const newValue = !lewRegistrationOpen;
    setSettingsLoading(true);
    try {
      await adminApi.updateSettings({ lew_registration_open: String(newValue) });
      setLewRegistrationOpen(newValue);
      toast.success(newValue ? 'LEW registration opened' : 'LEW registration closed');
    } catch {
      toast.error('Failed to update setting');
    } finally {
      setSettingsLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading dashboard..." />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">Admin Dashboard</h1>
        <p className="text-sm text-gray-500 mt-1">Platform overview and key metrics</p>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-8 gap-4">
        <DashboardCard
          label="Total Applications"
          value={dashboard?.totalApplications ?? 0}
          icon="📋"
        />
        <DashboardCard
          label="Pending Review"
          value={dashboard?.pendingReview ?? 0}
          icon="🔍"
          onClick={() => navigate('/admin/applications?status=PENDING_REVIEW')}
        />
        <DashboardCard
          label="Revision Requested"
          value={dashboard?.revisionRequested ?? 0}
          icon="📝"
          onClick={() => navigate('/admin/applications?status=REVISION_REQUESTED')}
        />
        <DashboardCard
          label="Pending Payment"
          value={dashboard?.pendingPayment ?? 0}
          icon="💳"
          onClick={() => navigate('/admin/applications?status=PENDING_PAYMENT')}
        />
        <DashboardCard
          label="Paid"
          value={dashboard?.paid ?? 0}
          icon="✅"
          onClick={() => navigate('/admin/applications?status=PAID')}
        />
        <DashboardCard
          label="In Progress"
          value={dashboard?.inProgress ?? 0}
          icon="🔄"
          onClick={() => navigate('/admin/applications?status=IN_PROGRESS')}
        />
        <DashboardCard
          label="Completed"
          value={dashboard?.completed ?? 0}
          icon="🏁"
          onClick={() => navigate('/admin/applications?status=COMPLETED')}
        />
        <DashboardCard
          label="Total Users"
          value={dashboard?.totalUsers ?? 0}
          icon="👥"
          onClick={() => navigate('/admin/users')}
        />
        {isAdmin && (
          <DashboardCard
            label="Unassigned"
            value={dashboard?.unassigned ?? 0}
            icon="⚡"
          />
        )}
      </div>

      {/* ADMIN-only: LEW Registration Setting */}
      {isAdmin && (
        <Card>
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold text-gray-800">LEW Registration</h3>
              <p className="text-xs text-gray-500 mt-0.5">
                {lewRegistrationOpen
                  ? 'New LEW sign-ups are currently allowed.'
                  : 'New LEW sign-ups are currently blocked.'}
              </p>
            </div>
            <button
              type="button"
              disabled={settingsLoading}
              onClick={handleToggleLewRegistration}
              className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-primary/20 disabled:opacity-50 ${
                lewRegistrationOpen ? 'bg-primary' : 'bg-gray-300'
              }`}
            >
              <span
                className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                  lewRegistrationOpen ? 'translate-x-5' : 'translate-x-0'
                }`}
              />
            </button>
          </div>
        </Card>
      )}

      {/* Recent applications */}
      <Card>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-gray-800">Recent Applications</h2>
          {recentApps.length > 0 && (
            <Button variant="ghost" size="sm" onClick={() => navigate('/admin/applications')}>
              View All
            </Button>
          )}
        </div>

        {recentApps.length === 0 ? (
          <EmptyState
            icon="📊"
            title="No applications yet"
            description="Applications will appear here once users start submitting them."
          />
        ) : (
          <>
            {/* Mobile card view */}
            <div className="sm:hidden divide-y divide-gray-100">
              {recentApps.map((app) => (
                <div
                  key={app.applicationSeq}
                  className="py-3 cursor-pointer active:bg-gray-50"
                  onClick={() => navigate(`/admin/applications/${app.applicationSeq}`)}
                >
                  <div className="flex items-start justify-between mb-1.5">
                    <div className="min-w-0 flex-1 mr-3">
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
                </div>
              ))}
            </div>
            {/* Desktop table view */}
            <div className="hidden sm:block overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-gray-100">
                    <th className="text-left py-3 px-2 font-medium text-gray-500">Applicant</th>
                    <th className="text-left py-3 px-2 font-medium text-gray-500">Address</th>
                    <th className="text-right py-3 px-2 font-medium text-gray-500">kVA</th>
                    <th className="text-right py-3 px-2 font-medium text-gray-500">Amount</th>
                    <th className="text-left py-3 px-2 font-medium text-gray-500">Status</th>
                    <th className="text-left py-3 px-2 font-medium text-gray-500">Date</th>
                    <th className="py-3 px-2"></th>
                  </tr>
                </thead>
                <tbody>
                  {recentApps.map((app) => (
                    <tr
                      key={app.applicationSeq}
                      className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer transition-colors"
                      onClick={() => navigate(`/admin/applications/${app.applicationSeq}`)}
                    >
                      <td className="py-3 px-2">
                        <div className="font-medium text-gray-800">{app.userName}</div>
                        <div className="text-xs text-gray-400">{app.userEmail}</div>
                      </td>
                      <td className="py-3 px-2">
                        <div className="text-gray-700 truncate max-w-[180px]">{app.address}</div>
                      </td>
                      <td className="py-3 px-2 text-right text-gray-600">{app.selectedKva} kVA</td>
                      <td className="py-3 px-2 text-right font-medium text-gray-800">
                        ${app.quoteAmount.toLocaleString()}
                      </td>
                      <td className="py-3 px-2">
                        <StatusBadge status={app.status} />
                      </td>
                      <td className="py-3 px-2 text-gray-500">
                        {new Date(app.createdAt).toLocaleDateString()}
                      </td>
                      <td className="py-3 px-2 text-right">
                        <span className="text-gray-400">→</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Card>
    </div>
  );
}
