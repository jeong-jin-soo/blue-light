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
import { expiredLicenseOrderApi } from '../../api/expiredLicenseOrderApi';
import priceApi from '../../api/priceApi';
import fileApi from '../../api/fileApi';
import type { ExpiredLicenseOrder, ExpiredLicenseOrderStatus } from '../../types';

const STATUS_CONFIG: Record<ExpiredLicenseOrderStatus, { label: string; color: string }> = {
  PENDING_QUOTE: { label: 'Pending Quote', color: 'bg-blue-100 text-blue-800' },
  QUOTE_PROPOSED: { label: 'Quote Proposed', color: 'bg-yellow-100 text-yellow-800' },
  QUOTE_REJECTED: { label: 'Quote Rejected', color: 'bg-red-100 text-red-800' },
  PENDING_PAYMENT: { label: 'Pending Payment', color: 'bg-orange-100 text-orange-800' },
  PAID: { label: 'Paid', color: 'bg-green-100 text-green-800' },
  VISIT_SCHEDULED: { label: 'Visit Scheduled', color: 'bg-blue-100 text-blue-800' },
  VISIT_COMPLETED: { label: 'Report Ready for Review', color: 'bg-purple-100 text-purple-800' },
  REVISIT_REQUESTED: { label: 'Revisit Requested', color: 'bg-orange-100 text-orange-800' },
  COMPLETED: { label: 'Completed', color: 'bg-green-100 text-green-800' },
};

