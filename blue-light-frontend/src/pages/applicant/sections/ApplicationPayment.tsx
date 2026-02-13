import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import fileApi from '../../../api/fileApi';
import type { Application, Payment, FileInfo } from '../../../types';

interface ApplicationPaymentProps {
  application: Application;
  payments: Payment[];
  paymentInfo: Record<string, string>;
  files?: FileInfo[];
}

export function ApplicationPayment({ application, payments, paymentInfo, files = [] }: ApplicationPaymentProps) {
  const receiptFiles = files.filter(f => f.fileType === 'PAYMENT_RECEIPT');

  const handleDownloadReceipt = async (file: FileInfo) => {
    try {
      await fileApi.downloadFile(file.fileSeq, file.originalFilename || 'payment-receipt');
    } catch {
      // silently fail
    }
  };
  return (
    <>
      {/* Pricing */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Pricing</h2>
        <div className="bg-primary-50 rounded-xl p-5 border border-primary-100">
          {application.serviceFee != null && (
            <div className="space-y-2 mb-3">
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">kVA Tier Price</span>
                <span className="font-medium text-primary-800">
                  SGD ${(application.quoteAmount - application.serviceFee).toLocaleString()}
                </span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">Service Fee</span>
                <span className="font-medium text-primary-800">
                  SGD ${application.serviceFee.toLocaleString()}
                </span>
              </div>
              <div className="border-t border-primary-200 pt-2"></div>
            </div>
          )}
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-primary-700">Total Amount</p>
              <p className="text-xs text-primary-600 mt-1">Based on {application.selectedKva} kVA capacity</p>
            </div>
            <p className="text-2xl font-bold text-primary-800">
              SGD ${application.quoteAmount.toLocaleString()}
            </p>
          </div>
          {application.emaFee && (
            <p className="text-xs text-amber-600 mt-3">
              * EMA fee of SGD ${application.emaFee.toLocaleString()} ({application.renewalPeriodMonths}-month licence) is payable directly to EMA and is not included in the above total.
            </p>
          )}
        </div>

        {application.status === 'PENDING_PAYMENT' && (
          <div className="mt-4 space-y-3">
            <div className="bg-warning-50 rounded-lg p-4 border border-warning-200">
              <div className="flex items-start gap-3">
                <span className="text-lg">💳</span>
                <div>
                  <p className="text-sm font-medium text-warning-800">Payment Required</p>
                  <p className="text-xs text-warning-700 mt-1">
                    Please make payment of <span className="font-semibold">SGD ${application.quoteAmount.toLocaleString()}</span> via PayNow. Your application will be processed once payment is confirmed by our team.
                  </p>
                </div>
              </div>
            </div>

            {/* PayNow Payment */}
            <div className="bg-surface-secondary rounded-lg p-4 border border-gray-200">
              <p className="text-sm font-semibold text-gray-800 mb-3">Payment via PayNow</p>

              {/* QR Code Image */}
              {paymentInfo.payment_paynow_qr && (
                <div className="flex justify-center mb-4">
                  <img
                    src={`${import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090/api'}${paymentInfo.payment_paynow_qr}`}
                    alt="PayNow QR Code"
                    className="w-52 h-52 object-contain border border-gray-200 rounded-lg bg-white p-2"
                  />
                </div>
              )}

              {(paymentInfo.payment_paynow_uen || paymentInfo.payment_paynow_name) && (
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 bg-primary-100 rounded-lg flex items-center justify-center flex-shrink-0">
                    <span className="text-sm font-bold text-primary-700">P</span>
                  </div>
                  <div>
                    <p className="text-sm font-medium text-gray-800">PayNow (QR / UEN Transfer)</p>
                    {paymentInfo.payment_paynow_uen && (
                      <p className="text-xs text-gray-600 mt-0.5">UEN: <span className="font-mono font-medium">{paymentInfo.payment_paynow_uen}</span></p>
                    )}
                    {paymentInfo.payment_paynow_name && (
                      <p className="text-xs text-gray-600 mt-0.5">Name: <span className="font-medium">{paymentInfo.payment_paynow_name}</span></p>
                    )}
                    <p className="text-xs text-gray-500 mt-0.5">Reference: <span className="font-mono font-medium">BL-{application.applicationSeq}</span></p>
                  </div>
                </div>
              )}

              <p className="text-xs text-gray-400 mt-3 pt-3 border-t border-gray-100">
                Please include the reference number in your PayNow transfer. Processing takes 1-2 business days after payment is received.
              </p>
            </div>
          </div>
        )}
      </Card>

      {/* Payment History */}
      {payments.length > 0 && (
        <Card>
          <h2 className="text-lg font-semibold text-gray-800 mb-4">Payment History</h2>
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
                          payment.status === 'SUCCESS' ? 'success' :
                          payment.status === 'REFUNDED' ? 'warning' : 'error'
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

          {/* Payment Receipt Download */}
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
      )}
    </>
  );
}
