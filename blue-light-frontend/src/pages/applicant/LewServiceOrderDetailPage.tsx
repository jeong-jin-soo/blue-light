import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { fullName } from '../../utils/formatName';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Textarea } from '../../components/ui/Textarea';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { InfoField } from '../../components/common/InfoField';
import { useToastStore } from '../../stores/toastStore';
import { lewServiceOrderApi } from '../../api/lewServiceOrderApi';
import priceApi from '../../api/priceApi';
import fileApi from '../../api/fileApi';
import type { LewServiceOrder, LewServiceOrderStatus } from '../../types';

const STATUS_CONFIG: Record<LewServiceOrderStatus, { label: string; color: string }> = {
  PENDING_QUOTE: { label: 'Pending Quote', color: 'bg-blue-100 text-blue-800' },
  QUOTE_PROPOSED: { label: 'Quote Proposed', color: 'bg-yellow-100 text-yellow-800' },
  QUOTE_REJECTED: { label: 'Quote Rejected', color: 'bg-red-100 text-red-800' },
  PENDING_PAYMENT: { label: 'Pending Payment', color: 'bg-orange-100 text-orange-800' },
  PAID: { label: 'Paid', color: 'bg-green-100 text-green-800' },
  IN_PROGRESS: { label: 'Visit Scheduled', color: 'bg-blue-100 text-blue-800' },
  SLD_UPLOADED: { label: 'Report Ready for Review', color: 'bg-purple-100 text-purple-800' },
  REVISION_REQUESTED: { label: 'Revisit Requested', color: 'bg-orange-100 text-orange-800' },
  COMPLETED: { label: 'Completed', color: 'bg-green-100 text-green-800' },
};

