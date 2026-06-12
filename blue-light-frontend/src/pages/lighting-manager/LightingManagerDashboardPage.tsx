import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Search, MessageSquare, CreditCard, BadgeCheck, RefreshCw,
  FileText, Flag, ClipboardList, ChevronRight,
} from 'lucide-react';
import { PageHeader } from '../../components/ui/PageHeader';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { lightingManagerApi } from '../../api/lightingManagerApi';
import type { LightingOrderDashboard } from '../../types';

export default function LightingManagerDashboardPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [dashboard, setDashboard] = useState<LightingOrderDashboard | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    lightingManagerApi.getDashboard()
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

  const pendingQuote = dashboard?.pendingQuote ?? 0;

  // 2차 KPI(참고용) — 작은 칩 행으로 강등.
  const chips = [
    { label: 'Total', value: dashboard?.total ?? 0, icon: ClipboardList, q: '' },
    { label: 'Quote Proposed', value: dashboard?.quoteProposed ?? 0, icon: MessageSquare, q: 'QUOTE_PROPOSED' },
    { label: 'Pending Payment', value: dashboard?.pendingPayment ?? 0, icon: CreditCard, q: 'PENDING_PAYMENT' },
    { label: 'Paid', value: dashboard?.paid ?? 0, icon: BadgeCheck, q: 'PAID' },
    { label: 'In Progress', value: dashboard?.inProgress ?? 0, icon: RefreshCw, q: 'IN_PROGRESS' },
    { label: 'Uploaded', value: dashboard?.deliverableUploaded ?? 0, icon: FileText, q: 'SLD_UPLOADED' },
    { label: 'Completed', value: dashboard?.completed ?? 0, icon: Flag, q: 'COMPLETED' },
  ];

  const goOrders = (q: string) =>
    navigate(q ? `/lighting-manager/orders?status=${q}` : '/lighting-manager/orders');

  return (
    <div className="max-w-7xl mx-auto space-y-6">
      <PageHeader title="Lighting Manager Dashboard" subtitle="Overview of Lighting Layout orders" />

      {/* Hero KPI — 신규 견적 대기(핵심 작업) */}
      <button
        type="button"
        onClick={() => goOrders('PENDING_QUOTE')}
        className="group block text-left bg-white rounded-xl border-t-[3px] border-accent shadow-dropdown p-6 hover:shadow-hero transition-shadow focus:outline-none focus:ring-2 focus:ring-accent/30 w-full sm:max-w-xs"
      >
        <div className="flex items-center gap-2 text-accent-600 mb-3">
          <Search className="w-5 h-5" />
          <span className="text-sm font-semibold">Pending Quote</span>
        </div>
        <div className="text-5xl font-bold text-gray-900 tabular-nums leading-none">{pendingQuote}</div>
        <div className="mt-3 flex items-center text-sm text-gray-500 group-hover:text-accent-600 transition-colors">
          Quote now <ChevronRight className="w-4 h-4 ml-0.5" />
        </div>
      </button>

      {/* 2차 KPI — 작은 칩 행 */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-7 gap-3">
        {chips.map(({ label, value, icon: Icon, q }) => (
          <button
            key={label}
            type="button"
            onClick={() => goOrders(q)}
            className="flex items-center gap-3 bg-white rounded-lg border border-primary-100 px-4 py-3 hover:border-primary-300 transition-colors text-left"
          >
            <Icon className="w-4 h-4 text-gray-400 shrink-0" />
            <div className="min-w-0">
              <div className="text-lg font-semibold text-gray-900 tabular-nums leading-none">{value}</div>
              <div className="text-xs text-gray-500 truncate">{label}</div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
