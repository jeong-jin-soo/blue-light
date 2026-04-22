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
import { ManagerOrderStatusBadge } from '../../components/domain/ManagerOrderStatusBadge';
import { useToastStore } from '../../stores/toastStore';
import { powerSocketManagerApi } from '../../api/powerSocketManagerApi';
import fileApi from '../../api/fileApi';
import { PowerSocketManagerSection } from './sections/PowerSocketManagerSection';
import type { PowerSocketOrder } from '../../types';

export default function PowerSocketManagerOrderDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToastStore();

  const [order, setOrder] = useState<PowerSocketOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  const [quoteAmount, setQuoteAmount] = useState('');
  const [quoteNote, setQuoteNote] = useState('');

  const [showPaymentConfirm, setShowPaymentConfirm] = useState(false);

  const orderId = Number(id);

  const fetchData = useCallback(async () => {
    try {
      const data = await powerSocketManagerApi.getOrder(orderId);
      setOrder(data);
    } catch {
      toast.error('Failed to load Power Socket order details');
      navigate('/power-socket-manager/orders');
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
      await powerSocketManagerApi.proposeQuote(orderId, {
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
      await powerSocketManagerApi.confirmPayment(orderId);
      toast.success('Payment confirmed');
      fetchData();
    } catch { toast.error('Failed to confirm payment'); }
    finally { setActionLoading(false); }
  };

  const handleDeliverableUpload = async (file: File, managerNote?: string) => {
    const uploadedFile = await powerSocketManagerApi.uploadFile(orderId, file, 'DRAWING_SLD');
    await powerSocketManagerApi.uploadDeliverableComplete(orderId, uploadedFile.fileSeq, managerNote);
    toast.success('Power Socket Layout uploaded and marked as complete');
    fetchData();
  };

  const handleDownloadFile = async (fileSeq: number, filename: string) => {
    try {
      await fileApi.downloadFile(fileSeq, filename);
    } catch {
      toast.error('Failed to download file');
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading Power Socket order..." />
      </div>
    );
  }

  if (!order) return null;

  const showDeliverableSection = ['PAID', 'IN_PROGRESS', 'REVISION_REQUESTED', 'SLD_UPLOADED'].includes(order.status);

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/power-socket-manager/orders')}
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
              Power Socket Order #{order.powerSocketOrderSeq}
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">Manager view</p>
          </div>
        </div>
        <ManagerOrderStatusBadge status={order.status} />
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
                    <p className="text-sm font-medium text-green-800">Payment confirmed. Begin work on the Power Socket Layout.</p>
                    <p className="text-xs text-green-700 mt-1">
                      Payment has been confirmed. You can now prepare and upload the Power Socket Layout.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {order.status === 'IN_PROGRESS' && (
            <Card>
              <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128736;</span>
                  <div>
                    <p className="text-sm font-medium text-blue-800">Power Socket Work In Progress</p>
                    <p className="text-xs text-blue-700 mt-1">
                      Use the section below to upload the Power Socket Layout.
                    </p>
                  </div>
                </div>
              </div>
            </Card>
          )}

          {order.status === 'REVISION_REQUESTED' && (
            <Card>
              <div className="bg-orange-50 border border-orange-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128221;</span>
                  <div>
                    <p className="text-sm font-medium text-orange-800">Revision Requested</p>
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

          {showDeliverableSection && (
            <PowerSocketManagerSection onDeliverableUpload={handleDeliverableUpload} />
          )}

          {order.status === 'SLD_UPLOADED' && (
            <Card>
              <div className="bg-purple-50 border border-purple-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">&#128196;</span>
                  <div className="flex-1">
                    <p className="text-sm font-medium text-purple-800">Power Socket Layout uploaded. Waiting for applicant review.</p>
                    <p className="text-xs text-purple-700 mt-1">
                      The deliverable has been uploaded. The applicant will review and confirm completion.
                      You can re-upload a new version using the section above if needed.
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
                        onClick={() => handleDownloadFile(order.uploadedFileSeq!, 'Power_Socket_Layout')}
                      >
                        Download Power Socket Layout
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
                      This Power Socket order has been completed successfully.
                    </p>
                    {order.uploadedFileSeq && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="mt-2"
                        onClick={() => handleDownloadFile(order.uploadedFileSeq!, 'Power_Socket_Layout')}
                      >
                        Download Power Socket Layout
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
                <span className="font-medium text-gray-700">#{order.powerSocketOrderSeq}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Status</span>
                <ManagerOrderStatusBadge status={order.status} />
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
