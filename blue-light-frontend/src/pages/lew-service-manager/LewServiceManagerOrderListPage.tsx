import { useEffect, useState } from 'react';
import { fullName } from '../../utils/formatName';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Select } from '../../components/ui/Select';
import { EmptyState } from '../../components/ui/EmptyState';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { Pagination } from '../../components/data/Pagination';
import { useToastStore } from '../../stores/toastStore';
import { lewServiceManagerApi } from '../../api/lewServiceManagerApi';
import {
  LewServiceStatusBadge,
  LEW_SERVICE_STATUS_OPTIONS,
} from '../../components/domain/LewServiceStatusBadge';
import type { LewServiceOrder } from '../../types';

const PAGE_SIZE = 15;

export default function LewServiceManagerOrderListPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [searchParams, setSearchParams] = useSearchParams();

  const initialStatus = searchParams.get('status') || '';

  const [orders, setOrders] = useState<LewServiceOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [statusFilter, setStatusFilter] = useState(initialStatus);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    lewServiceManagerApi
      .getOrders({
        page,
        size: PAGE_SIZE,
        status: statusFilter || undefined,
      })
      .then((data) => {
        setOrders(data.content);
        setTotalPages(data.totalPages);
      })
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load LEW Service orders');
      })
      .finally(() => setLoading(false));
  }, [page, statusFilter]);

  const handleStatusChange = (value: string) => {
    setStatusFilter(value);
    setPage(0);
    if (value) {
      setSearchParams({ status: value });
    } else {
      setSearchParams({});
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">LEW Service Orders</h1>
        <p className="text-sm text-gray-500 mt-1">Manage all LEW Service orders</p>
      </div>

      <Card>
        <div className="flex flex-col sm:flex-row gap-3">
          <div className="w-full sm:w-48">
            <Select
              value={statusFilter}
              onChange={(e) => handleStatusChange(e.target.value)}
              options={LEW_SERVICE_STATUS_OPTIONS}
            />
          </div>
        </div>
      </Card>

      {loading ? (
        <div className="flex items-center justify-center h-48">
          <LoadingSpinner size="lg" label="Loading orders..." />
        </div>
      ) : orders.length === 0 ? (
        <Card>
          <EmptyState
            icon="⚡"
            title="No LEW Service orders found"
            description={
              statusFilter
                ? 'No orders match your filter criteria.'
                : 'LEW Service orders will appear here once applicants submit them.'
            }
          />
        </Card>
      ) : (
        <>
          {/* Mobile card view */}
          <div className="sm:hidden space-y-2">
            {orders.map((order) => (
              <Card
                key={order.lewServiceOrderSeq}
                padding="sm"
                className="cursor-pointer active:bg-gray-50"
                role="button"
                tabIndex={0}
                onClick={() => navigate(`/lew-service-manager/orders/${order.lewServiceOrderSeq}`)}
                onKeyDown={(e: React.KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/lew-service-manager/orders/${order.lewServiceOrderSeq}`); } }}
              >
                <div className="flex items-start justify-between mb-1.5">
                  <div className="min-w-0 flex-1 mr-3">
                    <p className="font-medium text-gray-800">{fullName(order.userFirstName, order.userLastName)}</p>
                    <p className="text-xs text-gray-400">{order.userEmail}</p>
                  </div>
                  <LewServiceStatusBadge status={order.status} />
                </div>
                <p className="text-sm text-gray-700 truncate mb-1">{order.address || '-'}</p>
                <div className="flex items-center justify-between text-sm">
                  <span className="font-medium text-gray-800">
                    {order.quoteAmount != null ? `$${order.quoteAmount.toLocaleString()}` : '-'}
                  </span>
                  <span className="text-xs text-gray-400">{new Date(order.createdAt).toLocaleDateString()}</span>
                </div>
              </Card>
            ))}
          </div>

          {/* Desktop table view */}
          <Card className="hidden sm:block" padding="none">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-gray-100">
                    <th className="text-left py-3 px-4 font-medium text-gray-500">#</th>
                    <th className="text-left py-3 px-4 font-medium text-gray-500">Applicant</th>
                    <th className="text-left py-3 px-4 font-medium text-gray-500">Address</th>
                    <th className="text-left py-3 px-4 font-medium text-gray-500">Status</th>
                    <th className="text-right py-3 px-4 font-medium text-gray-500">Quote</th>
                    <th className="text-left py-3 px-4 font-medium text-gray-500">Requested</th>
                    <th className="py-3 px-4"></th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((order) => (
                    <tr
                      key={order.lewServiceOrderSeq}
                      className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer transition-colors"
                      tabIndex={0}
                      onClick={() => navigate(`/lew-service-manager/orders/${order.lewServiceOrderSeq}`)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/lew-service-manager/orders/${order.lewServiceOrderSeq}`); } }}
                    >
                      <td className="py-3 px-4">
                        <span className="font-mono text-xs text-gray-500">#{order.lewServiceOrderSeq}</span>
                      </td>
                      <td className="py-3 px-4">
                        <div className="font-medium text-gray-800">{fullName(order.userFirstName, order.userLastName)}</div>
                        <div className="text-xs text-gray-400">{order.userEmail}</div>
                      </td>
                      <td className="py-3 px-4">
                        <div className="text-gray-700 truncate max-w-[200px]">{order.address || '-'}</div>
                      </td>
                      <td className="py-3 px-4">
                        <LewServiceStatusBadge status={order.status} />
                      </td>
                      <td className="py-3 px-4 text-right font-medium text-gray-800">
                        {order.quoteAmount != null ? `$${order.quoteAmount.toLocaleString()}` : '-'}
                      </td>
                      <td className="py-3 px-4 text-gray-500 text-xs">
                        {new Date(order.createdAt).toLocaleDateString()}
                      </td>
                      <td className="py-3 px-4 text-right">
                        <span className="text-gray-400">&rarr;</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}
