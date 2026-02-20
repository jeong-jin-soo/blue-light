import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Badge } from '../../components/ui/Badge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import adminApi from '../../api/adminApi';
import fileApi from '../../api/fileApi';
import loaApi from '../../api/loaApi';
import { STATUS_STEPS, getStatusStep } from '../../utils/applicationUtils';
import { getBasePath } from '../../utils/routeUtils';

// Section components
import { AdminApplicationInfo } from './sections/AdminApplicationInfo';
import { AdminLoaSection } from './sections/AdminLoaSection';
import { AdminSldSection } from './sections/AdminSldSection';
import { AdminDocumentsSection } from './sections/AdminDocumentsSection';
import { AdminPaymentSection } from './sections/AdminPaymentSection';
import { AdminSidebar } from './sections/AdminSidebar';

// Modal components
import {
  PaymentModal, CompleteModal, RevisionModal, AssignLewModal,
  ApproveConfirmDialog, ProcessingConfirmDialog, UnassignLewConfirmDialog, SldConfirmDialog,
} from './sections/AdminModals';

import type { AdminApplication, FileInfo, FileType, Payment, LewSummary, SldRequest, LoaStatus } from '../../types';

export default function AdminApplicationDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToastStore();

  const [application, setApplication] = useState<AdminApplication | null>(null);
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  // Modal states
  const [showPaymentModal, setShowPaymentModal] = useState(false);
  const [showCompleteModal, setShowCompleteModal] = useState(false);
  const [showProcessingConfirm, setShowProcessingConfirm] = useState(false);
  const [showRevisionModal, setShowRevisionModal] = useState(false);
  const [showApproveConfirm, setShowApproveConfirm] = useState(false);
  const [revisionComment, setRevisionComment] = useState('');
  const [paymentForm, setPaymentForm] = useState({ transactionId: '', paymentMethod: 'PayNow', receiptFile: null as File | null });
  const [completeForm, setCompleteForm] = useState({ licenseNumber: '', licenseExpiryDate: '' });
  const [uploadFileType, setUploadFileType] = useState<FileType>('LICENSE_PDF');

  // LOA states
  const [loaStatus, setLoaStatus] = useState<LoaStatus | null>(null);
  const [loaGenerating, setLoaGenerating] = useState(false);
  const [loaUploading, setLoaUploading] = useState(false);

  // SLD states
  const [sldRequest, setSldRequest] = useState<SldRequest | null>(null);
  const [sldLewNote, setSldLewNote] = useState('');
  const [showSldConfirm, setShowSldConfirm] = useState(false);

  // LEW assignment states
  const [showAssignLewModal, setShowAssignLewModal] = useState(false);
  const [showUnassignConfirm, setShowUnassignConfirm] = useState(false);
  const [availableLews, setAvailableLews] = useState<LewSummary[]>([]);
  const [selectedLewSeq, setSelectedLewSeq] = useState<number | null>(null);
  const [lewsLoading, setLewsLoading] = useState(false);

  const { user: currentUser } = useAuthStore();
  const isAdmin = currentUser?.role === 'ADMIN' || currentUser?.role === 'SYSTEM_ADMIN';
  const basePath = getBasePath(currentUser?.role);
  const applicationId = Number(id);

  // ── Data Fetching ──────────────────────────────────

  const fetchData = useCallback(async () => {
    try {
      const [appData, filesData, paymentsData] = await Promise.all([
        adminApi.getApplication(applicationId),
        fileApi.getFilesByApplication(applicationId),
        adminApi.getPayments(applicationId),
      ]);
      setApplication(appData);
      setFiles(filesData);
      setPayments(paymentsData);

      // LOA status
      try {
        const loaData = await loaApi.getLoaStatus(applicationId);
        setLoaStatus(loaData);
      } catch { /* LOA status might not be available */ }

      if (appData.sldOption === 'REQUEST_LEW') {
        try {
          const sldData = await adminApi.getAdminSldRequest(applicationId);
          setSldRequest(sldData);
        } catch { /* SLD request might not exist */ }
      }
    } catch {
      toast.error('Failed to load application details');
      navigate(`${basePath}/applications`);
    } finally {
      setLoading(false);
    }
  }, [applicationId]);

  useEffect(() => { fetchData(); }, [fetchData]);

  // ── Action Handlers ──────────────────────────────────

  const handleRequestRevision = async () => {
    if (!revisionComment.trim()) { toast.error('Please enter a review comment'); return; }
    setActionLoading(true);
    try {
      await adminApi.requestRevision(applicationId, { comment: revisionComment });
      toast.success('Revision requested successfully');
      setShowRevisionModal(false);
      setRevisionComment('');
      fetchData();
    } catch { toast.error('Failed to request revision'); }
    finally { setActionLoading(false); }
  };

  const handleApproveForPayment = async () => {
    setShowApproveConfirm(false);
    setActionLoading(true);
    try {
      await adminApi.approveForPayment(applicationId);
      toast.success('Application approved. Payment requested from applicant.');
      fetchData();
    } catch { toast.error('Failed to approve application'); }
    finally { setActionLoading(false); }
  };

  const handleConfirmPayment = async () => {
    setActionLoading(true);
    try {
      await adminApi.confirmPayment(applicationId, {
        transactionId: paymentForm.transactionId || undefined,
        paymentMethod: paymentForm.paymentMethod || undefined,
      });

      // 영수증 파일이 첨부된 경우 업로드
      if (paymentForm.receiptFile) {
        try {
          await adminApi.uploadFile(applicationId, paymentForm.receiptFile, 'PAYMENT_RECEIPT');
        } catch {
          toast.error('Payment confirmed but failed to upload receipt');
        }
      }

      toast.success('Payment confirmed successfully');
      setShowPaymentModal(false);
      setPaymentForm({ transactionId: '', paymentMethod: 'PayNow', receiptFile: null });
      fetchData();
    } catch { toast.error('Failed to confirm payment'); }
    finally { setActionLoading(false); }
  };

  const handleStartProcessing = async () => {
    setShowProcessingConfirm(false);
    setActionLoading(true);
    try {
      await adminApi.updateStatus(applicationId, { status: 'IN_PROGRESS' });
      toast.success('Application status updated to In Progress');
      fetchData();
    } catch { toast.error('Failed to update status'); }
    finally { setActionLoading(false); }
  };

  const handleComplete = async () => {
    if (!completeForm.licenseNumber.trim() || !completeForm.licenseExpiryDate) {
      toast.error('Please fill in all fields'); return;
    }
    setActionLoading(true);
    try {
      await adminApi.completeApplication(applicationId, completeForm);
      toast.success('Application completed! Licence issued.');
      setShowCompleteModal(false);
      fetchData();
    } catch { toast.error('Failed to complete application'); }
    finally { setActionLoading(false); }
  };

  const handleFileUpload = async (file: File) => {
    await adminApi.uploadFile(applicationId, file, uploadFileType);
    toast.success('File uploaded successfully');
    const updatedFiles = await fileApi.getFilesByApplication(applicationId);
    setFiles(updatedFiles);
  };

  const handleFileDownload = async (fileInfo: FileInfo) => {
    try { await fileApi.downloadFile(fileInfo.fileSeq, fileInfo.originalFilename || 'download'); }
    catch { toast.error('Failed to download file'); }
  };

  // LEW Assignment
  const openAssignLewModal = async () => {
    setLewsLoading(true);
    setShowAssignLewModal(true);
    try {
      const lews = await adminApi.getAvailableLews(application?.selectedKva);
      setAvailableLews(lews);
      if (application?.assignedLewSeq) setSelectedLewSeq(application.assignedLewSeq);
      else if (lews.length === 1) setSelectedLewSeq(lews[0].userSeq);
      else setSelectedLewSeq(null);
    } catch { toast.error('Failed to load LEW list'); setShowAssignLewModal(false); }
    finally { setLewsLoading(false); }
  };

  const handleAssignLew = async () => {
    if (!selectedLewSeq) { toast.error('Please select a LEW'); return; }
    setActionLoading(true);
    try {
      await adminApi.assignLew(applicationId, { lewUserSeq: selectedLewSeq });
      toast.success('LEW assigned successfully');
      setShowAssignLewModal(false);
      fetchData();
    } catch { toast.error('Failed to assign LEW'); }
    finally { setActionLoading(false); }
  };

  const handleUnassignLew = async () => {
    setShowUnassignConfirm(false);
    setActionLoading(true);
    try {
      await adminApi.unassignLew(applicationId);
      toast.success('LEW unassigned successfully');
      fetchData();
    } catch { toast.error('Failed to unassign LEW'); }
    finally { setActionLoading(false); }
  };

  // LOA
  const handleGenerateLoa = async () => {
    setLoaGenerating(true);
    try {
      await loaApi.generateLoa(applicationId);
      toast.success('LOA generated successfully');
      const loaData = await loaApi.getLoaStatus(applicationId);
      setLoaStatus(loaData);
    } catch {
      toast.error('Failed to generate LOA');
    } finally {
      setLoaGenerating(false);
    }
  };

  const handleLoaDownload = async (fileSeq: number, filename: string) => {
    try { await fileApi.downloadFile(fileSeq, filename); }
    catch { toast.error('Failed to download LOA'); }
  };

  const handleUploadLoa = async (file: File) => {
    setLoaUploading(true);
    try {
      await adminApi.uploadFile(applicationId, file, 'OWNER_AUTH_LETTER');
      toast.success('LOA uploaded successfully');
      const loaData = await loaApi.getLoaStatus(applicationId);
      setLoaStatus(loaData);
    } catch {
      toast.error('Failed to upload LOA');
    } finally {
      setLoaUploading(false);
    }
  };

  // SLD
  const handleSldUpload = async (file: File) => {
    const uploadedFile = await adminApi.uploadFile(applicationId, file, 'DRAWING_SLD');
    await adminApi.uploadSldComplete(applicationId, uploadedFile.fileSeq, sldLewNote || undefined);
    toast.success('SLD uploaded and marked as complete');
    setSldLewNote('');
    fetchData();
  };

  const handleSldConfirm = async () => {
    setShowSldConfirm(false);
    setActionLoading(true);
    try {
      await adminApi.confirmSld(applicationId);
      toast.success('SLD confirmed');
      fetchData();
    } catch { toast.error('Failed to confirm SLD'); }
    finally { setActionLoading(false); }
  };

  // ── Render ──────────────────────────────────

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading application..." />
      </div>
    );
  }

  if (!application) return null;

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate(`${basePath}/applications`)}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
            aria-label="Back to applications list"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Back</span>
          </button>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-xl sm:text-2xl font-bold text-gray-800">
                Application #{application.applicationSeq}
              </h1>
              <Badge variant={application.applicationType === 'RENEWAL' ? 'warning' : 'info'}>
                {application.applicationType === 'RENEWAL' ? 'Renewal' : 'New'}
              </Badge>
            </div>
            <p className="text-sm text-gray-500 mt-0.5">Admin view &mdash; manage status and payments</p>
          </div>
        </div>
        <StatusBadge status={application.status} />
      </div>

      {/* Review Comment */}
      {application.reviewComment && (
        <Card>
          <div className="flex items-start gap-3">
            <span className="text-lg">📝</span>
            <div className="flex-1">
              <p className="text-sm font-medium text-gray-800">Review Comment</p>
              <p className="text-sm text-gray-600 mt-1 whitespace-pre-wrap">{application.reviewComment}</p>
            </div>
          </div>
        </Card>
      )}

      {/* Revision Banner */}
      {application.status === 'REVISION_REQUESTED' && (
        <div className="bg-warning-50 border border-warning-200 rounded-lg p-4">
          <div className="flex items-start gap-3">
            <span className="text-lg">⏳</span>
            <div>
              <p className="text-sm font-medium text-warning-800">Awaiting Applicant Revision</p>
              <p className="text-xs text-warning-700 mt-1">The applicant has been notified to revise and resubmit their application.</p>
            </div>
          </div>
        </div>
      )}

      {/* Mobile Progress */}
      <div className="lg:hidden">
        <Card>
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-gray-800">Progress</h3>
            <StatusBadge status={application.status} />
          </div>
          {application.status !== 'EXPIRED' && (
            <div className="mt-3">
              <StepTracker steps={STATUS_STEPS} currentStep={getStatusStep(application.status)} variant="horizontal" />
            </div>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Main content */}
        <div className="lg:col-span-2 space-y-6">
          <AdminApplicationInfo
            application={application}
            onNavigateToOriginal={(seq) => navigate(`${basePath}/applications/${seq}`)}
          />

          <AdminLoaSection
            application={application}
            loaStatus={loaStatus}
            onGenerate={handleGenerateLoa}
            onUploadLoa={handleUploadLoa}
            onDownload={handleLoaDownload}
            generating={loaGenerating}
            uploading={loaUploading}
          />

          {application.sldOption === 'REQUEST_LEW' && sldRequest && (
            <AdminSldSection
              applicationSeq={applicationId}
              sldRequest={sldRequest}
              sldLewNote={sldLewNote}
              onSldLewNoteChange={setSldLewNote}
              onSldUpload={handleSldUpload}
              onSldConfirmClick={() => setShowSldConfirm(true)}
              onSldUpdated={fetchData}
              actionLoading={actionLoading}
            />
          )}

          <AdminDocumentsSection
            files={files}
            status={application.status}
            uploadFileType={uploadFileType}
            onUploadFileTypeChange={setUploadFileType}
            onFileUpload={handleFileUpload}
            onFileDownload={handleFileDownload}
          />

          <AdminPaymentSection payments={payments} files={files} />
        </div>

        {/* Sidebar */}
        <AdminSidebar
          application={application}
          files={files}
          payments={payments}
          isAdmin={isAdmin}
          actionLoading={actionLoading}
          onRevisionClick={() => setShowRevisionModal(true)}
          onApproveClick={() => setShowApproveConfirm(true)}
          onPaymentClick={() => setShowPaymentModal(true)}
          onProcessingClick={() => setShowProcessingConfirm(true)}
          onCompleteClick={() => setShowCompleteModal(true)}
          onAssignLewClick={openAssignLewModal}
          onUnassignLewClick={() => setShowUnassignConfirm(true)}
        />
      </div>

      {/* Modals */}
      <PaymentModal
        isOpen={showPaymentModal} onClose={() => setShowPaymentModal(false)}
        onConfirm={handleConfirmPayment} quoteAmount={application.quoteAmount}
        paymentForm={paymentForm} setPaymentForm={setPaymentForm} loading={actionLoading}
      />
      <CompleteModal
        isOpen={showCompleteModal} onClose={() => setShowCompleteModal(false)}
        onConfirm={handleComplete} completeForm={completeForm}
        setCompleteForm={setCompleteForm} loading={actionLoading}
      />
      <RevisionModal
        isOpen={showRevisionModal} onClose={() => setShowRevisionModal(false)}
        onConfirm={handleRequestRevision} revisionComment={revisionComment}
        setRevisionComment={setRevisionComment} loading={actionLoading}
      />
      <ApproveConfirmDialog
        isOpen={showApproveConfirm} onClose={() => setShowApproveConfirm(false)}
        onConfirm={handleApproveForPayment} loading={actionLoading}
      />
      <ProcessingConfirmDialog
        isOpen={showProcessingConfirm} onClose={() => setShowProcessingConfirm(false)}
        onConfirm={handleStartProcessing}
      />
      <AssignLewModal
        isOpen={showAssignLewModal} onClose={() => setShowAssignLewModal(false)}
        onConfirm={handleAssignLew} lewsLoading={lewsLoading}
        availableLews={availableLews} selectedLewSeq={selectedLewSeq}
        setSelectedLewSeq={setSelectedLewSeq} applicationKva={application?.selectedKva}
        loading={actionLoading}
      />
      <UnassignLewConfirmDialog
        isOpen={showUnassignConfirm} onClose={() => setShowUnassignConfirm(false)}
        onConfirm={handleUnassignLew} loading={actionLoading}
      />
      <SldConfirmDialog
        isOpen={showSldConfirm} onClose={() => setShowSldConfirm(false)}
        onConfirm={handleSldConfirm} loading={actionLoading}
      />
    </div>
  );
}
