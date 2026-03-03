import { useState, useEffect } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { formatFileSize, isImageFile } from '../../../utils/applicationUtils';
import fileApi from '../../../api/fileApi';
import type { Payment, FileInfo } from '../../../types';

interface Props {
  payments: Payment[];
  files?: FileInfo[];
  applicationStatus?: string;
}

/** Inline image/file preview for admin verification */
function ReceiptPreviewCard({
  file,
  onDownload,
  accentColor = 'green',
}: {
  file: FileInfo;
  onDownload: (f: FileInfo) => void;
  accentColor?: 'amber' | 'green';
}) {
  const filename = file.originalFilename || 'Payment Receipt';
  const isImage = isImageFile(filename);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  const colors = accentColor === 'amber'
    ? { border: 'border-amber-200', bg: 'bg-white', icon: 'text-amber-600', text: 'text-amber-900', sub: 'text-amber-600' }
    : { border: 'border-green-200', bg: 'bg-green-50', icon: 'text-green-600', text: 'text-green-800', sub: 'text-green-600' };

  useEffect(() => {
    if (!isImage) return;
    let revoked = false;
    let url = '';
    fileApi.getFilePreviewUrl(file.fileSeq)
      .then((blobUrl) => {
        if (revoked) { URL.revokeObjectURL(blobUrl); return; }
        url = blobUrl;
        setPreviewUrl(blobUrl);
      })
      .catch(() => {});
    return () => { revoked = true; if (url) URL.revokeObjectURL(url); };
  }, [file.fileSeq, isImage]);

  return (
    <div className={`rounded-lg border ${colors.border} overflow-hidden ${colors.bg}`}>
      {/* Image inline preview */}
      {isImage && previewUrl && (
        <div className="p-2 flex justify-center bg-gray-50">
          <img
            src={previewUrl}
            alt={filename}
            className="max-h-64 max-w-full object-contain rounded"
          />
        </div>
      )}
      {/* File info bar */}
      <div className="flex items-center gap-3 px-3 py-2.5 border-t border-gray-100">
        <svg className={`w-5 h-5 ${colors.icon} flex-shrink-0`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <div className="flex-1 min-w-0">
          <p className={`text-sm ${colors.text} truncate`}>{filename}</p>
          <p className={`text-xs ${colors.sub}`}>
            {file.fileSize ? formatFileSize(file.fileSize) : ''}
            {file.uploadedAt && ` \u00B7 ${new Date(file.uploadedAt).toLocaleString()}`}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => onDownload(file)}>
          Download
        </Button>
      </div>
    </div>
  );
}

/**
 * 결제 이력 섹션
 */
export function AdminPaymentSection({ payments, files = [], applicationStatus }: Props) {
  const receiptFiles = files.filter(f => f.fileType === 'PAYMENT_RECEIPT');
  const isPendingPayment = applicationStatus === 'PENDING_PAYMENT';

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

      {/* Payment Advice from Applicant — shown prominently when PENDING_PAYMENT */}
      {isPendingPayment && receiptFiles.length > 0 && (
        <div className="mb-4 bg-amber-50 rounded-lg p-4 border border-amber-200">
          <div className="flex items-start gap-2 mb-3">
            <span className="text-base">📄</span>
            <div>
              <p className="text-sm font-semibold text-amber-800">Payment Advice from Applicant</p>
              <p className="text-xs text-amber-700 mt-0.5">
                The applicant has uploaded payment proof. Please verify against your bank account before confirming payment.
              </p>
            </div>
          </div>
          <div className="space-y-2">
            {receiptFiles.map((file) => (
              <ReceiptPreviewCard
                key={file.fileSeq}
                file={file}
                onDownload={handleDownloadReceipt}
                accentColor="amber"
              />
            ))}
          </div>
        </div>
      )}

      {payments.length === 0 && !(isPendingPayment && receiptFiles.length > 0) ? (
        <p className="text-sm text-gray-500">No payments recorded.</p>
      ) : payments.length > 0 ? (
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
      ) : null}

      {/* Receipt Files — shown as regular section when not PENDING_PAYMENT */}
      {!isPendingPayment && receiptFiles.length > 0 && (
        <div className="mt-4 pt-4 border-t border-gray-100">
          <p className="text-sm font-medium text-gray-700 mb-2">Payment Receipt</p>
          <div className="space-y-2">
            {receiptFiles.map((file) => (
              <ReceiptPreviewCard
                key={file.fileSeq}
                file={file}
                onDownload={handleDownloadReceipt}
                accentColor="green"
              />
            ))}
          </div>
        </div>
      )}
    </Card>
  );
}
