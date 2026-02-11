import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Input } from '../../components/ui/Input';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import { FileUpload } from '../../components/domain/FileUpload';
import { useToastStore } from '../../stores/toastStore';
import applicationApi from '../../api/applicationApi';
import fileApi from '../../api/fileApi';
import priceApi from '../../api/priceApi';
import { Select } from '../../components/ui/Select';
import { InfoField } from '../../components/common/InfoField';
import { STATUS_STEPS, getStatusStep, formatFileSize, formatFileType, getFileTypeBadge } from '../../utils/applicationUtils';
import type { Application, FileInfo, FileType, MasterPrice, Payment, SldRequest } from '../../types';

const APPLICANT_FILE_TYPE_OPTIONS = [
  { value: 'DRAWING_SLD', label: 'Single Line Diagram (SLD)' },
  { value: 'OWNER_AUTH_LETTER', label: "Owner's Authorisation Letter" },
];

export default function ApplicationDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToastStore();

  const [application, setApplication] = useState<Application | null>(null);
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteFileId, setDeleteFileId] = useState<string | number | null>(null);
  const [uploadFileType, setUploadFileType] = useState<FileType>('DRAWING_SLD');
  const [paymentInfo, setPaymentInfo] = useState<Record<string, string>>({});
  const [sldRequest, setSldRequest] = useState<SldRequest | null>(null);

  // Edit mode state
  const [editMode, setEditMode] = useState(false);
  const [editAddress, setEditAddress] = useState('');
  const [editPostalCode, setEditPostalCode] = useState('');
  const [editBuildingType, setEditBuildingType] = useState('');
  const [editKva, setEditKva] = useState<number>(0);
  const [editPrice, setEditPrice] = useState<number | null>(null);
  const [prices, setPrices] = useState<MasterPrice[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [showResubmitConfirm, setShowResubmitConfirm] = useState(false);

  const applicationId = Number(id);

  const fetchData = useCallback(async () => {
    try {
      const [appData, filesData, paymentsData] = await Promise.all([
        applicationApi.getApplication(applicationId),
        fileApi.getFilesByApplication(applicationId),
        applicationApi.getApplicationPayments(applicationId),
      ]);
      setApplication(appData);
      setFiles(filesData);
      setPayments(paymentsData);

      // Fetch SLD request if sldOption is REQUEST_LEW
      if (appData.sldOption === 'REQUEST_LEW') {
        try {
          const sldData = await applicationApi.getSldRequest(applicationId);
          setSldRequest(sldData);
        } catch {
          // SLD request might not exist yet
        }
      }

      // Fetch payment info when status is PENDING_PAYMENT
      if (appData.status === 'PENDING_PAYMENT') {
        try {
          const info = await priceApi.getPaymentInfo();
          setPaymentInfo(info);
        } catch {
          // Payment info is non-critical, continue without it
        }
      }
    } catch {
      toast.error('Failed to load application details');
      navigate('/applications');
    } finally {
      setLoading(false);
    }
  }, [applicationId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Load prices when entering edit mode
  const enterEditMode = async () => {
    if (!application) return;
    try {
      const priceData = await priceApi.getPrices();
      setPrices(priceData);
      setEditAddress(application.address);
      setEditPostalCode(application.postalCode);
      setEditBuildingType(application.buildingType || '');
      setEditKva(application.selectedKva);
      setEditPrice(application.quoteAmount);
      setEditMode(true);
    } catch {
      toast.error('Failed to load price information');
    }
  };

  // Recalculate price when kVA changes
  const handleKvaChange = async (kva: number) => {
    setEditKva(kva);
    if (kva > 0) {
      try {
        const result = await priceApi.calculatePrice(kva);
        setEditPrice(result.totalAmount);
      } catch {
        setEditPrice(null);
      }
    }
  };

  const handleResubmit = async () => {
    if (!application) return;
    setSubmitting(true);
    try {
      const updated = await applicationApi.updateApplication(applicationId, {
        address: editAddress,
        postalCode: editPostalCode,
        buildingType: editBuildingType || undefined,
        selectedKva: editKva,
      });
      setApplication(updated);
      setEditMode(false);
      toast.success('Application resubmitted successfully');
      fetchData();
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to resubmit application';
      toast.error(message);
    } finally {
      setSubmitting(false);
      setShowResubmitConfirm(false);
    }
  };

  const handleFileUpload = async (file: File) => {
    await fileApi.uploadFile(applicationId, file, uploadFileType);
    toast.success('File uploaded successfully');
    const updatedFiles = await fileApi.getFilesByApplication(applicationId);
    setFiles(updatedFiles);
  };

  const handleFileDownload = async (fileInfo: FileInfo) => {
    try {
      await fileApi.downloadFile(fileInfo.fileSeq, fileInfo.originalFilename || 'download');
    } catch {
      toast.error('Failed to download file');
    }
  };

  const handleFileDelete = async (fileId: string | number) => {
    setDeleteFileId(fileId);
  };

  const confirmFileDelete = async () => {
    if (deleteFileId === null) return;
    try {
      await fileApi.deleteFile(Number(deleteFileId));
      toast.success('File deleted');
      setFiles((prev) => prev.filter((f) => f.fileSeq !== Number(deleteFileId)));
    } catch {
      toast.error('Failed to delete file');
    } finally {
      setDeleteFileId(null);
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

  const canUpload = ['PENDING_REVIEW', 'REVISION_REQUESTED', 'PENDING_PAYMENT', 'PAID']
    .includes(application.status);

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/applications')}
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
              Submitted on {new Date(application.createdAt).toLocaleDateString()}
            </p>
          </div>
        </div>
        <StatusBadge status={application.status} />
      </div>

      {/* PENDING_REVIEW Banner */}
      {application.status === 'PENDING_REVIEW' && (
        <div className="bg-info-50 border border-info-200 rounded-lg p-4">
          <div className="flex items-start gap-3">
            <span className="text-lg">🔍</span>
            <div>
              <p className="text-sm font-medium text-info-800">Under Review</p>
              <p className="text-xs text-info-700 mt-1">
                Your application is being reviewed by the Licensed Electrical Worker (LEW). You will be notified once the review is complete.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* REVISION_REQUESTED Banner */}
      {application.status === 'REVISION_REQUESTED' && (
        <div className="bg-warning-50 border border-warning-200 rounded-lg p-4">
          <div className="flex items-start gap-3">
            <span className="text-lg">📝</span>
            <div className="flex-1">
              <p className="text-sm font-medium text-warning-800">Revision Requested</p>
              <p className="text-xs text-warning-700 mt-1">
                The LEW has requested changes to your application. Please review the comment below, make the necessary updates, and resubmit.
              </p>
              {application.reviewComment && (
                <div className="mt-3 bg-white rounded-lg p-3 border border-warning-200">
                  <p className="text-xs font-medium text-gray-500 mb-1">LEW Comment:</p>
                  <p className="text-sm text-gray-800 whitespace-pre-wrap">{application.reviewComment}</p>
                </div>
              )}
              {!editMode && (
                <Button
                  size="sm"
                  className="mt-3"
                  onClick={enterEditMode}
                >
                  Edit & Resubmit
                </Button>
              )}
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
              <StepTracker
                steps={STATUS_STEPS}
                currentStep={getStatusStep(application.status)}
                variant="horizontal"
              />
            </div>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Main content (left 2/3) */}
        <div className="lg:col-span-2 space-y-6">
          {/* Property Details */}
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Property Details</h2>

            {editMode ? (
              <div className="space-y-4">
                <Input
                  label="Installation Address"
                  required
                  maxLength={255}
                  value={editAddress}
                  onChange={(e) => setEditAddress(e.target.value)}
                />
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <Input
                    label="Postal Code"
                    required
                    maxLength={10}
                    value={editPostalCode}
                    onChange={(e) => setEditPostalCode(e.target.value)}
                  />
                  <Input
                    label="Building Type"
                    maxLength={50}
                    value={editBuildingType}
                    onChange={(e) => setEditBuildingType(e.target.value)}
                  />
                </div>
                <Select
                  label="DB Size (kVA)"
                  required
                  value={String(editKva)}
                  onChange={(e) => handleKvaChange(Number(e.target.value))}
                  options={prices.map((p) => ({
                    value: String(p.kvaMin),
                    label: `${p.kvaMin} kVA — SGD $${p.price.toLocaleString()}`,
                  }))}
                  placeholder="Select kVA"
                />
                {editPrice !== null && (
                  <div className="bg-primary-50 rounded-lg p-3 border border-primary-100">
                    <p className="text-sm text-primary-700">
                      Updated Quote: <span className="font-bold">SGD ${editPrice.toLocaleString()}</span>
                    </p>
                  </div>
                )}
                <div className="flex gap-3 pt-2">
                  <Button
                    onClick={() => setShowResubmitConfirm(true)}
                    loading={submitting}
                    disabled={!editAddress || !editPostalCode || !editKva}
                  >
                    Resubmit Application
                  </Button>
                  <Button variant="outline" onClick={() => setEditMode(false)}>
                    Cancel
                  </Button>
                </div>
              </div>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <InfoField label="Installation Address" value={application.address} />
                <InfoField label="Postal Code" value={application.postalCode} />
                <InfoField label="Building Type" value={application.buildingType || 'Not specified'} />
                <InfoField label="DB Size (kVA)" value={`${application.selectedKva} kVA`} />
                {application.spAccountNo && (
                  <InfoField label="SP Account No." value={application.spAccountNo} />
                )}
              </div>
            )}
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
                    <InfoField label="Original Application" value={`#${application.originalApplicationSeq}`} />
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
                  <p className="text-xs text-primary-600 mt-1">Based on {application.selectedKva} kVA capacity</p>
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

            {application.status === 'PENDING_PAYMENT' && (
              <div className="mt-4 space-y-3">
                <div className="bg-warning-50 rounded-lg p-4 border border-warning-200">
                  <div className="flex items-start gap-3">
                    <span className="text-lg">💳</span>
                    <div>
                      <p className="text-sm font-medium text-warning-800">Payment Required</p>
                      <p className="text-xs text-warning-700 mt-1">
                        Please make payment of <span className="font-semibold">SGD ${application.quoteAmount.toLocaleString()}</span> via one of the methods below. Your application will be processed once payment is confirmed by our team.
                      </p>
                    </div>
                  </div>
                </div>

                {/* Payment Methods */}
                <div className="bg-surface-secondary rounded-lg p-4 border border-gray-200">
                  <p className="text-sm font-semibold text-gray-800 mb-3">Payment Methods</p>

                  {/* PayNow */}
                  {(paymentInfo.payment_paynow_uen || paymentInfo.payment_paynow_name) && (
                    <div className="flex items-start gap-3 mb-3 pb-3 border-b border-gray-100">
                      <div className="w-8 h-8 bg-primary-100 rounded-lg flex items-center justify-center flex-shrink-0">
                        <span className="text-sm font-bold text-primary-700">P</span>
                      </div>
                      <div>
                        <p className="text-sm font-medium text-gray-800">PayNow</p>
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

                  {/* Bank Transfer */}
                  {(paymentInfo.payment_bank_name || paymentInfo.payment_bank_account) && (
                    <div className="flex items-start gap-3">
                      <div className="w-8 h-8 bg-primary-100 rounded-lg flex items-center justify-center flex-shrink-0">
                        <span className="text-sm font-bold text-primary-700">B</span>
                      </div>
                      <div>
                        <p className="text-sm font-medium text-gray-800">Bank Transfer</p>
                        {paymentInfo.payment_bank_name && (
                          <p className="text-xs text-gray-600 mt-0.5">Bank: <span className="font-medium">{paymentInfo.payment_bank_name}</span></p>
                        )}
                        {paymentInfo.payment_bank_account && (
                          <p className="text-xs text-gray-600 mt-0.5">Account: <span className="font-mono font-medium">{paymentInfo.payment_bank_account}</span></p>
                        )}
                        {paymentInfo.payment_bank_account_name && (
                          <p className="text-xs text-gray-600 mt-0.5">Account Name: <span className="font-medium">{paymentInfo.payment_bank_account_name}</span></p>
                        )}
                        <p className="text-xs text-gray-500 mt-0.5">Reference: <span className="font-mono font-medium">BL-{application.applicationSeq}</span></p>
                      </div>
                    </div>
                  )}

                  <p className="text-xs text-gray-400 mt-3 pt-3 border-t border-gray-100">
                    Please include the reference number in your payment. Processing takes 1-2 business days after payment is received.
                  </p>
                </div>
              </div>
            )}
          </Card>

          {/* SLD Request Status */}
          {application.sldOption === 'REQUEST_LEW' && sldRequest && (
            <Card>
              <h2 className="text-lg font-semibold text-gray-800 mb-4">SLD Drawing Request</h2>
              {sldRequest.status === 'REQUESTED' && (
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg">🔧</span>
                    <div>
                      <p className="text-sm font-medium text-blue-800">SLD Drawing Request Sent</p>
                      <p className="text-xs text-blue-700 mt-1">
                        Your SLD drawing request has been sent to the assigned LEW. You will be notified once the SLD is prepared.
                      </p>
                      {sldRequest.applicantNote && (
                        <div className="mt-2 bg-white rounded p-2 border border-blue-100">
                          <p className="text-xs text-gray-500">Your note:</p>
                          <p className="text-sm text-gray-700">{sldRequest.applicantNote}</p>
                        </div>
                      )}
                      <p className="text-xs text-blue-500 mt-2">
                        Requested on {new Date(sldRequest.createdAt).toLocaleDateString()}
                      </p>
                    </div>
                  </div>
                </div>
              )}
              {sldRequest.status === 'UPLOADED' && (
                <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg">✅</span>
                    <div>
                      <p className="text-sm font-medium text-green-800">SLD Has Been Uploaded</p>
                      <p className="text-xs text-green-700 mt-1">
                        The LEW has uploaded the SLD drawing. It is pending confirmation.
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
              )}
              {sldRequest.status === 'CONFIRMED' && (
                <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
                  <div className="flex items-start gap-3">
                    <span className="text-lg">📋</span>
                    <div>
                      <p className="text-sm font-medium text-gray-700">SLD Confirmed</p>
                      <p className="text-xs text-gray-500 mt-1">
                        The SLD drawing has been confirmed and is included in your application.
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </Card>
          )}

          {/* Documents */}
          <Card>
            <h2 className="text-lg font-semibold text-gray-800 mb-4">Documents</h2>

            {canUpload && (
              <div className="space-y-3 mb-4">
                <Select
                  label="Document Type"
                  value={uploadFileType}
                  onChange={(e) => setUploadFileType(e.target.value as FileType)}
                  options={APPLICANT_FILE_TYPE_OPTIONS}
                />
                <FileUpload
                  onUpload={handleFileUpload}
                  onRemove={handleFileDelete}
                  files={files.map((f) => ({
                    id: f.fileSeq,
                    name: f.originalFilename || `File #${f.fileSeq}`,
                    size: f.fileSize || 0,
                  }))}
                  label={APPLICANT_FILE_TYPE_OPTIONS.find((o) => o.value === uploadFileType)?.label || 'Document'}
                  hint="PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP up to 10MB. Files for ELISE submission should be under 2MB."
                  warnSizeMb={2}
                  warnSizeMessage="This file exceeds 2MB and may need to be resized before ELISE submission to EMA."
                />
              </div>
            )}

            {!canUpload && files.length === 0 && (
              <p className="text-sm text-gray-500">No documents uploaded.</p>
            )}

            {!canUpload && files.length > 0 && (
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
                          <Badge variant={getFileTypeBadge(f.fileType)} className="text-[10px]">
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
            </Card>
          )}
        </div>

        {/* Sidebar (right 1/3) */}
        <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
          {/* Status Tracker (desktop only - mobile version above grid) */}
          <div className="hidden lg:block">
            <Card>
              <h3 className="text-sm font-semibold text-gray-800 mb-4">Progress</h3>
              {application.status === 'EXPIRED' ? (
                <div className="text-center py-4">
                  <span className="text-3xl">⏰</span>
                  <p className="text-sm font-medium text-gray-700 mt-2">Application Expired</p>
                  <p className="text-xs text-gray-500 mt-1">
                    This application has expired due to non-payment.
                  </p>
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

          {/* Assigned LEW */}
          {application.assignedLewName && (
            <Card>
              <h3 className="text-sm font-semibold text-gray-800 mb-3">Assigned LEW</h3>
              <div className="flex items-center gap-3 p-3 bg-primary-50 rounded-lg border border-primary-100">
                <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
                  <span className="text-sm">⚡</span>
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-800">{application.assignedLewName}</p>
                  {application.assignedLewLicenceNo && (
                    <p className="text-xs text-primary-600 font-mono mt-0.5">{application.assignedLewLicenceNo}</p>
                  )}
                </div>
              </div>
            </Card>
          )}

          {/* Licence Info (only when completed) */}
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
              <div className="mt-4 bg-success-50 rounded-lg p-3 border border-success-200">
                <div className="flex items-center gap-2">
                  <span className="text-lg">✅</span>
                  <p className="text-xs text-success-700">
                    Your electrical installation licence has been issued.
                  </p>
                </div>
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
            </div>
          </Card>
        </div>
      </div>

      <ConfirmDialog
        isOpen={deleteFileId !== null}
        onClose={() => setDeleteFileId(null)}
        onConfirm={confirmFileDelete}
        title="Delete File"
        message="Are you sure you want to delete this file? This action cannot be undone."
        confirmLabel="Delete"
        variant="danger"
      />

      <ConfirmDialog
        isOpen={showResubmitConfirm}
        onClose={() => setShowResubmitConfirm(false)}
        onConfirm={handleResubmit}
        title="Resubmit Application"
        message="Are you sure you want to resubmit this application? It will be sent back to the LEW for review."
        confirmLabel="Resubmit"
        loading={submitting}
      />
    </div>
  );
}

