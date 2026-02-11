import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import type { Application, Payment } from '../../../types';

interface ApplicationPaymentProps {
  application: Application;
  payments: Payment[];
  paymentInfo: Record<string, string>;
}

export function ApplicationPayment({ application, payments, paymentInfo }: ApplicationPaymentProps) {
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
                    Please make payment of <span className="font-semibold">SGD ${application.quoteAmount.toLocaleString()}</span> via one of the methods below. Your application will be processed once payment is confirmed by our team.
                  </p>
                </div>
              </div>
            </div>

            {/* Payment Methods */}
            <div className="bg-surface-secondary rounded-lg p-4 border border-gray-200">
              <p className="text-sm font-semibold text-gray-800 mb-3">Payment Methods</p>

              {/* PayNow */}
              {(paymentInfo.payment_paynow_uen || paymentInfo.payment_paynow_name) && (
                <div className="flex items-start gap-3 mb-3 pb-3 border-b border-gray-100">
                  <div className="w-8 h-8 bg-primary-100 rounded-lg flex items-center justify-center flex-shrink-0">
                    <span className="text-sm font-bold text-primary-700">P</span>
                  </div>
                  <div>
                    <p className="text-sm font-medium text-gray-800">PayNow</p>
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

              {/* Bank Transfer */}
              {(paymentInfo.payment_bank_name || paymentInfo.payment_bank_account) && (
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 bg-primary-100 rounded-lg flex items-center justify-center flex-shrink-0">
                    <span className="text-sm font-bold text-primary-700">B</span>
                  </div>
                  <div>
                    <p className="text-sm font-medium text-gray-800">Bank Transfer</p>
                    {paymentInfo.payment_bank_name && (
                      <p className="text-xs text-gray-600 mt-0.5">Bank: <span className="font-medium">{paymentInfo.payment_bank_name}</span></p>
                    )}
                    {paymentInfo.payment_bank_account && (
                      <p className="text-xs text-gray-600 mt-0.5">Account: <span className="font-mono font-medium">{paymentInfo.payment_bank_account}</span></p>
                    )}
                    {paymentInfo.payment_bank_account_name && (
                      <p className="text-xs text-gray-600 mt-0.5">Account Name: <span className="font-medium">{paymentInfo.payment_bank_account_name}</span></p>
                    )}
                    <p className="text-xs text-gray-500 mt-0.5">Reference: <span className="font-mono font-medium">BL-{application.applicationSeq}</span></p>
                  </div>
                </div>
              )}

              <p className="text-xs text-gray-400 mt-3 pt-3 border-t border-gray-100">
                Please include the reference number in your payment. Processing takes 1-2 business days after payment is received.
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
        </Card>
      )}
    </>
  );
}
