import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { fullName } from '../../utils/formatName';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Textarea } from '../../components/ui/Textarea';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { InfoField } from '../../components/common/InfoField';
import { ExpiredLicenseStatusBadge } from '../../components/domain/ExpiredLicenseStatusBadge';
import { useToastStore } from '../../stores/toastStore';
import { expiredLicenseManagerApi } from '../../api/expiredLicenseManagerApi';
import fileApi from '../../api/fileApi';
import { OnSiteChecklistCard } from './sections/OnSiteChecklistCard';
import { VisitCompletionForm } from './sections/VisitCompletionForm';
import type { ExpiredLicenseOrder } from '../../types';

export default function ExpiredLicenseManagerOrderDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToastStore();

  const [order, setOrder] = useState<ExpiredLicenseOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  const [quoteAmount, setQuoteAmount] = useState('');
  const [quoteNote, setQuoteNote] = useState('');

  const [showPaymentConfirm, setShowPaymentConfirm] = useState(false);

  // Expired License Service 방문형 리스키닝 PR 2 — 방문 일정 예약
  const [visitScheduleEditing, setVisitScheduleEditing] = useState(false);
  const [visitScheduledAtInput, setVisitScheduledAtInput] = useState('');
  const [visitScheduleNoteInput, setVisitScheduleNoteInput] = useState('');
  const [visitScheduleSaving, setVisitScheduleSaving] = useState(false);

  const orderId = Number(id);

  const fetchData = useCallback(async () => {
    try {
      const data = await expiredLicenseManagerApi.getOrder(orderId);
      setOrder(data);
    } catch {
      toast.error('Failed to load Expired License order details');
      navigate('/expired-license-manager/orders');
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleProposeQuote = async () => {
    const amount = Number(quoteAmount);
    if (!amount || amount <= 0) {
      toast.error('Please enter a valid quote amount');
      return;
    }
    setActionLoading(true);
    try {
      await expiredLicenseManagerApi.proposeQuote(orderId, {
        quoteAmount: amount,
        quoteNote: quoteNote.trim() || undefined,
      });
      toast.success('Quote proposed successfully');
      setQuoteAmount('');
      setQuoteNote('');
      fetchData();
    } catch { toast.error('Failed to propose quote'); }
    finally { setActionLoading(false); }
  };

  const handleConfirmPayment = async () => {
    setShowPaymentConfirm(false);
    setActionLoading(true);
    try {
      await expiredLicenseManagerApi.confirmPayment(orderId);
      toast.success('Payment confirmed');
      fetchData();
    } catch { toast.error('Failed to confirm payment'); }
    finally { setActionLoading(false); }
  };

  const handleDownloadFile = async (fileSeq: number, filename: string) => {
    try {
      await fileApi.downloadFile(fileSeq, filename);
    } catch {
      toast.error('Failed to download file');
    }
  };

  /** <input type="datetime-local"> 값 "YYYY-MM-DDTHH:mm" → 백엔드 LocalDateTime 포맷 */
  const toBackendLocalDateTime = (value: string): string => {
    if (!value) return value;
    return value.length === 16 ? `${value}:00` : value;
  };

  /** 백엔드 ISO 문자열 → <input type="datetime-local"> 용 "YYYY-MM-DDTHH:mm" */
  const toInputLocalDateTime = (iso?: string): string => {
    if (!iso) return '';
    return iso.slice(0, 16);
  };

  const handleStartEditSchedule = () => {
    setVisitScheduledAtInput(toInputLocalDateTime(order?.visitScheduledAt));
    setVisitScheduleNoteInput(order?.visitScheduleNote ?? '');
    setVisitScheduleEditing(true);
  };

  const handleCancelEditSchedule = () => {
    setVisitScheduleEditing(false);
    setVisitScheduledAtInput('');
    setVisitScheduleNoteInput('');
  };

  const handleSaveSchedule = async () => {
    if (!visitScheduledAtInput) {
      toast.error('Please pick a visit date & time');
      return;
    }
    setVisitScheduleSaving(true);
    try {
      await expiredLicenseManagerApi.scheduleVisit(orderId, {
        visitScheduledAt: toBackendLocalDateTime(visitScheduledAtInput),
        visitScheduleNote: visitScheduleNoteInput.trim() || undefined,
      });
      toast.success('Visit time saved');
      setVisitScheduleEditing(false);
      fetchData();
    } catch {
      toast.error('Failed to save visit schedule');
    } finally {
      setVisitScheduleSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading Expired License order..." />
      </div>
    );
  }

  if (!order) return null;

  const isOnSite = order.status === 'VISIT_SCHEDULED' && !!order.checkInAt;
  // 방문 일정은 PAID / VISIT_SCHEDULED 에서 편집 가능
  const showVisitScheduleSection = ['PAID', 'VISIT_SCHEDULED'].includes(order.status);
  const visitScheduledAtDisplay = order.visitScheduledAt
    ? new Date(order.visitScheduledAt).toLocaleString(undefined, {
        weekday: 'short',
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
    : null;
  const reportFileSeq = order.visitReportFileSeq;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/expired-license-manager/orders')}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
            aria-label="Back to orders list"
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
            <p className="text-sm text-gray-500 mt-0.5">Manager view</p>
          </div>
        </div>
        <ExpiredLicenseStatusBadge status={order.status} />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-6">
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Applicant & Order Info</h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <InfoField label="Applicant" value={fullName(order.userFirstName, order.userLastName)} />
              <InfoField label="Email" value={order.userEmail} />
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
            {order.supportingDocuments && order.supportingDocuments.length > 0 && (
              <div className="mt-4 pt-4 border-t border-gray-100">
                <p className="text-sm font-medium text-gray-700 mb-2">
                  Applicant Documents ({order.supportingDocuments.length})
                </p>
                <div className="flex flex-wrap gap-2">
                  {order.supportingDocuments.map((doc) => (
                    <Button
                      key={doc.fileSeq}
                      variant="outline"
                      size="sm"
                      onClick={() => handleDownloadFile(doc.fileSeq, doc.originalFilename || `document_${doc.fileSeq}`)}
                    >
                      📄 {doc.originalFilename || `File #${doc.fileSeq}`}
                    </Button>
                  ))}
                </div>
              </div>
            )}
          </Card>

          {order.status === 'PENDING_QUOTE' && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Propose Quote</h2>
              <div className="space-y-4">
                <Input
                  label="Quote Amount (SGD)"
                  type="number"
                  placeholder="e.g., 500"
                  value={quoteAmount}
                  onChange={(e) => setQuoteAmount(e.target.value)}
                  min={0}
                  required
                />
                <Textarea
                  label="Quote Note (Optional)"
                  placeholder="Additional notes about the quote..."
                  value={quoteNote}
                  onChange={(e) => setQuoteNote(e.target.value)}
                  maxLength={2000}
                  rows={3}
                />
                <Button
                  variant="primary"
                  onClick={handleProposeQuote}
                  loading={actionLoading}
                >
                  Submit Quote
                </Button>
              </div>
            </Card>
          )}

          {order.status === 'QUOTE_PROPOSED' && (
            <Card>
              <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#9202;</span>
                  <div>
                    <p className="text-sm font-medium text-yellow-800">Quote proposed. Waiting for applicant response.</p>
                    <p className="text-xs text-yellow-700 mt-1">
                      Quote: SGD ${order.quoteAmount?.toLocaleString()}
                    </p>
                    {order.quoteNote && (
                      <div className="mt-2 bg-white rounded p-2 border border-yellow-100">
                        <p className="text-xs text-gray-500">Quote note:</p>
                        <p className="text-sm text-gray-700 whitespace-pre-wrap">{order.quoteNote}</p>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </Card>
          )}

          {order.status === 'QUOTE_REJECTED' && (
            <Card>
              <div className="bg-red-50 border border-red-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#10060;</span>
                  <div>
                    <p className="text-sm font-medium text-red-800">Quote has been rejected.</p>
                    <p className="text-xs text-red-700 mt-1">
                      The applicant has rejected the proposed quote.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {order.status === 'PENDING_PAYMENT' && (
            <Card>
              <div className="bg-orange-50 border border-orange-200 rounded-lg p-4 mb-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128179;</span>
                  <div>
                    <p className="text-sm font-medium text-orange-800">Awaiting Payment</p>
                    <p className="text-xs text-orange-700 mt-1">
                      Waiting for applicant payment of SGD ${order.quoteAmount?.toLocaleString()}.
                    </p>
                  </div>
                </div>
              </div>
              <Button
                variant="primary"
                onClick={() => setShowPaymentConfirm(true)}
                loading={actionLoading}
              >
                Confirm Payment
              </Button>
            </Card>
          )}

          {order.status === 'PAID' && (
            <Card>
              <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#9989;</span>
                  <div>
                    <p className="text-sm font-medium text-green-800">Payment confirmed. Schedule the on-site visit with the applicant.</p>
                    <p className="text-xs text-green-700 mt-1">
                      Payment has been confirmed. Coordinate a visit time with the applicant, then perform the work on site and submit the visit report.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {/* Expired License Service 방문형 리스키닝 PR 2 — Visit Schedule Section */}
          {showVisitScheduleSection && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Visit Schedule</h2>

              {/* 저장된 예약이 있고 편집 중이 아닐 때 */}
              {order.visitScheduledAt && !visitScheduleEditing && (
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 space-y-2">
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex items-start gap-3">
                      <span className="text-lg" aria-hidden>&#128197;</span>
                      <div>
                        <p className="text-sm font-medium text-blue-900">
                          Scheduled for {visitScheduledAtDisplay}
                        </p>
                        {order.visitScheduleNote && (
                          <p className="text-sm text-gray-700 mt-1 whitespace-pre-wrap">
                            {order.visitScheduleNote}
                          </p>
                        )}
                      </div>
                    </div>
                    <Button variant="outline" size="sm" onClick={handleStartEditSchedule}>
                      Edit
                    </Button>
                  </div>
                </div>
              )}

              {/* 저장된 예약이 없고 편집 중이 아닐 때 */}
              {!order.visitScheduledAt && !visitScheduleEditing && (
                <div className="space-y-3">
                  <p className="text-sm text-gray-600">
                    No visit has been scheduled yet. Agree on a date &amp; time with the applicant,
                    then save it here so they can plan accordingly.
                  </p>
                  <Button variant="primary" size="sm" onClick={handleStartEditSchedule}>
                    Schedule Visit
                  </Button>
                </div>
              )}

              {/* 편집 모드 */}
              {visitScheduleEditing && (
                <div className="space-y-4">
                  <Input
                    label="Visit Date &amp; Time"
                    type="datetime-local"
                    value={visitScheduledAtInput}
                    onChange={(e) => setVisitScheduledAtInput(e.target.value)}
                    required
                  />
                  <Textarea
                    label="Note (Optional)"
                    placeholder="e.g. Doorbell is broken — please call on arrival"
                    value={visitScheduleNoteInput}
                    onChange={(e) => setVisitScheduleNoteInput(e.target.value)}
                    maxLength={2000}
                    rows={3}
                  />
                  <div className="flex gap-2">
                    <Button
                      variant="primary"
                      size="sm"
                      onClick={handleSaveSchedule}
                      loading={visitScheduleSaving}
                    >
                      Save Visit Time
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={handleCancelEditSchedule}
                      disabled={visitScheduleSaving}
                    >
                      Cancel
                    </Button>
                  </div>
                </div>
              )}
            </Card>
          )}

          {/* PR 3: VISIT_SCHEDULED + not checked in → OnSiteChecklistCard */}
          {order.status === 'VISIT_SCHEDULED' && !order.checkInAt && (
            <OnSiteChecklistCard orderId={orderId} onCheckedIn={fetchData} />
          )}

          {/* PR 3: VISIT_SCHEDULED + ON_SITE → VisitCompletionForm */}
          {isOnSite && (
            <>
              <Card>
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg">&#128736;</span>
                    <div>
                      <p className="text-sm font-medium text-blue-800">On-site Visit In Progress</p>
                      <p className="text-xs text-blue-700 mt-1">
                        Checked in at {new Date(order.checkInAt!).toLocaleString()}. Complete the
                        on-site work, upload photos and the report, then check out.
                      </p>
                    </div>
                  </div>
                </div>
              </Card>
              <VisitCompletionForm orderId={orderId} order={order} onCompleted={fetchData} />
            </>
          )}

          {order.status === 'REVISIT_REQUESTED' && (
            <Card>
              <div className="bg-orange-50 border border-orange-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128221;</span>
                  <div>
                    <p className="text-sm font-medium text-orange-800">Revisit Requested</p>
                    <p className="text-xs text-orange-700 mt-1">
                      The applicant has requested a follow-up visit. Schedule a new visit time above.
                    </p>
                    {order.revisitComment && (
                      <div className="mt-2 bg-white rounded p-2 border border-orange-100">
                        <p className="text-xs text-gray-500">Revisit reason:</p>
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

          {order.status === 'VISIT_COMPLETED' && (
            <Card>
              <div className="bg-purple-50 border border-purple-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128196;</span>
                  <div className="flex-1">
                    <p className="text-sm font-medium text-purple-800">Visit report submitted. Waiting for applicant review.</p>
                    <p className="text-xs text-purple-700 mt-1">
                      The applicant will review and either confirm completion or request a revisit.
                    </p>
                    {order.managerNote && (
                      <div className="mt-2 bg-white rounded p-2 border border-purple-100">
                        <p className="text-xs text-gray-500">LEW note:</p>
                        <p className="text-sm text-gray-700 whitespace-pre-wrap">{order.managerNote}</p>
                      </div>
                    )}
                    {reportFileSeq && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="mt-2"
                        onClick={() => handleDownloadFile(reportFileSeq, 'LEW_Service_Visit_Report')}
                      >
                        Download Visit Report
                      </Button>
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
                      This on-site service order has been completed successfully.
                    </p>
                    {reportFileSeq && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="mt-2"
                        onClick={() => handleDownloadFile(reportFileSeq, 'LEW_Service_Visit_Report')}
                      >
                        Download Visit Report
                      </Button>
                    )}
                  </div>
                </div>
              </div>
            </Card>
          )}
        </div>

        <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
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
              {order.checkInAt && (
                <div className="flex justify-between">
                  <span className="text-gray-500">Checked In</span>
                  <span className="font-medium text-gray-700">
                    {new Date(order.checkInAt).toLocaleString()}
                  </span>
                </div>
              )}
              {order.checkOutAt && (
                <div className="flex justify-between">
                  <span className="text-gray-500">Checked Out</span>
                  <span className="font-medium text-gray-700">
                    {new Date(order.checkOutAt).toLocaleString()}
                  </span>
                </div>
              )}
            </div>
          </Card>

          <Card>
            <h3 className="text-sm font-semibold text-gray-800 mb-3">Applicant</h3>
            <div className="flex items-center gap-3 p-3 bg-gray-50 rounded-lg border border-gray-200">
              <div className="w-8 h-8 rounded-full bg-gray-200 flex items-center justify-center">
                <span className="text-sm">&#128100;</span>
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-800">{fullName(order.userFirstName, order.userLastName)}</p>
                <p className="text-xs text-gray-500 truncate">{order.userEmail}</p>
              </div>
            </div>
          </Card>

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

      <ConfirmDialog
        isOpen={showPaymentConfirm}
        onClose={() => setShowPaymentConfirm(false)}
        onConfirm={handleConfirmPayment}
        title="Confirm Payment"
        message={`Confirm payment of SGD $${order.quoteAmount?.toLocaleString()} has been received?`}
        confirmLabel="Confirm Payment"
        loading={actionLoading}
      />
    </div>
  );
}
