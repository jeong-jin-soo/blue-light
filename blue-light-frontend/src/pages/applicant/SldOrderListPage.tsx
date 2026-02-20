import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { EmptyState } from '../../components/ui/EmptyState';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { sldOrderApi } from '../../api/sldOrderApi';
import type { SldOrder, SldOrderStatus } from '../../types';

const STATUS_CONFIG: Record<SldOrderStatus, { label: string; color: string }> = {
  PENDING_QUOTE: { label: 'Pending Quote', color: 'bg-blue-100 text-blue-800' },
  QUOTE_PROPOSED: { label: 'Quote Proposed', color: 'bg-yellow-100 text-yellow-800' },
  QUOTE_REJECTED: { label: 'Quote Rejected', color: 'bg-red-100 text-red-800' },
  PENDING_PAYMENT: { label: 'Pending Payment', color: 'bg-orange-100 text-orange-800' },
  PAID: { label: 'Paid', color: 'bg-green-100 text-green-800' },
  IN_PROGRESS: { label: 'In Progress', color: 'bg-blue-100 text-blue-800' },
  SLD_UPLOADED: { label: 'SLD Uploaded', color: 'bg-purple-100 text-purple-800' },
  REVISION_REQUESTED: { label: 'Revision Requested', color: 'bg-orange-100 text-orange-800' },
  COMPLETED: { label: 'Completed', color: 'bg-green-100 text-green-800' },
};

function SldStatusBadge({ status }: { status: SldOrderStatus }) {
  const config = STATUS_CONFIG[status] || { label: status, color: 'bg-gray-100 text-gray-800' };
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${config.color}`}>
      {config.label}
    </span>
  );
}

export default function SldOrderListPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [orders, setOrders] = useState<SldOrder[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    sldOrderApi.getMySldOrders()
      .then(setOrders)
      .catch(() => toast.error('Failed to load SLD orders'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading SLD orders..." />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">My SLD Orders</h1>
          <p className="text-sm text-gray-500 mt-1">Your SLD drawing order history</p>
        </div>
        <Button onClick={() => navigate('/sld-orders/new')}>
          + New SLD Order
        </Button>
      </div>

      {/* Order list */}
      <Card>
        {orders.length === 0 ? (
          <EmptyState
            icon="📐"
            title="No SLD orders yet"
            description="Request your first SLD drawing to get started."
            action={
              <Button variant="outline" size="sm" onClick={() => navigate('/sld-orders/new')}>
                Create SLD Order
              </Button>
            }
          />
        ) : (
          <>
            {/* Mobile card view */}
            <div className="sm:hidden divide-y divide-gray-100">
              {orders.map((order) => (
                <div
                  key={order.sldOrderSeq}
                  className="py-3 cursor-pointer active:bg-gray-50"
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate(`/sld-orders/${order.sldOrderSeq}`)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/sld-orders/${order.sldOrderSeq}`); } }}
                >
                  <div className="flex items-start justify-between mb-1.5">
                    <div className="min-w-0 flex-1 mr-3">
                      <p className="font-medium text-gray-800 truncate">
                        {order.address || `SLD Order #${order.sldOrderSeq}`}
                      </p>
                      <p className="text-xs text-gray-400 mt-0.5">
                        {order.selectedKva ? `${order.selectedKva} kVA` : 'kVA not specified'}
                      </p>
                    </div>
                    <SldStatusBadge status={order.status} />
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="font-medium text-gray-800">
                      {order.quoteAmount != null ? `$${order.quoteAmount.toLocaleString()}` : '-'}
                    </span>
                    <span className="text-xs text-gray-400">{new Date(order.createdAt).toLocaleDateString()}</span>
                  </div>
                </div>
              ))}
            </div>

            {/* Desktop table view */}
            <div className="hidden sm:block overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-gray-100">
                    <th className="text-left py-3 px-2 font-medium text-gray-500">#</th>
                    <th className="text-left py-3 px-2 font-medium text-gray-500">Address</th>
                    <th className="text-left py-3 px-2 font-medium text-gray-500">Status</th>
                    <th className="text-right py-3 px-2 font-medium text-gray-500">Quote</th>
                    <th className="text-left py-3 px-2 font-medium text-gray-500">Requested</th>
                    <th className="py-3 px-2"></th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((order) => (
                    <tr
                      key={order.sldOrderSeq}
                      className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer transition-colors"
                      tabIndex={0}
                      onClick={() => navigate(`/sld-orders/${order.sldOrderSeq}`)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/sld-orders/${order.sldOrderSeq}`); } }}
                    >
                      <td className="py-3 px-2">
                        <span className="font-mono text-xs text-gray-500">#{order.sldOrderSeq}</span>
                      </td>
                      <td className="py-3 px-2">
                        <div className="text-gray-700 truncate max-w-[200px]">
                          {order.address || '-'}
                        </div>
                      </td>
                      <td className="py-3 px-2">
                        <SldStatusBadge status={order.status} />
                      </td>
                      <td className="py-3 px-2 text-right font-medium text-gray-800">
                        {order.quoteAmount != null ? `$${order.quoteAmount.toLocaleString()}` : '-'}
                      </td>
                      <td className="py-3 px-2 text-gray-500">
                        {new Date(order.createdAt).toLocaleDateString()}
                      </td>
                      <td className="py-3 px-2 text-right">
                        <span className="text-gray-400">&rarr;</span>
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