function ExpiredLicenseStatusBadge({ status }: { status: ExpiredLicenseOrderStatus }) {
  const config = STATUS_CONFIG[status] || { label: status, color: 'bg-gray-100 text-gray-800' };
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${config.color}`}>
      {config.label}
    </span>
  );
}

function formatDateTime(iso?: string): string | null {
  if (!iso) return null;
  return new Date(iso).toLocaleString(undefined, {
    weekday: 'short',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export default function ExpiredLicenseOrderDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToastStore();

  const [order, setOrder] = useState<ExpiredLicenseOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [paymentInfo, setPaymentInfo] = useState<Record<string, string>>({});

  // Revisit request
  const [showRevisitForm, setShowRevisitForm] = useState(false);
  const [revisitComment, setRevisitComment] = useState('');

  // Confirm dialogs
  const [showAcceptConfirm, setShowAcceptConfirm] = useState(false);
  const [showRejectConfirm, setShowRejectConfirm] = useState(false);
  const [showCompleteConfirm, setShowCompleteConfirm] = useState(false);
  const [pdfPreviewUrl, setPdfPreviewUrl] = useState<string | null>(null);

  const orderId = Number(id);

  const fetchData = useCallback(async () => {
    try {
      const orderData = await expiredLicenseOrderApi.getExpiredLicenseOrder(orderId);
      setOrder(orderData);

      if (orderData.status === 'PENDING_PAYMENT') {
        try {
          const info = await priceApi.getPaymentInfo();
          setPaymentInfo(info);
        } catch { /* non-critical */ }
      }
    } catch {
      toast.error('Failed to load Expired License order details');
      navigate('/expired-license-orders');
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Load PDF preview for visit report (VISIT_COMPLETED / COMPLETED)
  const reportFileSeq = order?.visitReportFileSeq;
  useEffect(() => {
    if (!reportFileSeq) return;
    if (order?.status !== 'VISIT_COMPLETED' && order?.status !== 'COMPLETED') return;

    let revoked = false;
    fileApi.getFilePreviewUrl(reportFileSeq).then((url) => {
      if (!revoked) setPdfPreviewUrl(url);
    }).catch(() => { /* non-critical */ });

    return () => {
      revoked = true;
      if (pdfPreviewUrl) window.URL.revokeObjectURL(pdfPreviewUrl);
    };
  }, [reportFileSeq, order?.status]);

  // ── Actions ──

  const handleAcceptQuote = async () => {
    setShowAcceptConfirm(false);
    setActionLoading(true);
    try {
      await expiredLicenseOrderApi.acceptQuote(orderId);
      toast.success('Quote accepted. Please proceed with payment.');
      fetchData();
    } catch { toast.error('Failed to accept quote'); }
    finally { setActionLoading(false); }
  };

  const handleRejectQuote = async () => {
    setShowRejectConfirm(false);
    setActionLoading(true);
    try {
      await expiredLicenseOrderApi.rejectQuote(orderId);
      toast.success('Quote rejected.');
      fetchData();
    } catch { toast.error('Failed to reject quote'); }
    finally { setActionLoading(false); }
  };

  const handleRequestRevisit = async () => {
    if (!revisitComment.trim()) {
      toast.error('Please tell your LEW what still needs attention');
      return;
    }
    setActionLoading(true);
    try {
      await expiredLicenseOrderApi.requestRevisit(orderId, revisitComment.trim());
      toast.success('Revisit requested.');
      setShowRevisitForm(false);
      setRevisitComment('');
      fetchData();
    } catch { toast.error('Failed to request a revisit'); }
    finally { setActionLoading(false); }
  };

  const handleConfirmCompletion = async () => {
    setShowCompleteConfirm(false);
    setActionLoading(true);
    try {
      await expiredLicenseOrderApi.confirmCompletion(orderId);
      toast.success('Expired License order completed!');
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
        <LoadingSpinner size="lg" label="Loading Expired License order..." />
      </div>
    );
  }

  if (!order) return null;

  const isOnSite = order.status === 'VISIT_SCHEDULED' && !!order.checkInAt;
  const visitScheduledDisplay = formatDateTime(order.visitScheduledAt);
  const checkInDisplay = formatDateTime(order.checkInAt);
  const checkOutDisplay = formatDateTime(order.checkOutAt);

  return (
    <div className="space-y-6">
      {/* ON_SITE sticky banner */}
      {isOnSite && (
        <div className="sticky top-0 z-10 -mx-4 sm:mx-0">
          <div className="bg-blue-600 text-white shadow-lg p-3 sm:rounded-lg">
            <div className="flex items-center gap-3">
              <span className="text-2xl" aria-hidden>&#128736;</span>
              <div>
                <p className="font-semibold">Your LEW is on site</p>
                <p className="text-xs text-blue-100">
                  {checkInDisplay ? `Checked in at ${checkInDisplay}` : 'Work in progress'}
                </p>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Page header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/expired-license-orders')}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
            aria-label="Back to Expired License orders"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Back</span>
          </button>
          <div>
            <h1 className="text-xl sm:text-2xl font-bold text-gray-800">
              Expired License Order #{order.expiredLicenseOrderSeq}
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">
              Requested on {new Date(order.createdAt).toLocaleDateString()}
            </p>
          </div>
        </div>
        <ExpiredLicenseStatusBadge status={order.status} />
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

          {/* Supporting Documents */}
          <SupportingDocumentsSection
            order={order}
            onDownload={handleDownloadFile}
            onChanged={fetchData}
          />

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

          {/* PAID / VISIT_SCHEDULED — Visit schedule card */}
          {(order.status === 'PAID' || order.status === 'VISIT_SCHEDULED') && (
            <Card>
              {order.visitScheduledAt ? (
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg" aria-hidden>&#128197;</span>
                    <div>
                      <p className="text-sm font-medium text-blue-900">
                        Your LEW will visit on {visitScheduledDisplay}
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
                      {order.status === 'VISIT_SCHEDULED' && !isOnSite && (
                        <p className="text-xs text-blue-700 mt-2">
                          Your LEW will check in on arrival and submit a report after completing the work.
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
                    <span className="text-lg">&#128197;</span>
                    <div>
                      <p className="text-sm font-medium text-blue-800">Visit Scheduled</p>
                      <p className="text-xs text-blue-700 mt-1">
                        Waiting for your LEW to confirm the exact date &amp; time.
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </Card>
          )}

          {/* VISIT_COMPLETED — Visit Report Viewer */}
          {order.status === 'VISIT_COMPLETED' && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Review Visit Report</h2>
              <div className="bg-purple-50 border border-purple-200 rounded-lg p-4 space-y-3">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128196;</span>
                  <div className="flex-1">
                    <p className="text-sm font-medium text-purple-800">Visit report has been submitted</p>
                    <p className="text-xs text-purple-700 mt-1">
                      Please review the visit report and confirm completion, or request a revisit.
                    </p>
                    {(checkInDisplay || checkOutDisplay) && (
                      <p className="text-xs text-gray-500 mt-2">
                        {checkInDisplay && <>Checked in: {checkInDisplay}</>}
                        {checkInDisplay && checkOutDisplay && <> &middot; </>}
                        {checkOutDisplay && <>Checked out: {checkOutDisplay}</>}
                      </p>
                    )}
                  </div>
                </div>
                {order.managerNote && (
                  <div className="bg-white rounded p-2 border border-purple-100">
                    <p className="text-xs text-gray-500">LEW note:</p>
                    <p className="text-sm text-gray-700 whitespace-pre-wrap">{order.managerNote}</p>
                  </div>
                )}
                {reportFileSeq && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleDownloadFile(reportFileSeq, 'ExpiredLicense_VisitReport')}
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

              {/* Visit photos gallery */}
              {order.visitPhotos && order.visitPhotos.length > 0 && (
                <div className="mt-4">
                  <p className="text-sm font-medium text-gray-700 mb-2">
                    Site Photos ({order.visitPhotos.length})
                  </p>
                  <VisitPhotoGallery
                    photos={order.visitPhotos}
                    onDownload={(fs) => handleDownloadFile(fs, 'ExpiredLicense_SitePhoto')}
                  />
                </div>
              )}

              {/* Revisit request form */}
              {showRevisitForm ? (
                <div className="mt-4 space-y-3">
                  <Textarea
                    label="Revisit Details"
                    placeholder="What still needs attention? (e.g. additional socket didn't work, measurement missing)"
                    value={revisitComment}
                    onChange={(e) => setRevisitComment(e.target.value)}
                    maxLength={2000}
                    rows={3}
                  />
                  <div className="flex gap-2">
                    <Button
                      variant="primary"
                      size="sm"
                      onClick={handleRequestRevisit}
                      loading={actionLoading}
                    >
                      Submit Revisit Request
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => { setShowRevisitForm(false); setRevisitComment(''); }}
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
                    onClick={() => setShowRevisitForm(true)}
                  >
                    Request Revisit
                  </Button>
                </div>
              )}
            </Card>
          )}

          {order.status === 'REVISIT_REQUESTED' && (
            <Card>
              <div className="bg-orange-50 border border-orange-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128221;</span>
                  <div>
                    <p className="text-sm font-medium text-orange-800">Revisit Requested</p>
                    <p className="text-xs text-orange-700 mt-1">
                      Your revisit request has been sent. Your LEW will reach out to arrange a follow-up visit.
                    </p>
                    {order.revisitComment && (
                      <div className="mt-2 bg-white rounded p-2 border border-orange-100">
                        <p className="text-xs text-gray-500">What you asked to be addressed:</p>
                        <p className="text-sm text-gray-700 whitespace-pre-wrap">
                          {order.revisitComment}
                        </p>
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
                      Your Expired License order has been completed.
                    </p>
                    {reportFileSeq && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="mt-2"
                        onClick={() => handleDownloadFile(reportFileSeq, 'ExpiredLicense_VisitReport')}
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
              {order.visitPhotos && order.visitPhotos.length > 0 && (
                <div className="mt-4">
                  <p className="text-sm font-medium text-gray-700 mb-2">
                    Site Photos ({order.visitPhotos.length})
                  </p>
                  <VisitPhotoGallery
                    photos={order.visitPhotos}
                    onDownload={(fs) => handleDownloadFile(fs, 'ExpiredLicense_SitePhoto')}
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
                <span className="font-medium text-gray-700">#{order.expiredLicenseOrderSeq}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Status</span>
                <ExpiredLicenseStatusBadge status={order.status} />
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
        message="Confirm that the visit is complete and the report is acceptable? This action cannot be undone. Once confirmed, no further revisits can be requested."
        confirmLabel="Confirm Completion"
        variant="danger"
        loading={actionLoading}
      />
    </div>
  );
}

/**
 * 방문 사진 썸네일 그리드 (PR 3).
 */
function VisitPhotoGallery({
  photos,
  onDownload,
}: {
  photos: NonNullable<ExpiredLicenseOrder['visitPhotos']>;
  onDownload: (fileSeq: number) => void;
}) {
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
      {photos.map((p) => (
        <button
          key={p.photoSeq}
          type="button"
          onClick={() => onDownload(p.fileSeq)}
          className="relative aspect-square bg-gray-100 rounded-lg overflow-hidden border border-gray-200 hover:border-primary-400 transition-colors"
          title={p.caption || 'Site photo'}
        >
          <div className="absolute inset-0 flex items-center justify-center text-3xl">
            <span aria-hidden>&#128247;</span>
          </div>
          {p.caption && (
            <div className="absolute bottom-0 left-0 right-0 bg-black/60 text-white text-xs p-1 truncate">
              {p.caption}
            </div>
          )}
        </button>
      ))}
    </div>
  );
}

// ────────────────────────────────────────────────────────────
//  SupportingDocumentsSection — 신청자 참고 문서 (다중, 임의 포맷)
// ────────────────────────────────────────────────────────────

const MAX_DOCS = 10;
const MAX_DOC_SIZE = 20 * 1024 * 1024; // 20MB

function formatFileSize(bytes?: number): string {
  if (bytes == null) return '';
  return bytes < 1024 * 1024
    ? `${(bytes / 1024).toFixed(1)} KB`
    : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function SupportingDocumentsSection({
  order,
  onDownload,
  onChanged,
}: {
  order: ExpiredLicenseOrder;
  onDownload: (fileSeq: number, filename: string) => void;
  onChanged: () => void;
}) {
  const toast = useToastStore();
  const docs = order.supportingDocuments ?? [];
  const canModify = order.status === 'PENDING_QUOTE';
  const [uploading, setUploading] = useState(false);
  const [deletingSeq, setDeletingSeq] = useState<number | null>(null);

  const handleAddFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const remaining = MAX_DOCS - docs.length;
    if (remaining <= 0) {
      toast.error(`You can upload a maximum of ${MAX_DOCS} documents.`);
      return;
    }
    const incoming = Array.from(files).slice(0, remaining);
    setUploading(true);
    let failed = 0;
    for (const file of incoming) {
      if (file.size > MAX_DOC_SIZE) {
        toast.error(`${file.name} exceeds the 20MB limit.`);
        failed += 1;
        continue;
      }
      try {
        await expiredLicenseOrderApi.uploadSupportingDocument(order.expiredLicenseOrderSeq, file);
      } catch {
        failed += 1;
      }
    }
    setUploading(false);
    if (failed > 0) {
      toast.warning(`${incoming.length - failed} uploaded, ${failed} failed.`);
    } else {
      toast.success(`${incoming.length} document(s) uploaded.`);
    }
    onChanged();
  };

  const handleDelete = async (fileSeq: number) => {
    setDeletingSeq(fileSeq);
    try {
      await expiredLicenseOrderApi.deleteSupportingDocument(order.expiredLicenseOrderSeq, fileSeq);
      toast.success('Document removed.');
      onChanged();
    } catch {
      toast.error('Failed to remove document.');
    } finally {
      setDeletingSeq(null);
    }
  };

  if (docs.length === 0 && !canModify) return null;

  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">
        Shared Documents {docs.length > 0 && <span className="text-sm font-normal text-gray-400">({docs.length}/{MAX_DOCS})</span>}
      </h2>
      {docs.length === 0 ? (
        <p className="text-sm text-gray-500 mb-3">No supporting documents uploaded yet.</p>
      ) : (
        <ul className="space-y-2 mb-3">
          {docs.map((doc) => (
            <li
              key={doc.fileSeq}
              className="flex items-center justify-between px-3 py-2.5 bg-gray-50 rounded-lg border border-gray-200"
            >
              <button
                type="button"
                onClick={() => onDownload(doc.fileSeq, doc.originalFilename || `document_${doc.fileSeq}`)}
                className="flex items-center gap-2 min-w-0 text-left hover:text-primary-600 transition-colors"
              >
                <span className="text-lg">📄</span>
                <div className="min-w-0">
                  <p className="text-sm font-medium text-gray-700 truncate">
                    {doc.originalFilename || `File #${doc.fileSeq}`}
                  </p>
                  <p className="text-xs text-gray-400">{formatFileSize(doc.fileSize)}</p>
                </div>
              </button>
              {canModify && (
                <button
                  type="button"
                  disabled={deletingSeq === doc.fileSeq}
                  onClick={() => handleDelete(doc.fileSeq)}
                  className="text-gray-400 hover:text-red-500 transition-colors p-1 disabled:opacity-50"
                  aria-label={`Remove ${doc.originalFilename}`}
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canModify && docs.length < MAX_DOCS && (
        <label className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors">
          <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
          <span className="text-sm text-gray-600">
            {uploading ? 'Uploading…' : `Add document${docs.length > 0 ? 's' : ''}`}
          </span>
          <input
            type="file"
            multiple
            disabled={uploading}
            className="hidden"
            onChange={(e) => {
              void handleAddFiles(e.target.files);
              e.target.value = '';
            }}
          />
        </label>
      )}
    </Card>
  );
}
