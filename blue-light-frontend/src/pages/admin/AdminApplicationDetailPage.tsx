import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import { FileUpload } from '../../components/domain/FileUpload';
import { Textarea } from '../../components/ui/Textarea';
import { Select } from '../../components/ui/Select';
import { useToastStore } from '../../stores/toastStore';
import adminApi from '../../api/adminApi';
import fileApi from '../../api/fileApi';
import { InfoField } from '../../components/common/InfoField';
import { STATUS_STEPS, getStatusStep, formatFileSize, formatFileType, getFileTypeBadge } from '../../utils/applicationUtils';
import { useAuthStore } from '../../stores/authStore';
import {
  PaymentModal, CompleteModal, RevisionModal, AssignLewModal,
  ApproveConfirmDialog, ProcessingConfirmDialog, UnassignLewConfirmDialog, SldConfirmDialog,
} from './sections/AdminModals';
import type { AdminApplication, FileInfo, FileType, Payment, LewSummary, SldRequest } from '../../types';

const FILE_TYPE_OPTIONS = [
  { value: 'LICENSE_PDF', label: 'Licence PDF' },
  { value: 'REPORT_PDF', label: 'Report PDF' },
  { value: 'OWNER_AUTH_LETTER', label: "Owner's Auth Letter" },
];

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
  const [paymentForm, setPaymentForm] = useState({ transactionId: '', paymentMethod: 'PayNow' });
  const [completeForm, setCompleteForm] = useState({ licenseNumber: '', licenseExpiryDate: '' });
  const [uploadFileType, setUploadFileType] = useState<FileType>('LICENSE_PDF');

  // SLD request states
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
  const isAdmin = currentUser?.role === 'ADMIN';

  const applicationId = Number(id);

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

      // Fetch SLD request if sldOption is REQUEST_LEW
      if (appData.sldOption === 'REQUEST_LEW') {
        try {
          const sldData = await adminApi.getAdminSldRequest(applicationId);
          setSldRequest(sldData);
        } catch {
          // SLD request might not exist
        }
      }
    } catch {
      toast.error('Failed to load application details');
      navigate('/admin/applications');
    } finally {
      setLoading(false);
    }
  }, [applicationId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // ── Admin Actions ──────────────────────────────────

  const handleRequestRevision = async () => {
    if (!revisionComment.trim()) {
      toast.error('Please enter a review comment');
      return;
    }
    setActionLoading(true);
    try {
      await adminApi.requestRevision(applicationId, { comment: revisionComment });
      toast.success('Revision requested successfully');
      setShowRevisionModal(false);
      setRevisionComment('');
      fetchData();
    } catch {
      toast.error('Failed to request revision');
    } finally {
      setActionLoading(false);
    }
  };

  const handleApproveForPayment = async () => {
    setShowApproveConfirm(false);
    setActionLoading(true);
    try {
      await adminApi.approveForPayment(applicationId);
      toast.success('Application approved. Payment requested from applicant.');
      fetchData();
    } catch {
      toast.error('Failed to approve application');
    } finally {
      setActionLoading(false);
    }
  };

  const handleConfirmPayment = async () => {
    setActionLoading(true);
    try {
      await adminApi.confirmPayment(applicationId, {
        transactionId: paymentForm.transactionId || undefined,
        paymentMethod: paymentForm.paymentMethod || undefined,
      });
      toast.success('Payment confirmed successfully');
      setShowPaymentModal(false);
      fetchData();
    } catch {
      toast.error('Failed to confirm payment');
    } finally {
      setActionLoading(false);
    }
  };

  const handleStartProcessing = async () => {
    setShowProcessingConfirm(false);
    setActionLoading(true);
    try {
      await adminApi.updateStatus(applicationId, { status: 'IN_PROGRESS' });
      toast.success('Application status updated to In Progress');
      fetchData();
    } catch {
      toast.error('Failed to update status');
    } finally {
      setActionLoading(false);
    }
  };

  const handleComplete = async () => {
    if (!completeForm.licenseNumber.trim() || !completeForm.licenseExpiryDate) {
      toast.error('Please fill in all fields');
      return;
    }
    setActionLoading(true);
    try {
      await adminApi.completeApplication(applicationId, completeForm);
      toast.success('Application completed! Licence issued.');
      setShowCompleteModal(false);
      fetchData();
    } catch {
      toast.error('Failed to complete application');
    } finally {
      setActionLoading(false);
    }
  };

  const handleFileUpload = async (file: File) => {
    await adminApi.uploadFile(applicationId, file, uploadFileType);
    toast.success('File uploaded successfully');
    const updatedFiles = await fileApi.getFilesByApplication(applicationId);
    setFiles(updatedFiles);
  };

  // ── LEW Assignment Handlers ──────────────────────
  const openAssignLewModal = async () => {
    setLewsLoading(true);
    setShowAssignLewModal(true);
    try {
      const lews = await adminApi.getAvailableLews(application?.selectedKva);
      setAvailableLews(lews);
      // 현재 할당된 LEW가 있으면 미리 선택
      if (application?.assignedLewSeq) {
        setSelectedLewSeq(application.assignedLewSeq);
      } else if (lews.length === 1) {
        setSelectedLewSeq(lews[0].userSeq);
      } else {
        setSelectedLewSeq(null);
      }
    } catch {
      toast.error('Failed to load LEW list');
      setShowAssignLewModal(false);
    } finally {
      setLewsLoading(false);
    }
  };

  const handleAssignLew = async () => {
    if (!selectedLewSeq) {
      toast.error('Please select a LEW');
      return;
    }
    setActionLoading(true);
    try {
      await adminApi.assignLew(applicationId, { lewUserSeq: selectedLewSeq });
      toast.success('LEW assigned successfully');
      setShowAssignLewModal(false);
      fetchData();
    } catch {
      toast.error('Failed to assign LEW');
    } finally {
      setActionLoading(false);
    }
  };

  const handleUnassignLew = async () => {
    setShowUnassignConfirm(false);
    setActionLoading(true);
    try {
      await adminApi.unassignLew(applicationId);
      toast.success('LEW unassigned successfully');
      fetchData();
    } catch {
      toast.error('Failed to unassign LEW');
    } finally {
      setActionLoading(false);
    }
  };

  // ── SLD Request Handlers ──────────────────────
  const handleSldUpload = async (file: File) => {
    // Upload file first (as DRAWING_SLD type)
    const uploadedFile = await adminApi.uploadFile(applicationId, file, 'DRAWING_SLD');
    // Mark SLD as uploaded
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
    } catch {
      toast.error('Failed to confirm SLD');
    } finally {
      setActionLoading(false);
    }
  };

  const handleFileDownload = async (fileInfo: FileInfo) => {
    try {
      await fileApi.downloadFile(fileInfo.fileSeq, fileInfo.originalFilename || 'download');
    } catch {
      toast.error('Failed to download file');
    }
  };

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
            onClick={() => navigate('/admin/applications')}
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
              <Badge variant={application.applicationType === 'RENEWAL' ? 'warning' : application.applicationType === 'SUPPLY_INSTALLATION' ? 'warning' : 'info'}>
                {application.applicationType === 'RENEWAL' ? 'Renewal' : application.applicationType === 'SUPPLY_INSTALLATION' ? 'Supply' : 'New'}
              </Badge>
            </div>
            <p className="text-sm text-gray-500 mt-0.5">
              Admin view &mdash; manage status and payments
            </p>
          </div>
        </div>
        <StatusBadge status={application.status} />
      </div>

      {/* Review Comment Display */}
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

      {/* REVISION_REQUESTED Info Banner */}
      {application.status === 'REVISION_REQUESTED' && (
        <div className="bg-warning-50 border border-warning-200 rounded-lg p-4">
          <div className="flex items-start gap-3">
            <span className="text-lg">⏳</span>
            <div>
              <p className="text-sm font-medium text-warning-800">Awaiting Applicant Revision</p>
              <p className="text-xs text-warning-700 mt-1">
                The applicant has been notified to revise and resubmit their application.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Mobile Progress Summary */}
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
        {/* Main content (left 2/3) */}
        <div className="lg:col-span-2 space-y-6">
          {/* Applicant Info */}
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Applicant Information</h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <InfoField label="Name" value={application.userName} />
              <InfoField label="Email" value={application.userEmail} />
              <InfoField label="Phone" value={application.userPhone || 'Not provided'} />
              <InfoField label="Designation" value={application.userDesignation || '—'} />
            </div>

            {/* Business Details (Letter of Appointment 필수 항목) */}
            <div className="mt-4 pt-4 border-t border-gray-100">
              <h3 className="text-sm font-medium text-gray-600 mb-3">
                Business Details
                <span className="text-xs text-gray-400 ml-1.5">(Required for Letter of Appointment)</span>
              </h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <InfoField label="Company Name" value={application.userCompanyName || '—'} />
                <InfoField label="UEN" value={application.userUen || '—'} />
              </div>
            </div>

            {/* Correspondence Address */}
            <div className="mt-4 pt-4 border-t border-gray-100">
              <h3 className="text-sm font-medium text-gray-600 mb-3">
                Correspondence Address
                <span className="text-xs text-gray-400 ml-1.5">(EMA notification delivery)</span>
              </h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <InfoField label="Address" value={application.userCorrespondenceAddress || '—'} />
                <InfoField label="Postal Code" value={application.userCorrespondencePostalCode || '—'} />
              </div>
            </div>

            {/* Missing info warning */}
            {(!application.userCompanyName || !application.userUen || !application.userDesignation || !application.userCorrespondenceAddress) && (
              <div className="mt-4 bg-warning-50 border border-warning-200 rounded-lg p-3">
                <div className="flex items-start gap-2">
                  <span className="text-sm">⚠️</span>
                  <div>
                    <p className="text-xs font-medium text-warning-800">Incomplete Applicant Profile</p>
                    <p className="text-xs text-warning-700 mt-0.5">
                      The following are required for Letter of Appointment:{' '}
                      {[
                        !application.userCompanyName && 'Company Name',
                        !application.userUen && 'UEN',
                        !application.userDesignation && 'Designation',
                        !application.userCorrespondenceAddress && 'Correspondence Address',
                      ].filter(Boolean).join(', ')}.
                      Please ask the applicant to update their profile.
                    </p>
                  </div>
                </div>
              </div>
            )}
          </Card>

          {/* Property Details */}
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Property Details</h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <InfoField label="Installation Address" value={application.address} />
              <InfoField label="Postal Code" value={application.postalCode} />
              <InfoField label="Building Type" value={application.buildingType || 'Not specified'} />
              <InfoField label="DB Size (kVA)" value={`${application.selectedKva} kVA`} />
              {application.spAccountNo && (
                <InfoField label="SP Account No." value={application.spAccountNo} />
              )}
            </div>
          </Card>

          {/* Licence Period (both NEW and RENEWAL) */}
          {application.renewalPeriodMonths && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Licence Period</h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <InfoField label="Duration" value={`${application.renewalPeriodMonths} months`} />
                <InfoField
                  label="EMA Fee"
                  value={application.emaFee ? `SGD $${application.emaFee.toLocaleString()} (Paid to EMA)` : '—'}
                />
              </div>
            </Card>
          )}

          {/* Renewal Details (RENEWAL only) */}
          {application.applicationType === 'RENEWAL' && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">Renewal Details</h2>
              <div className="bg-orange-50 rounded-lg p-4 border border-orange-100">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <InfoField label="Existing Licence No." value={application.existingLicenceNo || '—'} />
                  <InfoField label="Existing Expiry Date" value={application.existingExpiryDate || '—'} />
                  {application.renewalReferenceNo && (
                    <InfoField label="Renewal Reference No." value={application.renewalReferenceNo} />
                  )}
                  {application.originalApplicationSeq && (
                    <div>
                      <dt className="text-xs text-gray-500">Original Application</dt>
                      <dd className="text-sm font-medium text-primary-600 mt-0.5">
                        <button
                          onClick={() => navigate(`/admin/applications/${application.originalApplicationSeq}`)}
                          className="hover:underline"
                        >
                          #{application.originalApplicationSeq} →
                        </button>
                      </dd>
                    </div>
                  )}
                </div>
              </div>
            </Card>
          )}

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
                  <p className="text-xs text-primary-600 mt-1">
                    Based on {application.selectedKva} kVA capacity
                  </p>
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
          </Card>

          {/* SLD Request Management */}
          {application.sldOption === 'REQUEST_LEW' && sldRequest && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">SLD Drawing Request</h2>

              {sldRequest.status === 'REQUESTED' && (
                <div className="space-y-4">
                  <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                    <div className="flex items-start gap-3">
                      <span className="text-lg">🔧</span>
                      <div>
                        <p className="text-sm font-medium text-blue-800">SLD Drawing Requested by Applicant</p>
                        <p className="text-xs text-blue-700 mt-1">
                          The applicant has requested a LEW to prepare the SLD drawing.
                        </p>
                        {sldRequest.applicantNote && (
                          <div className="mt-2 bg-white rounded p-2 border border-blue-100">
                            <p className="text-xs text-gray-500">Applicant note:</p>
                            <p className="text-sm text-gray-700">{sldRequest.applicantNote}</p>
                          </div>
                        )}
                        <p className="text-xs text-blue-500 mt-2">
                          Requested on {new Date(sldRequest.createdAt).toLocaleDateString()}
                        </p>
                      </div>
                    </div>
                  </div>

                  {/* LEW SLD Upload Area */}
                  <div className="border border-gray-200 rounded-lg p-4 space-y-3">
                    <p className="text-sm font-medium text-gray-700">Upload SLD Drawing</p>
                    <Textarea
                      label="LEW Note (Optional)"
                      rows={2}
                      maxLength={2000}
                      value={sldLewNote}
                      onChange={(e) => setSldLewNote(e.target.value)}
                      placeholder="Optional note about the SLD drawing"
                      className="resize-none"
                    />
                    <FileUpload
                      onUpload={handleSldUpload}
                      files={[]}
                      label="Upload SLD Drawing"
                      hint="PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP up to 10MB"
                    />
                  </div>
                </div>
              )}

              {sldRequest.status === 'UPLOADED' && (
                <div className="space-y-4">
                  <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                    <div className="flex items-start gap-3">
                      <span className="text-lg">✅</span>
                      <div className="flex-1">
                        <p className="text-sm font-medium text-green-800">SLD Uploaded</p>
                        <p className="text-xs text-green-700 mt-1">
                          The SLD drawing has been uploaded. Click "Confirm SLD" to finalize.
                        </p>
                        {sldRequest.lewNote && (
                          <div className="mt-2 bg-white rounded p-2 border border-green-100">
                            <p className="text-xs text-gray-500">LEW note:</p>
                            <p className="text-sm text-gray-700">{sldRequest.lewNote}</p>
                          </div>
                        )}
                        {sldRequest.uploadedFileSeq && (
                          <Button
                            variant="outline"
                            size="sm"
                            className="mt-2"
                            onClick={() => {
                              if (sldRequest.uploadedFileSeq) {
                                fileApi.downloadFile(sldRequest.uploadedFileSeq, 'SLD_Drawing');
                              }
                            }}
                          >
                            Download SLD
                          </Button>
                        )}
                      </div>
                    </div>
                  </div>
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={() => setShowSldConfirm(true)}
                    loading={actionLoading}
                  >
                    Confirm SLD
                  </Button>
                </div>
              )}

              {sldRequest.status === 'CONFIRMED' && (
                <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg">📋</span>
                    <div>
                      <p className="text-sm font-medium text-gray-700">SLD Confirmed</p>
                      <p className="text-xs text-gray-500 mt-1">
                        The SLD drawing has been confirmed and is included in this application.
                      </p>
                      {sldRequest.uploadedFileSeq && (
                        <Button
                          variant="outline"
                          size="sm"
                          className="mt-2"
                          onClick={() => {
                            if (sldRequest.uploadedFileSeq) {
                              fileApi.downloadFile(sldRequest.uploadedFileSeq, 'SLD_Drawing');
                            }
                          }}
                        >
                          Download SLD
                        </Button>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </Card>
          )}

          {/* Documents */}
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Documents</h2>

            {/* Admin file upload (for licence/report PDFs) */}
            {(application.status === 'IN_PROGRESS' || application.status === 'COMPLETED') && (
              <div className="mb-4 space-y-3">
                <div className="w-48">
                  <Select
                    label="File Type"
                    value={uploadFileType}
                    onChange={(e) => setUploadFileType(e.target.value as FileType)}
                    options={FILE_TYPE_OPTIONS}
                  />
                </div>
                <FileUpload
                  onUpload={handleFileUpload}
                  files={[]}
                  label={uploadFileType === 'LICENSE_PDF' ? 'Upload Licence Document' : 'Upload Report Document'}
                  hint="PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP up to 10MB"
                />
              </div>
            )}

            {files.length === 0 ? (
              <p className="text-sm text-gray-500">No documents uploaded.</p>
            ) : (
              <div className="space-y-2">
                {files.map((f) => (
                  <div
                    key={f.fileSeq}
                    className="flex items-center justify-between px-3 py-2 bg-surface-secondary rounded-lg"
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-lg">📄</span>
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-gray-700 truncate">
                          {f.originalFilename || `File #${f.fileSeq}`}
                        </p>
                        <div className="flex items-center gap-2 text-xs text-gray-400">
                          <Badge
                            variant={getFileTypeBadge(f.fileType)}
                            className="text-[10px]"
                          >
                            {formatFileType(f.fileType)}
                          </Badge>
                          {f.fileSize != null && f.fileSize > 0 && (
                            <span>{formatFileSize(f.fileSize)}</span>
                          )}
                          <span>{new Date(f.uploadedAt).toLocaleDateString()}</span>
                        </div>
                      </div>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleFileDownload(f)}
                    >
                      Download
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </Card>

          {/* Payment History */}
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Payment History</h2>
            {payments.length === 0 ? (
              <p className="text-sm text-gray-500">No payments recorded.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-gray-200">
                      <th className="text-left py-2 px-3 font-medium text-gray-500">Date</th>
                      <th className="text-left py-2 px-3 font-medium text-gray-500">Method</th>
                      <th className="text-left py-2 px-3 font-medium text-gray-500">
                        Transaction ID
                      </th>
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
            )}
          </Card>
        </div>

        {/* Sidebar (right 1/3) */}
        <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
          {/* Status Tracker (desktop only — mobile version shown above grid) */}
          <div className="hidden lg:block">
            <Card>
              <h3 className="text-sm font-semibold text-gray-800 mb-4">Progress</h3>
              {application.status === 'EXPIRED' ? (
                <div className="text-center py-4">
                  <span className="text-3xl">⏰</span>
                  <p className="text-sm font-medium text-gray-700 mt-2">Application Expired</p>
                </div>
              ) : (
                <StepTracker
                  steps={STATUS_STEPS}
                  currentStep={getStatusStep(application.status)}
                  variant="vertical"
                />
              )}
            </Card>
          </div>

          {/* Admin Actions */}
          <Card>
            <h3 className="text-sm font-semibold text-gray-800 mb-4">Admin Actions</h3>
            <div className="space-y-2">
              {application.status === 'PENDING_REVIEW' && (
                <>
                  <Button
                    variant="outline"
                    fullWidth
                    size="sm"
                    onClick={() => setShowRevisionModal(true)}
                    loading={actionLoading}
                  >
                    📝 Request Revision
                  </Button>
                  <Button
                    variant="primary"
                    fullWidth
                    size="sm"
                    onClick={() => setShowApproveConfirm(true)}
                    loading={actionLoading}
                  >
                    ✅ Approve & Request Payment
                  </Button>
                </>
              )}

              {application.status === 'REVISION_REQUESTED' && (
                <div className="bg-warning-50 rounded-lg p-3 border border-warning-200 text-center">
                  <span className="text-lg">⏳</span>
                  <p className="text-xs text-warning-700 mt-1">
                    Waiting for applicant to revise and resubmit.
                  </p>
                </div>
              )}

              {application.status === 'PENDING_PAYMENT' && (
                <Button
                  variant="outline"
                  fullWidth
                  size="sm"
                  onClick={() => setShowPaymentModal(true)}
                  loading={actionLoading}
                >
                  💳 Confirm Payment
                </Button>
              )}

              {application.status === 'PAID' && (
                <Button
                  variant="outline"
                  fullWidth
                  size="sm"
                  onClick={() => setShowProcessingConfirm(true)}
                  loading={actionLoading}
                >
                  🔄 Start Processing
                </Button>
              )}

              {application.status === 'IN_PROGRESS' && (
                <Button
                  variant="primary"
                  fullWidth
                  size="sm"
                  onClick={() => setShowCompleteModal(true)}
                  loading={actionLoading}
                >
                  ✅ Complete & Issue Licence
                </Button>
              )}

              {application.status === 'COMPLETED' && (
                <div className="bg-success-50 rounded-lg p-3 border border-success-200 text-center">
                  <span className="text-lg">🎉</span>
                  <p className="text-xs text-success-700 mt-1">
                    This application is completed.
                  </p>
                </div>
              )}

              {application.status === 'EXPIRED' && (
                <div className="bg-gray-50 rounded-lg p-3 border border-gray-200 text-center">
                  <p className="text-xs text-gray-500">
                    No actions available for expired applications.
                  </p>
                </div>
              )}
            </div>
          </Card>

          {/* Assigned LEW (ADMIN only) */}
          {isAdmin && (
            <Card>
              <h3 className="text-sm font-semibold text-gray-800 mb-3">Assigned LEW</h3>
              {application.assignedLewSeq ? (
                <div className="space-y-3">
                  <div className="flex items-center gap-3 p-3 bg-primary-50 rounded-lg border border-primary-100">
                    <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
                      <span className="text-sm">⚡</span>
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-gray-800">{application.assignedLewName}</p>
                      <p className="text-xs text-gray-500 truncate">{application.assignedLewEmail}</p>
                      {application.assignedLewLicenceNo && (
                        <p className="text-xs text-primary-600 font-mono mt-0.5">{application.assignedLewLicenceNo}</p>
                      )}
                      {application.assignedLewGrade && (
                        <Badge variant="info" className="mt-1 text-[10px]">
                          {application.assignedLewGrade.replace('GRADE_', 'G')} (≤{application.assignedLewMaxKva === 9999 ? '400kV' : `${application.assignedLewMaxKva}kVA`})
                        </Badge>
                      )}
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      fullWidth
                      onClick={openAssignLewModal}
                    >
                      Change
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      fullWidth
                      onClick={() => setShowUnassignConfirm(true)}
                    >
                      Remove
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="text-center py-2">
                  <p className="text-sm text-gray-500 mb-3">No LEW assigned</p>
                  <Button
                    variant="outline"
                    size="sm"
                    fullWidth
                    onClick={openAssignLewModal}
                  >
                    ⚡ Assign LEW
                  </Button>
                </div>
              )}
            </Card>
          )}

          {/* Licence Info (when completed) */}
          {application.status === 'COMPLETED' && application.licenseNumber && (
            <Card>
              <h3 className="text-sm font-semibold text-gray-800 mb-4">Licence Information</h3>
              <div className="space-y-3">
                <InfoField label="Licence Number" value={application.licenseNumber} />
                {application.licenseExpiryDate && (
                  <InfoField
                    label="Expiry Date"
                    value={new Date(application.licenseExpiryDate).toLocaleDateString()}
                  />
                )}
              </div>
            </Card>
          )}

          {/* Quick Info */}
          <Card>
            <h3 className="text-sm font-semibold text-gray-800 mb-3">Quick Info</h3>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-gray-500">Application ID</span>
                <span className="font-medium text-gray-700">#{application.applicationSeq}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Submitted</span>
                <span className="font-medium text-gray-700">
                  {new Date(application.createdAt).toLocaleDateString()}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Last Updated</span>
                <span className="font-medium text-gray-700">
                  {new Date(application.updatedAt).toLocaleDateString()}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Documents</span>
                <span className="font-medium text-gray-700">{files.length} file(s)</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Payments</span>
                <span className="font-medium text-gray-700">{payments.length} record(s)</span>
              </div>
            </div>
          </Card>
        </div>
      </div>

      {/* ── Modals ────────────────── */}
      <PaymentModal
        isOpen={showPaymentModal}
        onClose={() => setShowPaymentModal(false)}
        onConfirm={handleConfirmPayment}
        quoteAmount={application.quoteAmount}
        paymentForm={paymentForm}
        setPaymentForm={setPaymentForm}
        loading={actionLoading}
      />
      <CompleteModal
        isOpen={showCompleteModal}
        onClose={() => setShowCompleteModal(false)}
        onConfirm={handleComplete}
        completeForm={completeForm}
        setCompleteForm={setCompleteForm}
        loading={actionLoading}
      />
      <RevisionModal
        isOpen={showRevisionModal}
        onClose={() => setShowRevisionModal(false)}
        onConfirm={handleRequestRevision}
        revisionComment={revisionComment}
        setRevisionComment={setRevisionComment}
        loading={actionLoading}
      />
      <ApproveConfirmDialog
        isOpen={showApproveConfirm}
        onClose={() => setShowApproveConfirm(false)}
        onConfirm={handleApproveForPayment}
        loading={actionLoading}
      />
      <ProcessingConfirmDialog
        isOpen={showProcessingConfirm}
        onClose={() => setShowProcessingConfirm(false)}
        onConfirm={handleStartProcessing}
      />
      <AssignLewModal
        isOpen={showAssignLewModal}
        onClose={() => setShowAssignLewModal(false)}
        onConfirm={handleAssignLew}
        lewsLoading={lewsLoading}
        availableLews={availableLews}
        selectedLewSeq={selectedLewSeq}
        setSelectedLewSeq={setSelectedLewSeq}
        applicationKva={application?.selectedKva}
        loading={actionLoading}
      />
      <UnassignLewConfirmDialog
        isOpen={showUnassignConfirm}
        onClose={() => setShowUnassignConfirm(false)}
        onConfirm={handleUnassignLew}
        loading={actionLoading}
      />
      <SldConfirmDialog
        isOpen={showSldConfirm}
        onClose={() => setShowSldConfirm(false)}
        onConfirm={handleSldConfirm}
        loading={actionLoading}
      />
    </div>
  );
}

