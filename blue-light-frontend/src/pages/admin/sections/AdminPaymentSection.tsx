import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import fileApi from '../../../api/fileApi';
import type { Payment, FileInfo } from '../../../types';

interface Props {
  payments: Payment[];
  files?: FileInfo[];
}

/**
 * 결제 이력 섹션
 */
export function AdminPaymentSection({ payments, files = [] }: Props) {
  const receiptFiles = files.filter(f => f.fileType === 'PAYMENT_RECEIPT');

  const handleDownloadReceipt = async (file: FileInfo) => {
    try {
      await fileApi.downloadFile(file.fileSeq, file.originalFilename || 'payment-receipt');
    } catch {
      // silently fail
    }
  };
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

      {/* Receipt Files */}
      {receiptFiles.length > 0 && (
        <div className="mt-4 pt-4 border-t border-gray-100">
          <p className="text-sm font-medium text-gray-700 mb-2">Payment Receipt</p>
          <div className="space-y-2">
            {receiptFiles.map((file) => (
              <div key={file.fileSeq} className="flex items-center gap-3 p-2.5 bg-green-50 border border-green-200 rounded-lg">
                <svg className="w-5 h-5 text-green-600 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
                <div className="flex-1 min-w-0">
                  <p className="text-sm text-green-800 truncate">{file.originalFilename || 'Payment Receipt'}</p>
                  {file.fileSize && (
                    <p className="text-xs text-green-600">{(file.fileSize / 1024).toFixed(0)} KB</p>
                  )}
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleDownloadReceipt(file)}
                >
                  Download
                </Button>
              </div>
            ))}
          </div>
        </div>
      )}
    </Card>
  );
}
