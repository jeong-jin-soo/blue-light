import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DashboardCard } from '../../components/domain/DashboardCard';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { sldManagerApi } from '../../api/sldManagerApi';
import type { SldOrderDashboard } from '../../types';

export default function SldManagerDashboardPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [dashboard, setDashboard] = useState<SldOrderDashboard | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    sldManagerApi.getDashboard()
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
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">SLD Manager Dashboard</h1>
        <p className="text-sm text-gray-500 mt-1">Overview of SLD drawing orders</p>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4">
        <DashboardCard
          label="전체"
          value={dashboard?.total ?? 0}
          icon="📋"
          onClick={() => navigate('/sld-manager/orders')}
        />
        <DashboardCard
          label="견적대기"
          value={dashboard?.pendingQuote ?? 0}
          icon="🔍"
          onClick={() => navigate('/sld-manager/orders?status=PENDING_QUOTE')}
        />
        <DashboardCard
          label="견적제안"
          value={dashboard?.quoteProposed ?? 0}
          icon="💬"
          onClick={() => navigate('/sld-manager/orders?status=QUOTE_PROPOSED')}
        />
        <DashboardCard
          label="결제대기"
          value={dashboard?.pendingPayment ?? 0}
          icon="💳"
          onClick={() => navigate('/sld-manager/orders?status=PENDING_PAYMENT')}
        />
        <DashboardCard
          label="결제완료"
          value={dashboard?.paid ?? 0}
          icon="✅"
          onClick={() => navigate('/sld-manager/orders?status=PAID')}
        />
        <DashboardCard
          label="작업중"
          value={dashboard?.inProgress ?? 0}
          icon="🔄"
          onClick={() => navigate('/sld-manager/orders?status=IN_PROGRESS')}
        />
        <DashboardCard
          label="업로드완료"
          value={dashboard?.sldUploaded ?? 0}
          icon="📄"
          onClick={() => navigate('/sld-manager/orders?status=SLD_UPLOADED')}
        />
        <DashboardCard
          label="완료"
          value={dashboard?.completed ?? 0}
          icon="🏁"
          onClick={() => navigate('/sld-manager/orders?status=COMPLETED')}
        />
      </div>
    </div>
  );
}
