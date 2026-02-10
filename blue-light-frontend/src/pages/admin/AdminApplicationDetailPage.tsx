import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Input } from '../../components/ui/Input';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import { FileUpload } from '../../components/domain/FileUpload';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../components/ui/Modal';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { useToastStore } from '../../stores/toastStore';
import adminApi from '../../api/adminApi';
import fileApi from '../../api/fileApi';
import { Select } from '../../components/ui/Select';
import { useAuthStore } from '../../stores/authStore';
import type { AdminApplication, FileInfo, FileType, Payment, LewSummary } from '../../types';

const FILE_TYPE_OPTIONS = [
  { value: 'LICENSE_PDF', label: 'Licence PDF' },
  { value: 'REPORT_PDF', label: 'Report PDF' },
  { value: 'OWNER_AUTH_LETTER', label: "Owner's Auth Letter" },
];

const STATUS_STEPS = [
  { label: 'Submitted', description: 'Application submitted for review' },
  { label: 'Reviewed', description: 'LEW review completed' },
  { label: 'Paid', description: 'Payment confirmed' },
  { label: 'In Progress', description: 'Under processing' },
  { label: 'Completed', description: 'Licence issued' },
];

function getStatusStep(status: string): number {
  switch (status) {
    case 'PENDING_REVIEW': return 0;
    case 'REVISION_REQUESTED': return 0;
    case 'PENDING_PAYMENT': return 1;
    case 'PAID': return 2;
    case 'IN_PROGRESS': return 3;
    case 'COMPLETED': return 5;
    case 'EXPIRED': return -1;
    default: return 0;
  }
}

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
      const lews = await adminApi.getAvailableLews();
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
            className="p-1.5 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
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
              {application.userDesignation && (
                <InfoField label="Designation" value={application.userDesignation} />
              )}
            </div>
            {(application.userCompanyName || application.userUen) && (
              <div className="mt-4 pt-4 border-t border-gray-100">
                <h3 className="text-sm font-medium text-gray-600 mb-3">Business Details</h3>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  {application.userCompanyName && (
                    <InfoField label="Company Name" value={application.userCompanyName} />
                  )}
                  {application.userUen && (
                    <InfoField label="UEN" value={application.userUen} />
                  )}
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
        <div className="space-y-6">
          {/* Status Tracker */}
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

      {/* ── Payment Confirmation Modal ────────────────── */}
      <Modal isOpen={showPaymentModal} onClose={() => setShowPaymentModal(false)} size="sm">
        <ModalHeader title="Confirm Payment" onClose={() => setShowPaymentModal(false)} />
        <ModalBody>
          <div className="space-y-4">
            <p className="text-sm text-gray-600">
              Confirm that payment of{' '}
              <span className="font-semibold">
                SGD ${application.quoteAmount.toLocaleString()}
              </span>{' '}
              has been received for this application.
            </p>
            <Input
              label="Transaction ID"
              placeholder="e.g., TXN-20250101-001"
              value={paymentForm.transactionId}
              onChange={(e) =>
                setPaymentForm((prev) => ({ ...prev, transactionId: e.target.value }))
              }
              hint="Optional - enter if available"
            />
            <Input
              label="Payment Method"
              placeholder="e.g., PayNow, Bank Transfer"
              value={paymentForm.paymentMethod}
              onChange={(e) =>
                setPaymentForm((prev) => ({ ...prev, paymentMethod: e.target.value }))
              }
            />
          </div>
        </ModalBody>
        <ModalFooter>
          <Button variant="outline" size="sm" onClick={() => setShowPaymentModal(false)}>
            Cancel
          </Button>
          <Button size="sm" onClick={handleConfirmPayment} loading={actionLoading}>
            Confirm Payment
          </Button>
        </ModalFooter>
      </Modal>

      {/* ── Complete Application Modal ────────────────── */}
      <Modal isOpen={showCompleteModal} onClose={() => setShowCompleteModal(false)} size="sm">
        <ModalHeader
          title="Complete & Issue Licence"
          onClose={() => setShowCompleteModal(false)}
        />
        <ModalBody>
          <div className="space-y-4">
            <p className="text-sm text-gray-600">
              Issue the electrical installation licence for this application.
            </p>
            <Input
              label="Licence Number"
              placeholder="e.g., EIL-2025-00001"
              value={completeForm.licenseNumber}
              onChange={(e) =>
                setCompleteForm((prev) => ({ ...prev, licenseNumber: e.target.value }))
              }
              required
            />
            <Input
              label="Expiry Date"
              type="date"
              value={completeForm.licenseExpiryDate}
              onChange={(e) =>
                setCompleteForm((prev) => ({
                  ...prev,
                  licenseExpiryDate: e.target.value,
                }))
              }
              required
            />
          </div>
        </ModalBody>
        <ModalFooter>
          <Button variant="outline" size="sm" onClick={() => setShowCompleteModal(false)}>
            Cancel
          </Button>
          <Button size="sm" onClick={handleComplete} loading={actionLoading}>
            Issue Licence
          </Button>
        </ModalFooter>
      </Modal>

      {/* ── Revision Request Modal ────────────────── */}
      <Modal isOpen={showRevisionModal} onClose={() => setShowRevisionModal(false)} size="sm">
        <ModalHeader title="Request Revision" onClose={() => setShowRevisionModal(false)} />
        <ModalBody>
          <div className="space-y-4">
            <p className="text-sm text-gray-600">
              Enter a comment describing what the applicant needs to revise.
            </p>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Review Comment<span className="text-error-500 ml-0.5">*</span>
              </label>
              <textarea
                className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary resize-none"
                rows={4}
                maxLength={2000}
                value={revisionComment}
                onChange={(e) => setRevisionComment(e.target.value)}
                placeholder="e.g., Please provide the correct postal code and update the building type."
              />
              <p className="mt-1 text-xs text-gray-500 text-right">
                {revisionComment.length}/2000
              </p>
            </div>
          </div>
        </ModalBody>
        <ModalFooter>
          <Button variant="outline" size="sm" onClick={() => setShowRevisionModal(false)}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={handleRequestRevision}
            loading={actionLoading}
            disabled={!revisionComment.trim()}
          >
            Request Revision
          </Button>
        </ModalFooter>
      </Modal>

      {/* ── Approve for Payment Confirm ────────────────── */}
      <ConfirmDialog
        isOpen={showApproveConfirm}
        onClose={() => setShowApproveConfirm(false)}
        onConfirm={handleApproveForPayment}
        title="Approve Application"
        message="Approve this application and request payment from the applicant? The status will change to PENDING_PAYMENT."
        confirmLabel="Approve"
        loading={actionLoading}
      />

      {/* ── Start Processing Confirm ────────────────── */}
      <ConfirmDialog
        isOpen={showProcessingConfirm}
        onClose={() => setShowProcessingConfirm(false)}
        onConfirm={handleStartProcessing}
        title="Start Processing"
        message="Start processing this application? Status will change to IN_PROGRESS."
        confirmLabel="Start Processing"
      />

      {/* ── Assign LEW Modal ────────────────── */}
      <Modal isOpen={showAssignLewModal} onClose={() => setShowAssignLewModal(false)} size="sm">
        <ModalHeader title="Assign LEW" onClose={() => setShowAssignLewModal(false)} />
        <ModalBody>
          {lewsLoading ? (
            <div className="flex items-center justify-center py-8">
              <LoadingSpinner size="md" label="Loading LEWs..." />
            </div>
          ) : availableLews.length === 0 ? (
            <div className="text-center py-6">
              <p className="text-sm text-gray-500">No approved LEWs available for assignment.</p>
            </div>
          ) : (
            <div className="space-y-2">
              <p className="text-sm text-gray-600 mb-3">
                Select a LEW to assign to this application:
              </p>
              {availableLews.map((lew) => (
                <button
                  key={lew.userSeq}
                  type="button"
                  onClick={() => setSelectedLewSeq(lew.userSeq)}
                  className={`w-full flex items-center gap-3 p-3 rounded-lg border-2 transition-all text-left ${
                    selectedLewSeq === lew.userSeq
                      ? 'border-primary bg-primary/5'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <div className="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center flex-shrink-0">
                    <span className="text-sm">⚡</span>
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-gray-800">{lew.name}</p>
                    <p className="text-xs text-gray-500 truncate">{lew.email}</p>
                    {lew.lewLicenceNo && (
                      <p className="text-xs text-primary-600 font-mono mt-0.5">{lew.lewLicenceNo}</p>
                    )}
                  </div>
                  {selectedLewSeq === lew.userSeq && (
                    <span className="text-primary text-lg flex-shrink-0">✓</span>
                  )}
                </button>
              ))}
            </div>
          )}
        </ModalBody>
        <ModalFooter>
          <Button variant="outline" size="sm" onClick={() => setShowAssignLewModal(false)}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={handleAssignLew}
            loading={actionLoading}
            disabled={!selectedLewSeq || lewsLoading}
          >
            Assign
          </Button>
        </ModalFooter>
      </Modal>

      {/* ── Unassign LEW Confirm ────────────────── */}
      <ConfirmDialog
        isOpen={showUnassignConfirm}
        onClose={() => setShowUnassignConfirm(false)}
        onConfirm={handleUnassignLew}
        title="Remove LEW Assignment"
        message="Remove the assigned LEW from this application? The application will become unassigned."
        confirmLabel="Remove"
        loading={actionLoading}
      />
    </div>
  );
}

function InfoField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-gray-500">{label}</dt>
      <dd className="text-sm font-medium text-gray-800 mt-0.5">{value}</dd>
    </div>
  );
}

function formatFileType(type: string): string {
  switch (type) {
    case 'DRAWING_SLD': return 'SLD';
    case 'OWNER_AUTH_LETTER': return 'Auth Letter';
    case 'SITE_PHOTO': return 'Photo';
    case 'REPORT_PDF': return 'Report';
    case 'LICENSE_PDF': return 'Licence';
    default: return type;
  }
}

function getFileTypeBadge(type: string): 'primary' | 'info' | 'success' | 'gray' {
  switch (type) {
    case 'DRAWING_SLD': return 'primary';
    case 'OWNER_AUTH_LETTER': return 'info';
    case 'SITE_PHOTO': return 'info';
    case 'REPORT_PDF': return 'gray';
    case 'LICENSE_PDF': return 'success';
    default: return 'gray';
  }
}