function LewServiceStatusBadge({ status }: { status: LewServiceOrderStatus }) {
  const config = STATUS_CONFIG[status] || { label: status, color: 'bg-gray-100 text-gray-800' };
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${config.color}`}>
      {config.label}
    </span>
  );
}

export default function LewServiceOrderDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToastStore();

  const [order, setOrder] = useState<LewServiceOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [paymentInfo, setPaymentInfo] = useState<Record<string, string>>({});

  // Revision request
  const [showRevisionForm, setShowRevisionForm] = useState(false);
  const [revisionComment, setRevisionComment] = useState('');

  // Confirm dialogs
  const [showAcceptConfirm, setShowAcceptConfirm] = useState(false);
  const [showRejectConfirm, setShowRejectConfirm] = useState(false);
  const [showCompleteConfirm, setShowCompleteConfirm] = useState(false);
  const [pdfPreviewUrl, setPdfPreviewUrl] = useState<string | null>(null);

  const orderId = Number(id);

  const fetchData = useCallback(async () => {
    try {
      const orderData = await lewServiceOrderApi.getLewServiceOrder(orderId);
      setOrder(orderData);

      if (orderData.status === 'PENDING_PAYMENT') {
        try {
          const info = await priceApi.getPaymentInfo();
          setPaymentInfo(info);
        } catch { /* non-critical */ }
      }
    } catch {
      toast.error('Failed to load LEW Service order details');
      navigate('/lew-service-orders');
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Load PDF preview for SLD_UPLOADED / COMPLETED status
  useEffect(() => {
    if (!order?.uploadedFileSeq) return;
    if (order.status !== 'SLD_UPLOADED' && order.status !== 'COMPLETED') return;

    let revoked = false;
    fileApi.getFilePreviewUrl(order.uploadedFileSeq).then((url) => {
      if (!revoked) setPdfPreviewUrl(url);
    }).catch(() => { /* non-critical */ });

    return () => {
      revoked = true;
      if (pdfPreviewUrl) window.URL.revokeObjectURL(pdfPreviewUrl);
    };
  }, [order?.uploadedFileSeq, order?.status]);

  // ── Actions ──

  const handleAcceptQuote = async () => {
    setShowAcceptConfirm(false);
    setActionLoading(true);
    try {
      await lewServiceOrderApi.acceptQuote(orderId);
      toast.success('Quote accepted. Please proceed with payment.');
      fetchData();
    } catch { toast.error('Failed to accept quote'); }
    finally { setActionLoading(false); }
  };

  const handleRejectQuote = async () => {
    setShowRejectConfirm(false);
    setActionLoading(true);
    try {
      await lewServiceOrderApi.rejectQuote(orderId);
      toast.success('Quote rejected.');
      fetchData();
    } catch { toast.error('Failed to reject quote'); }
    finally { setActionLoading(false); }
  };

  const handleRequestRevision = async () => {
    if (!revisionComment.trim()) {
      toast.error('Please tell your LEW what still needs attention');
      return;
    }
    setActionLoading(true);
    try {
      await lewServiceOrderApi.requestRevision(orderId, revisionComment.trim());
      toast.success('Revisit requested.');
      setShowRevisionForm(false);
      setRevisionComment('');
      fetchData();
    } catch { toast.error('Failed to request a revisit'); }
    finally { setActionLoading(false); }
  };

  const handleConfirmCompletion = async () => {
    setShowCompleteConfirm(false);
    setActionLoading(true);
    try {
      await lewServiceOrderApi.confirmCompletion(orderId);
      toast.success('LEW Service order completed!');
      fetchData();
    } catch { toast.error('Failed to confirm completion'); }
    finally { setActionLoading(false); }
  };

  const handleDownloadFile = async (fileSeq: number, filename: string) => {
    try {
      await fileApi.downloadFile(fileSeq, filename);
    } catch {
      toast.error('Failed to download file');
    }
  };

  // ── Render ──

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading LEW Service order..." />
      </div>
    );
  }

  if (!order) return null;

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/lew-service-orders')}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
            aria-label="Back to LEW Service orders"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Back</span>
          </button>
          <div>
            <h1 className="text-xl sm:text-2xl font-bold text-gray-800">
              LEW Service Order #{order.lewServiceOrderSeq}
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">
              Requested on {new Date(order.createdAt).toLocaleDateString()}
            </p>
          </div>
        </div>
        <LewServiceStatusBadge status={order.status} />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Main content */}
        <div className="lg:col-span-2 space-y-6">
          {/* Order Info */}
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Order Details</h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <InfoField label="Address" value={order.address || '-'} />
              <InfoField label="Postal Code" value={order.postalCode || '-'} />
              <InfoField label="Building Type" value={order.buildingType || '-'} />
              <InfoField label="Capacity (kVA)" value={order.selectedKva ? `${order.selectedKva} kVA` : '-'} />
            </div>
            {order.applicantNote && (
              <div className="mt-4 pt-4 border-t border-gray-100">
                <InfoField label="Requirements Note" value={order.applicantNote} />
              </div>
            )}
            {order.assignedManagerFirstName && (
              <div className="mt-4 pt-4 border-t border-gray-100">
                <InfoField label="Assigned Manager" value={fullName(order.assignedManagerFirstName, order.assignedManagerLastName)} />
              </div>
            )}
          </Card>

          {/* Sketch File Download */}
          {order.sketchFileSeq && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Sketch File</h2>
              <Button
                variant="outline"
                size="sm"
                onClick={() => handleDownloadFile(order.sketchFileSeq!, 'Sketch_File')}
              >
                Download Sketch
              </Button>
            </Card>
          )}

          {/* Status-specific section */}
          {order.status === 'PENDING_QUOTE' && (
            <Card>
              <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">🔍</span>
                  <div>
                    <p className="text-sm font-medium text-blue-800">Reviewing Your Request</p>
                    <p className="text-xs text-blue-700 mt-1">
                      Your request is being reviewed. You will receive a quote soon.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {order.status === 'QUOTE_PROPOSED' && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Quote Proposal</h2>
              <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm text-gray-600">Quote Amount</span>
                  <span className="text-xl font-bold text-gray-800">
                    SGD ${order.quoteAmount?.toLocaleString()}
                  </span>
                </div>
                {order.quoteNote && (
                  <div className="border-t border-yellow-200 pt-3">
                    <p className="text-xs text-gray-500 mb-1">Quote Note:</p>
                    <p className="text-sm text-gray-700 whitespace-pre-wrap">{order.quoteNote}</p>
                  </div>
                )}
              </div>
              <div className="flex gap-3 mt-4">
                <Button
                  variant="primary"
                  onClick={() => setShowAcceptConfirm(true)}
                  loading={actionLoading}
                >
                  Accept
                </Button>
                <Button
                  variant="outline"
                  onClick={() => setShowRejectConfirm(true)}
                  loading={actionLoading}
                >
                  Reject
                </Button>
              </div>
            </Card>
          )}

          {order.status === 'QUOTE_REJECTED' && (
            <Card>
              <div className="bg-red-50 border border-red-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#10060;</span>
                  <div>
                    <p className="text-sm font-medium text-red-800">Quote Rejected</p>
                    <p className="text-xs text-red-700 mt-1">
                      The quote has been rejected. This order is closed.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {order.status === 'PENDING_PAYMENT' && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Payment Instructions</h2>
              <div className="bg-orange-50 border border-orange-200 rounded-lg p-4 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm text-gray-600">Payment Amount</span>
                  <span className="text-xl font-bold text-gray-800">
                    SGD ${order.quoteAmount?.toLocaleString()}
                  </span>
                </div>
                {Object.keys(paymentInfo).length > 0 && (
                  <div className="border-t border-orange-200 pt-3 space-y-2">
                    <p className="text-sm font-medium text-gray-700">Payment Information:</p>
                    {Object.entries(paymentInfo).map(([key, value]) => (
                      <div key={key} className="flex justify-between text-sm">
                        <span className="text-gray-500">{key}</span>
                        <span className="font-medium text-gray-700">{value}</span>
                      </div>
                    ))}
                  </div>
                )}
                <p className="text-xs text-orange-700">
                  Please make the payment and wait for confirmation from the manager.
                </p>
              </div>
            </Card>
          )}

          {/* LEW Service 방문형 리스키닝 PR 2 — Visit Schedule Card (applicant read-only) */}
          {(order.status === 'PAID' || order.status === 'IN_PROGRESS') && (
            <Card>
              {order.visitScheduledAt ? (
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg" aria-hidden>&#128197;</span>
                    <div>
                      <p className="text-sm font-medium text-blue-900">
                        Your LEW will visit on {new Date(order.visitScheduledAt).toLocaleString(undefined, {
                          weekday: 'short',
                          year: 'numeric',
                          month: 'short',
                          day: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit',
                        })}
                      </p>
                      {order.visitScheduleNote && (
                        <p className="text-sm text-gray-700 mt-1 whitespace-pre-wrap">
                          {order.visitScheduleNote}
                        </p>
                      )}
                      {order.status === 'PAID' && (
                        <p className="text-xs text-blue-700 mt-2">
                          Please make sure someone is available at the site at the scheduled time.
                        </p>
                      )}
                      {order.status === 'IN_PROGRESS' && (
                        <p className="text-xs text-blue-700 mt-2">
                          Your LEW will submit a visit report after completing the on-site work.
                        </p>
                      )}
                    </div>
                  </div>
                </div>
              ) : order.status === 'PAID' ? (
                <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg">&#9989;</span>
                    <div>
                      <p className="text-sm font-medium text-green-800">Payment Confirmed</p>
                      <p className="text-xs text-green-700 mt-1">
                        Your LEW will contact you to schedule the visit.
                      </p>
                    </div>
                  </div>
                </div>
              ) : (
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg">&#128736;</span>
                    <div>
                      <p className="text-sm font-medium text-blue-800">On-site Visit In Progress</p>
                      <p className="text-xs text-blue-700 mt-1">
                        Your LEW is working on the on-site visit. You'll be notified when the visit report is submitted.
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </Card>
          )}

          {order.status === 'SLD_UPLOADED' && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Review Visit Report</h2>
              <div className="bg-purple-50 border border-purple-200 rounded-lg p-4 space-y-3">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128196;</span>
                  <div>
                    <p className="text-sm font-medium text-purple-800">Visit report has been submitted</p>
                    <p className="text-xs text-purple-700 mt-1">
                      Please review the visit report and confirm completion, or request a revisit.
                    </p>
                  </div>
                </div>
                {order.managerNote && (
                  <div className="bg-white rounded p-2 border border-purple-100">
                    <p className="text-xs text-gray-500">LEW note:</p>
                    <p className="text-sm text-gray-700 whitespace-pre-wrap">{order.managerNote}</p>
                  </div>
                )}
                {order.uploadedFileSeq && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleDownloadFile(order.uploadedFileSeq!, 'LewService_VisitReport')}
                  >
                    Download Visit Report
                  </Button>
                )}
              </div>

              {/* PDF inline preview */}
              {pdfPreviewUrl && (
                <div className="mt-4 border border-gray-200 rounded-lg overflow-hidden">
                  <iframe
                    src={pdfPreviewUrl}
                    title="Visit Report Preview"
                    className="w-full bg-white"
                    style={{ height: '500px' }}
                  />
                </div>
              )}

              {/* Revision request form */}
              {showRevisionForm ? (
                <div className="mt-4 space-y-3">
                  <Textarea
                    label="Revisit Details"
                    placeholder="What still needs attention? (e.g. additional socket didn't work, measurement missing)"
                    value={revisionComment}
                    onChange={(e) => setRevisionComment(e.target.value)}
                    maxLength={2000}
                    rows={3}
                  />
                  <div className="flex gap-2">
                    <Button
                      variant="primary"
                      size="sm"
                      onClick={handleRequestRevision}
                      loading={actionLoading}
                    >
                      Submit Revision Request
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => { setShowRevisionForm(false); setRevisionComment(''); }}
                    >
                      Cancel
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="flex gap-3 mt-4">
                  <Button
                    variant="primary"
                    onClick={() => setShowCompleteConfirm(true)}
                    loading={actionLoading}
                  >
                    Confirm Completion
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => setShowRevisionForm(true)}
                  >
                    Request Revisit
                  </Button>
                </div>
              )}
            </Card>
          )}

          {order.status === 'REVISION_REQUESTED' && (
            <Card>
              <div className="bg-orange-50 border border-orange-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128221;</span>
                  <div>
                    <p className="text-sm font-medium text-orange-800">Revisit Requested</p>
                    <p className="text-xs text-orange-700 mt-1">
                      Your revisit request has been sent. Your LEW will reach out to arrange a follow-up visit.
                    </p>
                    {order.revisionComment && (
                      <div className="mt-2 bg-white rounded p-2 border border-orange-100">
                        <p className="text-xs text-gray-500">What you asked to be addressed:</p>
                        <p className="text-sm text-gray-700 whitespace-pre-wrap">{order.revisionComment}</p>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </Card>
          )}

          {order.status === 'COMPLETED' && (
            <Card>
              <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#9989;</span>
                  <div className="flex-1">
                    <p className="text-sm font-medium text-green-800">Completed</p>
                    <p className="text-xs text-green-700 mt-1">
                      Your LEW Service order has been completed.
                    </p>
                    {order.uploadedFileSeq && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="mt-2"
                        onClick={() => handleDownloadFile(order.uploadedFileSeq!, 'LewService_VisitReport')}
                      >
                        Download Visit Report
                      </Button>
                    )}
                  </div>
                </div>
              </div>
              {/* PDF inline preview */}
              {pdfPreviewUrl && (
                <div className="mt-4 border border-gray-200 rounded-lg overflow-hidden">
                  <iframe
                    src={pdfPreviewUrl}
                    title="Visit Report Preview"
                    className="w-full bg-white"
                    style={{ height: '500px' }}
                  />
                </div>
              )}
            </Card>
          )}
        </div>

        {/* Sidebar */}
        <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
          {/* Quick Info */}
          <Card>
            <h3 className="text-sm font-semibold text-gray-800 mb-3">Quick Info</h3>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-gray-500">Order ID</span>
                <span className="font-medium text-gray-700">#{order.lewServiceOrderSeq}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Status</span>
                <LewServiceStatusBadge status={order.status} />
              </div>
              {order.quoteAmount != null && (
                <div className="flex justify-between">
                  <span className="text-gray-500">Quote</span>
                  <span className="font-medium text-gray-700">SGD ${order.quoteAmount.toLocaleString()}</span>
                </div>
              )}
              <div className="flex justify-between">
                <span className="text-gray-500">Requested</span>
                <span className="font-medium text-gray-700">
                  {new Date(order.createdAt).toLocaleDateString()}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Last Updated</span>
                <span className="font-medium text-gray-700">
                  {new Date(order.updatedAt).toLocaleDateString()}
                </span>
              </div>
            </div>
          </Card>

          {/* Assigned Manager */}
          {order.assignedManagerFirstName && (
            <Card>
              <h3 className="text-sm font-semibold text-gray-800 mb-3">Assigned Manager</h3>
              <div className="flex items-center gap-3 p-3 bg-primary-50 rounded-lg border border-primary-100">
                <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
                  <span className="text-sm">&#128100;</span>
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-800">{fullName(order.assignedManagerFirstName, order.assignedManagerLastName)}</p>
                </div>
              </div>
            </Card>
          )}
        </div>
      </div>

      {/* Confirm dialogs */}
      <ConfirmDialog
        isOpen={showAcceptConfirm}
        onClose={() => setShowAcceptConfirm(false)}
        onConfirm={handleAcceptQuote}
        title="Accept Quote"
        message={`Accept the quote of SGD $${order.quoteAmount?.toLocaleString()}? You will be asked to make payment.`}
        confirmLabel="Accept"
        loading={actionLoading}
      />

      <ConfirmDialog
        isOpen={showRejectConfirm}
        onClose={() => setShowRejectConfirm(false)}
        onConfirm={handleRejectQuote}
        title="Reject Quote"
        message="Are you sure you want to reject this quote? This action cannot be undone."
        confirmLabel="Reject"
        variant="danger"
        loading={actionLoading}
      />

      <ConfirmDialog
        isOpen={showCompleteConfirm}
        onClose={() => setShowCompleteConfirm(false)}
        onConfirm={handleConfirmCompletion}
        title="Confirm Completion"
        message="Confirm that the uploaded LEW Service is complete and acceptable? This action cannot be undone. Once confirmed, no further revisions can be requested."
        confirmLabel="Confirm Completion"
        variant="danger"
        loading={actionLoading}
      />
    </div>
  );
}
