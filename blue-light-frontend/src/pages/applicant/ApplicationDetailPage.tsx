import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import { InfoField } from '../../components/common/InfoField';
import { useToastStore } from '../../stores/toastStore';
import applicationApi from '../../api/applicationApi';
import fileApi from '../../api/fileApi';
import priceApi from '../../api/priceApi';
import { STATUS_STEPS, getStatusStep } from '../../utils/applicationUtils';
import { ApplicationInfo } from './sections/ApplicationInfo';
import { ApplicationPayment } from './sections/ApplicationPayment';
import { ApplicationDocuments } from './sections/ApplicationDocuments';
import type { Application, FileInfo, FileType, MasterPrice, Payment, SldRequest } from '../../types';

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

      if (appData.sldOption === 'REQUEST_LEW') {
        try {
          const sldData = await applicationApi.getSldRequest(applicationId);
          setSldRequest(sldData);
        } catch {
          // SLD request might not exist yet
        }
      }

      if (appData.status === 'PENDING_PAYMENT') {
        try {
          const info = await priceApi.getPaymentInfo();
          setPaymentInfo(info);
        } catch {
          // Payment info is non-critical
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

  // ── Edit mode handlers ──────────────────────────────

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

  const handleEditStateChange = (field: string, value: string | number) => {
    switch (field) {
      case 'address': setEditAddress(value as string); break;
      case 'postalCode': setEditPostalCode(value as string); break;
      case 'buildingType': setEditBuildingType(value as string); break;
      case 'kva': setEditKva(value as number); break;
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

  // ── File handlers ──────────────────────────────

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

  // ── Render ──────────────────────────────

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
              <Badge variant={application.applicationType === 'RENEWAL' ? 'warning' : 'info'}>
                {application.applicationType === 'RENEWAL' ? 'Renewal' : 'New'}
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
          <ApplicationInfo
            application={application}
            editMode={editMode}
            editState={{
              address: editAddress,
              postalCode: editPostalCode,
              buildingType: editBuildingType,
              kva: editKva,
              price: editPrice,
            }}
            prices={prices}
            submitting={submitting}
            onEditStateChange={handleEditStateChange}
            onKvaChange={handleKvaChange}
            onResubmit={() => setShowResubmitConfirm(true)}
            onCancelEdit={() => setEditMode(false)}
          />

          <ApplicationPayment
            application={application}
            payments={payments}
            paymentInfo={paymentInfo}
          />

          <ApplicationDocuments
            application={application}
            files={files}
            sldRequest={sldRequest}
            canUpload={canUpload}
            uploadFileType={uploadFileType}
            onUploadFileTypeChange={setUploadFileType}
            onFileUpload={handleFileUpload}
            onFileDelete={async (fileId) => { setDeleteFileId(fileId); }}
            onFileDownload={handleFileDownload}
          />
        </div>

        {/* Sidebar (right 1/3) */}
        <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
          {/* Status Tracker (desktop only) */}
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
