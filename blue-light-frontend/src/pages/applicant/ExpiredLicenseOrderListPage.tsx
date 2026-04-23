import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { EmptyState } from '../../components/ui/EmptyState';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { expiredLicenseOrderApi } from '../../api/expiredLicenseOrderApi';
import { ExpiredLicenseStatusBadge } from '../../components/domain/ExpiredLicenseStatusBadge';
import type { ExpiredLicenseOrder } from '../../types';

export default function ExpiredLicenseOrderListPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [orders, setOrders] = useState<ExpiredLicenseOrder[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    expiredLicenseOrderApi.getMyExpiredLicenseOrders()
      .then(setOrders)
      .catch(() => toast.error('Failed to load Expired License orders'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading Expired License orders..." />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">My Expired License Orders</h1>
          <p className="text-sm text-gray-500 mt-1">Your Expired License renewal order history</p>
        </div>
        <Button onClick={() => navigate('/expired-license-orders/new')}>
          + Request Expired License Service
        </Button>
      </div>

      <Card>
        {orders.length === 0 ? (
          <EmptyState
            icon="🔄"
            title="No Expired License orders yet"
            description="Request a service to renew your expired electrical installation licence."
            action={
              <Button onClick={() => navigate('/expired-license-orders/new')}>
                Create Expired License Order
              </Button>
            }
          />
        ) : (
          <>
            <div className="sm:hidden divide-y divide-gray-100">
              {orders.map((order) => (
                <div
                  key={order.expiredLicenseOrderSeq}
                  className="py-3 cursor-pointer active:bg-gray-50"
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate(`/expired-license-orders/${order.expiredLicenseOrderSeq}`)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/expired-license-orders/${order.expiredLicenseOrderSeq}`); } }}
                >
                  <div className="flex items-start justify-between mb-1.5">
                    <div className="min-w-0 flex-1 mr-3">
                      <p className="font-medium text-gray-800 truncate">
                        {order.address || `Expired License Order #${order.expiredLicenseOrderSeq}`}
                      </p>
                      <p className="text-xs text-gray-400 mt-0.5">
                        {order.selectedKva ? `${order.selectedKva} kVA` : 'kVA not specified'}
                      </p>
                    </div>
                    <ExpiredLicenseStatusBadge status={order.status} />
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
                      key={order.expiredLicenseOrderSeq}
                      className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer transition-colors"
                      tabIndex={0}
                      onClick={() => navigate(`/expired-license-orders/${order.expiredLicenseOrderSeq}`)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/expired-license-orders/${order.expiredLicenseOrderSeq}`); } }}
                    >
                      <td className="py-3 px-2">
                        <span className="font-mono text-xs text-gray-500">#{order.expiredLicenseOrderSeq}</span>
                      </td>
                      <td className="py-3 px-2">
                        <div className="text-gray-700 truncate max-w-[200px]">
                          {order.address || '-'}
                        </div>
                      </td>
                      <td className="py-3 px-2">
                        <ExpiredLicenseStatusBadge status={order.status} />
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
