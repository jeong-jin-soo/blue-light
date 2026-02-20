import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Textarea } from '../../components/ui/Textarea';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { InfoField } from '../../components/common/InfoField';
import { useToastStore } from '../../stores/toastStore';
import { sldManagerApi } from '../../api/sldManagerApi';
import fileApi from '../../api/fileApi';
import { SldManagerSldSection } from './sections/SldManagerSldSection';
import type { SldOrder, SldOrderStatus } from '../../types';

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

export default function SldManagerOrderDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToastStore();

  const [order, setOrder] = useState<SldOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  // Quote form
  const [quoteAmount, setQuoteAmount] = useState('');
  const [quoteNote, setQuoteNote] = useState('');

  // Payment confirm
  const [showPaymentConfirm, setShowPaymentConfirm] = useState(false);

  // Complete confirm
  const [showCompleteConfirm, setShowCompleteConfirm] = useState(false);

  const orderId = Number(id);

  const fetchData = useCallback(async () => {
    try {
      const data = await sldManagerApi.getOrder(orderId);
      setOrder(data);
    } catch {
      toast.error('Failed to load SLD order details');
      navigate('/sld-manager/orders');
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // ── Actions ──

  const handleProposeQuote = async () => {
    const amount = Number(quoteAmount);
    if (!amount || amount <= 0) {
      toast.error('Please enter a valid quote amount');
      return;
    }
    setActionLoading(true);
    try {
      await sldManagerApi.proposeQuote(orderId, {
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
      await sldManagerApi.confirmPayment(orderId);
      toast.success('Payment confirmed');
      fetchData();
    } catch { toast.error('Failed to confirm payment'); }
    finally { setActionLoading(false); }
  };

  const handleMarkComplete = async () => {
    setShowCompleteConfirm(false);
    setActionLoading(true);
    try {
      await sldManagerApi.markComplete(orderId);
      toast.success('Order marked as complete');
      fetchData();
    } catch { toast.error('Failed to complete order'); }
    finally { setActionLoading(false); }
  };

  const handleSldUpload = async (file: File, managerNote?: string) => {
    const uploadedFile = await sldManagerApi.uploadFile(orderId, file, 'DRAWING_SLD');
    await sldManagerApi.uploadSldComplete(orderId, uploadedFile.fileSeq, managerNote);
    toast.success('SLD uploaded and marked as complete');
    fetchData();
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
        <LoadingSpinner size="lg" label="Loading SLD order..." />
      </div>
    );
  }

  if (!order) return null;

  const showSldSection = ['PAID', 'IN_PROGRESS', 'REVISION_REQUESTED'].includes(order.status);

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/sld-manager/orders')}
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
              SLD Order #{order.sldOrderSeq}
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">Manager view</p>
          </div>
        </div>
        <SldStatusBadge status={order.status} />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Main content */}
        <div className="lg:col-span-2 space-y-6">
          {/* Applicant & Order Info */}
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Applicant & Order Info</h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <InfoField label="신청자" value={order.userName} />
              <InfoField label="이메일" value={order.userEmail} />
              <InfoField label="주소" value={order.address || '-'} />
              <InfoField label="우편번호" value={order.postalCode || '-'} />
              <InfoField label="건물 유형" value={order.buildingType || '-'} />
              <InfoField label="용량 (kVA)" value={order.selectedKva ? `${order.selectedKva} kVA` : '-'} />
            </div>
            {order.applicantNote && (
              <div className="mt-4 pt-4 border-t border-gray-100">
                <InfoField label="요구사항 메모" value={order.applicantNote} />
              </div>
            )}
            {order.sketchFileSeq && (
              <div className="mt-4 pt-4 border-t border-gray-100">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleDownloadFile(order.sketchFileSeq!, 'Applicant_Sketch')}
                >
                  Download Applicant Sketch
                </Button>
              </div>
            )}
          </Card>

          {/* Status-specific action sections */}

          {/* PENDING_QUOTE: Quote proposal form */}
          {order.status === 'PENDING_QUOTE' && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">견적 제안</h2>
              <div className="space-y-4">
                <Input
                  label="견적 금액 (SGD)"
                  type="number"
                  placeholder="e.g., 500"
                  value={quoteAmount}
                  onChange={(e) => setQuoteAmount(e.target.value)}
                  min={0}
                  required
                />
                <Textarea
                  label="견적 메모 (Optional)"
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

          {/* QUOTE_PROPOSED: Waiting for applicant response */}
          {order.status === 'QUOTE_PROPOSED' && (
            <Card>
              <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#9202;</span>
                  <div>
                    <p className="text-sm font-medium text-yellow-800">견적 제안 완료. 신청자 응답 대기 중.</p>
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

          {/* QUOTE_REJECTED: Rejected */}
          {order.status === 'QUOTE_REJECTED' && (
            <Card>
              <div className="bg-red-50 border border-red-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#10060;</span>
                  <div>
                    <p className="text-sm font-medium text-red-800">견적이 거절되었습니다.</p>
                    <p className="text-xs text-red-700 mt-1">
                      The applicant has rejected the proposed quote.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {/* PENDING_PAYMENT: Confirm payment */}
          {order.status === 'PENDING_PAYMENT' && (
            <Card>
              <div className="bg-orange-50 border border-orange-200 rounded-lg p-4 mb-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128179;</span>
                  <div>
                    <p className="text-sm font-medium text-orange-800">결제 대기 중</p>
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
                결제 확인 (Confirm Payment)
              </Button>
            </Card>
          )}

          {/* PAID: Payment confirmed, show SLD section */}
          {order.status === 'PAID' && (
            <Card>
              <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#9989;</span>
                  <div>
                    <p className="text-sm font-medium text-green-800">결제 완료. 작업을 시작하세요.</p>
                    <p className="text-xs text-green-700 mt-1">
                      Payment has been confirmed. You can now prepare and upload the SLD drawing.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {/* IN_PROGRESS: SLD section active */}
          {order.status === 'IN_PROGRESS' && (
            <Card>
              <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128736;</span>
                  <div>
                    <p className="text-sm font-medium text-blue-800">SLD 작업 중</p>
                    <p className="text-xs text-blue-700 mt-1">
                      Use the SLD section below to upload the drawing.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {/* REVISION_REQUESTED: Show revision comment + SLD section for re-upload */}
          {order.status === 'REVISION_REQUESTED' && (
            <Card>
              <div className="bg-orange-50 border border-orange-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128221;</span>
                  <div>
                    <p className="text-sm font-medium text-orange-800">수정 요청</p>
                    <p className="text-xs text-orange-700 mt-1">
                      The applicant has requested a revision.
                    </p>
                    {order.revisionComment && (
                      <div className="mt-2 bg-white rounded p-2 border border-orange-100">
                        <p className="text-xs text-gray-500">Revision comment:</p>
                        <p className="text-sm text-gray-700 whitespace-pre-wrap">{order.revisionComment}</p>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </Card>
          )}

          {/* SLD Section (Manual Upload / AI Generate) */}
          {showSldSection && (
            <SldManagerSldSection
              sldOrderSeq={orderId}
              onSldUpload={handleSldUpload}
              onSldUpdated={fetchData}
            />
          )}

          {/* SLD_UPLOADED: Waiting for applicant confirmation */}
          {order.status === 'SLD_UPLOADED' && (
            <Card>
              <div className="bg-purple-50 border border-purple-200 rounded-lg p-4 mb-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128196;</span>
                  <div className="flex-1">
                    <p className="text-sm font-medium text-purple-800">SLD 업로드 완료. 신청자 확인 대기 중.</p>
                    <p className="text-xs text-purple-700 mt-1">
                      The SLD drawing has been uploaded. Waiting for applicant to review.
                    </p>
                    {order.managerNote && (
                      <div className="mt-2 bg-white rounded p-2 border border-purple-100">
                        <p className="text-xs text-gray-500">Manager note:</p>
                        <p className="text-sm text-gray-700 whitespace-pre-wrap">{order.managerNote}</p>
                      </div>
                    )}
                    {order.uploadedFileSeq && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="mt-2"
                        onClick={() => handleDownloadFile(order.uploadedFileSeq!, 'SLD_Drawing')}
                      >
                        Download SLD
                      </Button>
                    )}
                  </div>
                </div>
              </div>
              <Button
                variant="primary"
                onClick={() => setShowCompleteConfirm(true)}
                loading={actionLoading}
              >
                완료 처리 (Mark Complete)
              </Button>
            </Card>
          )}

          {/* COMPLETED */}
          {order.status === 'COMPLETED' && (
            <Card>
              <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#9989;</span>
                  <div className="flex-1">
                    <p className="text-sm font-medium text-green-800">완료</p>
                    <p className="text-xs text-green-700 mt-1">
                      This SLD order has been completed successfully.
                    </p>
                    {order.uploadedFileSeq && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="mt-2"
                        onClick={() => handleDownloadFile(order.uploadedFileSeq!, 'SLD_Drawing')}
                      >
                        Download SLD
                      </Button>
                    )}
                  </div>
                </div>
              </div>
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
                <span className="font-medium text-gray-700">#{order.sldOrderSeq}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">상태</span>
                <SldStatusBadge status={order.status} />
              </div>
              {order.quoteAmount != null && (
                <div className="flex justify-between">
                  <span className="text-gray-500">견적금액</span>
                  <span className="font-medium text-gray-700">SGD ${order.quoteAmount.toLocaleString()}</span>
                </div>
              )}
              <div className="flex justify-between">
                <span className="text-gray-500">요청일</span>
                <span className="font-medium text-gray-700">
                  {new Date(order.createdAt).toLocaleDateString()}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">최종 수정</span>
                <span className="font-medium text-gray-700">
                  {new Date(order.updatedAt).toLocaleDateString()}
                </span>
              </div>
            </div>
          </Card>

          {/* Applicant Info */}
          <Card>
            <h3 className="text-sm font-semibold text-gray-800 mb-3">Applicant</h3>
            <div className="flex items-center gap-3 p-3 bg-gray-50 rounded-lg border border-gray-200">
              <div className="w-8 h-8 rounded-full bg-gray-200 flex items-center justify-center">
                <span className="text-sm">&#128100;</span>
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-gray-800">{order.userName}</p>
                <p className="text-xs text-gray-500 truncate">{order.userEmail}</p>
              </div>
            </div>
          </Card>

          {/* Assigned Manager */}
          {order.assignedManagerName && (
            <Card>
              <h3 className="text-sm font-semibold text-gray-800 mb-3">담당 매니저</h3>
              <div className="flex items-center gap-3 p-3 bg-primary-50 rounded-lg border border-primary-100">
                <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
                  <span className="text-sm">&#128100;</span>
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-800">{order.assignedManagerName}</p>
                </div>
              </div>
            </Card>
          )}
        </div>
      </div>

      {/* Confirm dialogs */}
      <ConfirmDialog
        isOpen={showPaymentConfirm}
        onClose={() => setShowPaymentConfirm(false)}
        onConfirm={handleConfirmPayment}
        title="Confirm Payment"
        message={`Confirm payment of SGD $${order.quoteAmount?.toLocaleString()} has been received?`}
        confirmLabel="Confirm Payment"
        loading={actionLoading}
      />

      <ConfirmDialog
        isOpen={showCompleteConfirm}
        onClose={() => setShowCompleteConfirm(false)}
        onConfirm={handleMarkComplete}
        title="Mark as Complete"
        message="Mark this SLD order as completed? The applicant will be notified."
        confirmLabel="Complete"
        loading={actionLoading}
      />
    </div>
  );
}
