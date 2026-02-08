import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { DashboardCard } from '../../components/domain/DashboardCard';
import { EmptyState } from '../../components/ui/EmptyState';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useAuthStore } from '../../stores/authStore';
import { useToastStore } from '../../stores/toastStore';
import applicationApi from '../../api/applicationApi';
import type { Application, ApplicationSummary } from '../../types';

export default function DashboardPage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const toast = useToastStore();
  const [summary, setSummary] = useState<ApplicationSummary | null>(null);
  const [recentApps, setRecentApps] = useState<Application[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [summaryData, appsData] = await Promise.all([
          applicationApi.getApplicationSummary(),
          applicationApi.getMyApplications(),
        ]);
        setSummary(summaryData);
        setRecentApps(appsData.slice(0, 5));
      } catch (err: unknown) {
        const error = err as { message?: string };
        toast.error(error.message || 'Failed to load dashboard data');
      } finally {
        setLoading(false);
      }
    };
    fetchData();
  }, []);

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
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">
            Welcome back{user?.name ? `, ${user.name}` : ''}
          </h1>
          <p className="text-sm text-gray-500 mt-1">Overview of your licence applications</p>
        </div>
        <Button onClick={() => navigate('/applications/new')}>
          + New Application
        </Button>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4">
        <DashboardCard
          label="Total Applications"
          value={summary?.total ?? 0}
          icon="📋"
        />
        <DashboardCard
          label="Pending Review"
          value={summary?.pendingReview ?? 0}
          icon="🔍"
        />
        <DashboardCard
          label="Pending Payment"
          value={summary?.pendingPayment ?? 0}
          icon="⏳"
        />
        <DashboardCard
          label="In Progress"
          value={summary?.inProgress ?? 0}
          icon="🔄"
        />
        <DashboardCard
          label="Completed"
          value={summary?.completed ?? 0}
          icon="✅"
        />
      </div>

      {/* Recent applications */}
      <Card>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-gray-800">Recent Applications</h2>
          {recentApps.length > 0 && (
            <Button variant="ghost" size="sm" onClick={() => navigate('/applications')}>
              View All
            </Button>
          )}
        </div>

        {recentApps.length === 0 ? (
          <EmptyState
            icon="📋"
            title="No applications yet"
            description="Get started by creating your first licence application."
            action={
              <Button variant="outline" size="sm" onClick={() => navigate('/applications/new')}>
                Create Application
              </Button>
            }
          />
        ) : (
          <>
            {/* Mobile card view */}
            <div className="sm:hidden divide-y divide-gray-100">
              {recentApps.map((app) => (
                <div
                  key={app.applicationSeq}
                  className="py-3 cursor-pointer active:bg-gray-50"
                  onClick={() => navigate(`/applications/${app.applicationSeq}`)}
                >
                  <div className="flex items-start justify-between mb-1.5">
                    <div className="min-w-0 flex-1 mr-3">
                      <p className="font-medium text-gray-800 truncate">{app.address}</p>
                      <p className="text-xs text-gray-400 mt-0.5">{app.postalCode}</p>
                    </div>
                    <StatusBadge status={app.status} />
                  </div>
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
                    <th className="text-left py-3 px-2 font-medium text-gray-500">Address</th>
                    <th className="text-left py-3 px-2 font-medium text-gray-500">kVA</th>
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
                      onClick={() => navigate(`/applications/${app.applicationSeq}`)}
                    >
                      <td className="py-3 px-2">
                        <div className="font-medium text-gray-800 truncate max-w-[200px]">
                          {app.address}
                        </div>
                        <div className="text-xs text-gray-400">{app.postalCode}</div>
                      </td>
                      <td className="py-3 px-2 text-gray-600">{app.selectedKva} kVA</td>
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
