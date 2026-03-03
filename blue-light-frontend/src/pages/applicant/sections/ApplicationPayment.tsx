import { useRef, useState, useEffect } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog';
import { useToastStore } from '../../../stores/toastStore';
import fileApi from '../../../api/fileApi';
import { formatFileSize, isImageFile } from '../../../utils/applicationUtils';
import type { Application, Payment, FileInfo } from '../../../types';

interface ApplicationPaymentProps {
  application: Application;
  payments: Payment[];
  paymentInfo: Record<string, string>;
  files?: FileInfo[];
  onPaymentAdviceUpload?: (file: File) => Promise<void>;
  onPaymentAdviceDelete?: (fileSeq: number) => Promise<void>;
}

/** Inline image/file preview card for a payment advice file */
function AdviceFileCard({
  file,
  onDownload,
  onDelete,
}: {
  file: FileInfo;
  onDownload: (f: FileInfo) => void;
  onDelete?: (f: FileInfo) => void;
}) {
  const filename = file.originalFilename || 'Payment Advice';
  const isImage = isImageFile(filename);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

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
    <div className="bg-white rounded-lg border border-blue-100 overflow-hidden">
      {/* Image preview */}
      {isImage && previewUrl && (
        <div className="p-2 flex justify-center bg-gray-50">
          <img
            src={previewUrl}
            alt={filename}
            className="max-h-60 max-w-full object-contain rounded"
          />
        </div>
      )}
      {/* File info bar */}
      <div className="flex items-center gap-2 px-3 py-2 border-t border-blue-50">
        <svg className="w-4 h-4 text-blue-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <div className="flex-1 min-w-0">
          <p className="text-xs text-gray-600 truncate">{filename}</p>
          {file.fileSize != null && (
            <p className="text-[10px] text-gray-400">{formatFileSize(file.fileSize)}</p>
          )}
        </div>
        <button
          type="button"
          onClick={() => onDownload(file)}
          className="text-blue-600 hover:text-blue-800 p-1"
          title="Download"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
          </svg>
        </button>
        {onDelete && (
          <button
            type="button"
            onClick={() => onDelete(file)}
            className="text-gray-400 hover:text-red-500 p-1"
            title="Delete"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}

export function ApplicationPayment({
  application,
  payments,
  paymentInfo,
  files = [],
  onPaymentAdviceUpload,
  onPaymentAdviceDelete,
}: ApplicationPaymentProps) {
  const toast = useToastStore();
  const receiptFiles = files.filter(f => f.fileType === 'PAYMENT_RECEIPT');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<FileInfo | null>(null);
  const [deleting, setDeleting] = useState(false);

  const canUploadAdvice = application.status === 'PENDING_PAYMENT' && !!onPaymentAdviceUpload;

  const handleDownloadReceipt = async (file: FileInfo) => {
    try {
      await fileApi.downloadFile(file.fileSeq, file.originalFilename || 'payment-receipt');
    } catch {
      // silently fail
    }
  };

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !onPaymentAdviceUpload) return;

    if (file.size > 10 * 1024 * 1024) {
      toast.error('File size must be less than 10MB');
      e.target.value = '';
      return;
    }

    setUploading(true);
    try {
      await onPaymentAdviceUpload(file);
      toast.success('Payment advice uploaded successfully');
    } catch {
      toast.error('Failed to upload payment advice');
    } finally {
      setUploading(false);
      e.target.value = '';
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget || !onPaymentAdviceDelete) return;
    setDeleting(true);
    try {
      await onPaymentAdviceDelete(deleteTarget.fileSeq);
      toast.success('Payment advice deleted');
    } catch {
      toast.error('Failed to delete payment advice');
    } finally {
      setDeleting(false);
      setDeleteTarget(null);
    }
  };

  return (
    <>
      {/* Pricing */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Pricing</h2>
        <div className="bg-primary-50 rounded-xl p-5 border border-primary-100">
          <div className="space-y-2 mb-3">
            <div className="flex justify-between text-sm">
              <span className="text-primary-700">kVA Tier Price</span>
              <span className="font-medium text-primary-800">
                SGD ${(application.quoteAmount - (application.sldFee || 0) - (application.emaFee || 0)).toLocaleString()}
              </span>
            </div>
            {application.sldFee != null && application.sldFee > 0 && (
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">SLD Drawing Fee</span>
                <span className="font-medium text-primary-800">
                  SGD ${application.sldFee.toLocaleString()}
                </span>
              </div>
            )}
            {application.emaFee != null && application.emaFee > 0 && (
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">EMA Fee ({application.renewalPeriodMonths}-month)</span>
                <span className="font-medium text-primary-800">
                  SGD ${application.emaFee.toLocaleString()}
                </span>
              </div>
            )}
            <div className="border-t border-primary-200 pt-2"></div>
          </div>
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-primary-700">Total Amount</p>
              <p className="text-xs text-primary-600 mt-1">Based on {application.selectedKva} kVA capacity</p>
            </div>
            <p className="text-2xl font-bold text-primary-800">
              SGD ${application.quoteAmount.toLocaleString()}
            </p>
          </div>
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

            {/* Upload Payment Advice */}
            {canUploadAdvice && (
              <div className="bg-blue-50 rounded-lg p-4 border border-blue-200">
                <div className="flex items-start gap-3 mb-3">
                  <span className="text-lg">📄</span>
                  <div>
                    <p className="text-sm font-medium text-blue-800">Upload Payment Advice</p>
                    <p className="text-xs text-blue-700 mt-0.5">
                      After making payment, please upload a screenshot or PDF of your payment confirmation. This helps us verify your payment faster.
                    </p>
                  </div>
                </div>

                {/* Already uploaded files with inline preview */}
                {receiptFiles.length > 0 && (
                  <div className="space-y-2 mb-3">
                    {receiptFiles.map((file) => (
                      <AdviceFileCard
                        key={file.fileSeq}
                        file={file}
                        onDownload={handleDownloadReceipt}
                        onDelete={(f) => setDeleteTarget(f)}
                      />
                    ))}
                  </div>
                )}

                {/* Upload button */}
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={uploading}
                  className="w-full border-2 border-dashed border-blue-300 rounded-lg p-3 text-center hover:border-blue-400 hover:bg-blue-100/50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {uploading ? (
                    <p className="text-xs text-blue-600">Uploading...</p>
                  ) : (
                    <p className="text-xs text-blue-600">
                      {receiptFiles.length > 0 ? 'Upload additional payment advice' : 'Click to upload payment advice (PDF, image)'}
                    </p>
                  )}
                </button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*,.pdf"
                  className="hidden"
                  onChange={handleFileSelect}
                />
              </div>
            )}
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

          {/* Payment Receipt Download (after payment confirmed) */}
          {receiptFiles.length > 0 && (
            <div className="mt-4 pt-4 border-t border-gray-100">
              <p className="text-sm font-medium text-gray-700 mb-2">Payment Receipt</p>
              <div className="space-y-2">
                {receiptFiles.map((file) => (
                  <AdviceFileCard
                    key={file.fileSeq}
                    file={file}
                    onDownload={handleDownloadReceipt}
                  />
                ))}
              </div>
            </div>
          )}
        </Card>
      )}

      {/* Delete Confirm Dialog */}
      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
        title="Delete Payment Advice"
        message={`Delete "${deleteTarget?.originalFilename || 'this file'}"? This action cannot be undone.`}
        confirmLabel="Delete"
        loading={deleting}
      />
    </>
  );
}
