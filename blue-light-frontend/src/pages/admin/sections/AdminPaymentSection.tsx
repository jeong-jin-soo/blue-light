import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import type { Payment } from '../../../types';

interface Props {
  payments: Payment[];
}

/**
 * 결제 이력 섹션
 */
export function AdminPaymentSection({ payments }: Props) {
  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">Payment History</h2>
      {payments.length === 0 ? (
        <p className="text-sm text-gray-500">No payments recorded.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200">
                <th className="text-left py-2 px-3 font-medium text-gray-500">Date</th>
                <th className="text-left py-2 px-3 font-medium text-gray-500">Method</th>
                <th className="text-left py-2 px-3 font-medium text-gray-500">Transaction ID</th>
                <th className="text-right py-2 px-3 font-medium text-gray-500">Amount</th>
                <th className="text-left py-2 px-3 font-medium text-gray-500">Status</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((payment) => (
                <tr key={payment.paymentSeq} className="border-b border-gray-50">
                  <td className="py-2 px-3 text-gray-600">
                    {new Date(payment.paidAt).toLocaleDateString()}
                  </td>
                  <td className="py-2 px-3 text-gray-600">
                    {payment.paymentMethod || '-'}
                  </td>
                  <td className="py-2 px-3 text-gray-600 font-mono text-xs">
                    {payment.transactionId || '-'}
                  </td>
                  <td className="py-2 px-3 text-right font-medium text-gray-800">
                    SGD ${payment.amount.toLocaleString()}
                  </td>
                  <td className="py-2 px-3">
                    <Badge
                      variant={
                        payment.status === 'SUCCESS'
                          ? 'success'
                          : payment.status === 'REFUNDED'
                          ? 'warning'
                          : 'error'
                      }
                    >
                      {payment.status}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
