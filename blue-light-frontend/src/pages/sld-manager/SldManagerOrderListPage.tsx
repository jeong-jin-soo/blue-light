import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Select } from '../../components/ui/Select';
import { EmptyState } from '../../components/ui/EmptyState';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { Pagination } from '../../components/data/Pagination';
import { useToastStore } from '../../stores/toastStore';
import { sldManagerApi } from '../../api/sldManagerApi';
import type { SldOrder, SldOrderStatus } from '../../types';

const STATUS_OPTIONS = [
  { value: '', label: 'All Statuses' },
  { value: 'PENDING_QUOTE', label: 'Pending Quote' },
  { value: 'QUOTE_PROPOSED', label: 'Quote Proposed' },
  { value: 'QUOTE_REJECTED', label: 'Quote Rejected' },
  { value: 'PENDING_PAYMENT', label: 'Pending Payment' },
  { value: 'PAID', label: 'Paid' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'SLD_UPLOADED', label: 'SLD Uploaded' },
  { value: 'REVISION_REQUESTED', label: 'Revision Requested' },
  { value: 'COMPLETED', label: 'Completed' },
];

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

const PAGE_SIZE = 15;

export default function SldManagerOrderListPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [searchParams, setSearchParams] = useSearchParams();

  const initialStatus = searchParams.get('status') || '';

  const [orders, setOrders] = useState<SldOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [statusFilter, setStatusFilter] = useState(initialStatus);

  useEffect(() => {
    setLoading(true);
    sldManagerApi
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
        toast.error(err.message || 'Failed to load SLD orders');
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
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">SLD Orders</h1>
        <p className="text-sm text-gray-500 mt-1">Manage all SLD drawing orders</p>
      </div>

      {/* Filters */}
      <Card>
        <div className="flex flex-col sm:flex-row gap-3">
          <div className="w-full sm:w-48">
            <Select
              value={statusFilter}
              onChange={(e) => handleStatusChange(e.target.value)}
              options={STATUS_OPTIONS}
            />
          </div>
        </div>
      </Card>

      {/* Order list */}
      {loading ? (
        <div className="flex items-center justify-center h-48">
          <LoadingSpinner size="lg" label="Loading orders..." />
        </div>
      ) : orders.length === 0 ? (
        <Card>
          <EmptyState
            icon="📐"
            title="No SLD orders found"
            description={
              statusFilter
                ? 'No orders match your filter criteria.'
                : 'SLD orders will appear here once applicants submit them.'
            }
          />
        </Card>
      ) : (
        <>
          {/* Mobile card view */}
          <div className="sm:hidden space-y-2">
            {orders.map((order) => (
              <Card
                key={order.sldOrderSeq}
                padding="sm"
                className="cursor-pointer active:bg-gray-50"
                role="button"
                tabIndex={0}
                onClick={() => navigate(`/sld-manager/orders/${order.sldOrderSeq}`)}
                onKeyDown={(e: React.KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/sld-manager/orders/${order.sldOrderSeq}`); } }}
              >
                <div className="flex items-start justify-between mb-1.5">
                  <div className="min-w-0 flex-1 mr-3">
                    <p className="font-medium text-gray-800">{order.userName}</p>
                    <p className="text-xs text-gray-400">{order.userEmail}</p>
                  </div>
                  <SldStatusBadge status={order.status} />
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
                    <th className="text-left py-3 px-4 font-medium text-gray-500">신청자</th>
                    <th className="text-left py-3 px-4 font-medium text-gray-500">주소</th>
                    <th className="text-left py-3 px-4 font-medium text-gray-500">상태</th>
                    <th className="text-right py-3 px-4 font-medium text-gray-500">견적금액</th>
                    <th className="text-left py-3 px-4 font-medium text-gray-500">요청일</th>
                    <th className="py-3 px-4"></th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((order) => (
                    <tr
                      key={order.sldOrderSeq}
                      className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer transition-colors"
                      tabIndex={0}
                      onClick={() => navigate(`/sld-manager/orders/${order.sldOrderSeq}`)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigate(`/sld-manager/orders/${order.sldOrderSeq}`); } }}
                    >
                      <td className="py-3 px-4">
                        <span className="font-mono text-xs text-gray-500">#{order.sldOrderSeq}</span>
                      </td>
                      <td className="py-3 px-4">
                        <div className="font-medium text-gray-800">{order.userName}</div>
                        <div className="text-xs text-gray-400">{order.userEmail}</div>
                      </td>
                      <td className="py-3 px-4">
                        <div className="text-gray-700 truncate max-w-[200px]">{order.address || '-'}</div>
                      </td>
                      <td className="py-3 px-4">
                        <SldStatusBadge status={order.status} />
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

          {/* Pagination */}
          <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}
