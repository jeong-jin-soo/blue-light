import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DashboardCard } from '../../components/domain/DashboardCard';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { lewServiceManagerApi } from '../../api/lewServiceManagerApi';
import type { LewServiceOrderDashboard } from '../../types';

export default function LewServiceManagerDashboardPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [dashboard, setDashboard] = useState<LewServiceOrderDashboard | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    lewServiceManagerApi.getDashboard()
      .then(setDashboard)
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load dashboard data');
      })
      .finally(() => setLoading(false));
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
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">LEW Service Manager Dashboard</h1>
        <p className="text-sm text-gray-500 mt-1">Overview of LEW Service orders</p>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4">
        <DashboardCard
          label="Total"
          value={dashboard?.total ?? 0}
          icon="📋"
          onClick={() => navigate('/lew-service-manager/orders')}
        />
        <DashboardCard
          label="Pending Quote"
          value={dashboard?.pendingQuote ?? 0}
          icon="🔍"
          onClick={() => navigate('/lew-service-manager/orders?status=PENDING_QUOTE')}
        />
        <DashboardCard
          label="Quote Proposed"
          value={dashboard?.quoteProposed ?? 0}
          icon="💬"
          onClick={() => navigate('/lew-service-manager/orders?status=QUOTE_PROPOSED')}
        />
        <DashboardCard
          label="Pending Payment"
          value={dashboard?.pendingPayment ?? 0}
          icon="💳"
          onClick={() => navigate('/lew-service-manager/orders?status=PENDING_PAYMENT')}
        />
        <DashboardCard
          label="Paid"
          value={dashboard?.paid ?? 0}
          icon="✅"
          onClick={() => navigate('/lew-service-manager/orders?status=PAID')}
        />
        <DashboardCard
          label="Visit Scheduled"
          value={dashboard?.visitScheduled ?? 0}
          icon="📅"
          onClick={() => navigate('/lew-service-manager/orders?status=VISIT_SCHEDULED')}
        />
        <DashboardCard
          label="Report Submitted"
          value={dashboard?.visitCompleted ?? 0}
          icon="📄"
          onClick={() => navigate('/lew-service-manager/orders?status=VISIT_COMPLETED')}
        />
        <DashboardCard
          label="Revisit Requested"
          value={dashboard?.revisitRequested ?? 0}
          icon="🔁"
          onClick={() => navigate('/lew-service-manager/orders?status=REVISIT_REQUESTED')}
        />
        <DashboardCard
          label="Completed"
          value={dashboard?.completed ?? 0}
          icon="🏁"
          onClick={() => navigate('/lew-service-manager/orders?status=COMPLETED')}
        />
      </div>
    </div>
  );
}
