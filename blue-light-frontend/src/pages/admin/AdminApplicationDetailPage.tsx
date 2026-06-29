import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Badge } from '../../components/ui/Badge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import { PageHeader } from '../../components/ui/PageHeader';
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
import { KvaSection } from '../../components/admin/KvaSection';
import { AdminKvaAdjustmentSection } from '../../components/admin/AdminKvaAdjustmentSection';
import { AdminActivityTimelineSection } from '../../components/admin/AdminActivityTimelineSection';
import { AdminSldSection } from './sections/AdminSldSection';
import { AdminEmaSection } from './sections/AdminEmaSection';
import { AdminDocumentsSection } from './sections/AdminDocumentsSection';
import { AdminPaymentSection } from './sections/AdminPaymentSection';
import { AdminSidebar } from './sections/AdminSidebar';
import { LewDocumentReviewSection } from '../../components/document/LewDocumentReviewSection';
// ★ Concierge 강화 + 별도 수금 PR-4 — Manual Payment 모달 + 영수증 이력 카드.
import { ManualPaymentModal } from '../../components/admin/ManualPaymentModal';
import { InvoiceHistoryCard } from '../../components/admin/InvoiceHistoryCard';
import { recordManualPayment as recordManualPaymentApi } from '../../api/adminApplicationApi';
import { useEmaActions } from '../../hooks/useEmaActions';
import type { ManualPaymentPayload } from '../../types/manualPayment';

// Modal components
import {
  PaymentModal, CompleteModal, RevisionModal, AssignLewModal,
  ApproveConfirmDialog, ProcessingConfirmDialog, UnassignLewConfirmDialog, SldConfirmDialog,
  type CompleteForm,
} from './sections/AdminModals';

import type { AdminApplication, FileInfo, FileType, Payment, LewSummary, SldRequest, LoaStatus } from '../../types';

export default function AdminApplicationDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
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
  const [completeForm, setCompleteForm] = useState<CompleteForm>({ licenseNumber: '', licenseExpiryDate: '', licenseIssuedDate: '' });
  const [parsingLicense, setParsingLicense] = useState(false);
  // 업로드 성공 자체를 "업로드됨"의 근거로 — files(getFiles) 갱신이 실패/지연돼도 발급 버튼이 켜지게.
  const [licenseUploadedLocal, setLicenseUploadedLocal] = useState(false);
  const [uploadFileType, setUploadFileType] = useState<FileType>('LICENSE_PDF');

  // LOA states (교환 모델 — Part B에서 admin 패널이 재사용 예정)
  const [loaStatus, setLoaStatus] = useState<LoaStatus | null>(null);

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

  // ★ Concierge 강화 PR-4 — Manual Payment (offline) state
  const [showManualPaymentModal, setShowManualPaymentModal] = useState(false);
  const [manualPaymentLoading, setManualPaymentLoading] = useState(false);
  // 영수증 이력 카드 강제 새로고침 키 — manual-payment 후 invalidate.
  const [invoiceRefreshKey, setInvoiceRefreshKey] = useState(0);
  // 활동 타임라인 새로고침 키 — fetchData(액션 후 재조회) 시마다 증가.
  const [activityRefreshKey, setActivityRefreshKey] = useState(0);

  const { user: currentUser } = useAuthStore();
  const isAdmin = currentUser?.role === 'ADMIN' || currentUser?.role === 'SYSTEM_ADMIN';
  const basePath = getBasePath(currentUser?.role);
  const applicationId = Number(id);

  // ── EMA 제출 추적 (ema-submission-tracking-spec.md §8.3 — ADMIN 모니터링 + 액션) ──
  const ema = useEmaActions(applicationId);

  // 서류 요청 모달 권한 가드 — ADMIN/SYSTEM_ADMIN 전용.
  // LEW는 별도 LEW 페이지(/lew/applications/:id, /lew/applications/:id/review)에서 처리.
  const canRequestDocuments = isAdmin;

  // ── Data Fetching ──────────────────────────────────

  const fetchData = useCallback(async () => {
    try {
      // Application 상세는 필수. files/payments는 역할에 따라 권한이 제한적이므로
      // allSettled로 부분 실패 허용(LEW는 files/payments 열람 권한이 없을 수 있음).
      const appData = await adminApi.getApplication(applicationId);
      setApplication(appData);

      const [filesResult, paymentsResult] = await Promise.allSettled([
        fileApi.getFilesByApplication(applicationId),
        adminApi.getPayments(applicationId),
      ]);
      setFiles(filesResult.status === 'fulfilled' ? filesResult.value : []);
      setPayments(paymentsResult.status === 'fulfilled' ? paymentsResult.value : []);

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
      // 활동 타임라인 재조회 트리거 (상태/결제/배정 등 모든 액션 후 fetchData 호출됨).
      setActivityRefreshKey((k) => k + 1);
    } catch {
      toast.error('Failed to load application details');
      navigate(`${basePath}/applications`);
    } finally {
      setLoading(false);
    }
  }, [applicationId]);

  useEffect(() => { fetchData(); }, [fetchData]);

  // 알림 딥링크 — URL 해시(#payment/#documents)가 가리키는 섹션으로 스크롤.
  useEffect(() => {
    if (loading || !location.hash) return;
    const hashId = location.hash.slice(1);
    const t = setTimeout(() => {
      const el = document.getElementById(hashId);
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 80);
    return () => clearTimeout(t);
  }, [loading, location.hash]);

  // EMA 상태 로드 (상세 진입 시). 응답은 NOT_SUBMITTED 도 정상.
  const emaRefresh = ema.refresh;
  useEffect(() => {
    if (Number.isFinite(applicationId) && applicationId > 0) void emaRefresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applicationId]);

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

  const handleReopen = async () => {
    if (!confirm(
      'Reopen this completed application?\n\n' +
      'It will return to "In Progress" so the applicant and LEW can edit files again. ' +
      'This action is recorded in the activity timeline.'
    )) return;
    setActionLoading(true);
    try {
      await adminApi.reopenApplication(applicationId);
      toast.success('Application reopened — now In Progress');
      fetchData();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      toast.error(e?.response?.data?.message || 'Failed to reopen application');
    } finally { setActionLoading(false); }
  };

  // 라이선스 PDF 업로드 → AI 파싱 → 완료 폼 프리필 (번호/발급일/만료일, LEW 검토·수정).
  const handleLicenseUpload = async (file: File) => {
    setParsingLicense(true);
    try {
      await adminApi.uploadFile(applicationId, file, 'LICENSE_PDF');
      setLicenseUploadedLocal(true); // 업로드 성공 → 발급 버튼 즉시 활성(getFiles 의존 X)
      try {
        const parsed = await adminApi.parseLicense(applicationId);
        setCompleteForm((prev) => ({
          licenseNumber: parsed.licenseNumber || prev.licenseNumber,
          licenseExpiryDate: parsed.expiryDate || prev.licenseExpiryDate,
          licenseIssuedDate: parsed.issueDate || prev.licenseIssuedDate,
        }));
        toast.success('Licence read — please review the fields');
      } catch {
        // 파싱 실패해도 업로드는 정상 — LEW 가 번호·만료일을 직접 입력하면 발급 가능.
        toast.warning('Licence uploaded. Couldn’t auto-read it — please enter the licence number and expiry date manually.');
      }
      fetchData(); // 파일 목록 갱신 → licenseUploaded 반영(파싱 결과와 무관)
    } catch {
      toast.error('Failed to upload the licence file');
    } finally {
      setParsingLicense(false);
    }
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
      void ema.refresh();
    } catch (err: unknown) {
      // EMA 종료 게이트 에러코드 매핑 (ema-submission-tracking-spec.md §4).
      const e = err as { response?: { data?: { code?: string; message?: string } } };
      const code = e?.response?.data?.code;
      if (code === 'EMA_NOT_APPROVED') toast.error('EMA submission must be approved before completion.');
      else if (code === 'LICENSE_PDF_MISSING') toast.error('Upload the licence PDF before completing.');
      else toast.error(e?.response?.data?.message || 'Failed to complete application');
    }
    finally { setActionLoading(false); }
  };

  const handleFileUpload = async (file: File, fileType?: FileType) => {
    const type = fileType || uploadFileType;
    await adminApi.uploadFile(applicationId, file, type);
    toast.success('File uploaded successfully');
    const updatedFiles = await fileApi.getFilesByApplication(applicationId);
    setFiles(updatedFiles);
  };

  const handleFileDownload = async (fileInfo: FileInfo) => {
    try { await fileApi.downloadFile(fileInfo.fileSeq, fileInfo.originalFilename || 'download'); }
    catch { toast.error('Failed to download file'); }
  };

  const handleFileDelete = async (fileId: number) => {
    if (!confirm('Are you sure you want to delete this file?')) return;
    try {
      await fileApi.deleteFile(fileId);
      toast.success('File deleted successfully');
      const updatedFiles = await fileApi.getFilesByApplication(applicationId);
      setFiles(updatedFiles);
    } catch { toast.error('Failed to delete file'); }
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

  // ★ Concierge 강화 PR-4 — Manual Payment (offline) handler.
  // 스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §7.3, AC-A1~A7.
  // finally 블록에서 로딩 해제 — 에러는 모달이 자체 errMsg 로 표시하도록 자연 propagate.
  const handleRecordManualPayment = async (payload: ManualPaymentPayload) => {
    setManualPaymentLoading(true);
    try {
      const response = await recordManualPaymentApi(applicationId, payload);
      const issuedNote = payload.receiptIssue !== false
        ? response.invoiceNumber
          ? ` Receipt ${response.invoiceNumber} issued.`
          : ' Receipt will be issued shortly.'
        : '';
      toast.success(`Payment of SGD ${Number(payload.amount).toFixed(2)} recorded.${issuedNote}`);
      setShowManualPaymentModal(false);
      // 신청 상태/payments 갱신.
      await fetchData();
      // InvoiceHistoryCard 가 새 invoice 를 다시 조회하도록 트리거.
      setInvoiceRefreshKey((k) => k + 1);
    } finally {
      setManualPaymentLoading(false);
    }
  };

  // LOA (교환 모델 — Part B admin 패널)
  // 등록/교체 후 loaStatus 와 files 를 함께 재조회한다.
  // (files 까지 갱신해야 LoA 행의 파일명·업로드 시각이 새 파일로 바뀌어 교체가 화면에 반영됨)
  const loadLoaStatus = useCallback(async () => {
    try {
      const [loaData, updatedFiles] = await Promise.all([
        loaApi.getLoaStatus(applicationId),
        fileApi.getFilesByApplication(applicationId).catch(() => null),
      ]);
      setLoaStatus(loaData);
      if (updatedFiles) setFiles(updatedFiles);
    } catch { /* LOA status might not be available */ }
  }, [applicationId]);

  const handleLoaDownload = async (fileSeq: number, filename: string) => {
    try { await fileApi.downloadFile(fileSeq, filename); }
    catch { toast.error('Failed to download LOA'); }
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

  const handleSldUnconfirm = async () => {
    if (!confirm('Reopen the SLD? This will allow re-uploading or regenerating the SLD drawing.')) return;
    setActionLoading(true);
    try {
      await adminApi.unconfirmSld(applicationId);
      toast.success('SLD reopened for editing');
      fetchData();
    } catch { toast.error('Failed to reopen SLD'); }
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
    <div className="max-w-7xl mx-auto space-y-6">
      {/* Back navigation — PageHeader 위에 유지 */}
      <div>
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
      </div>

      <PageHeader
        title={
          <span className="flex items-center gap-2">
            Application #{application.applicationSeq}
            <Badge variant={application.applicationType === 'RENEWAL' ? 'warning' : 'info'}>
              {application.applicationType === 'RENEWAL' ? 'Renewal' : 'New'}
            </Badge>
          </span>
        }
        subtitle="Admin view — manage status and payments"
        actions={<StatusBadge status={application.status} />}
      />

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
          <div className="mt-3">
            <StepTracker steps={STATUS_STEPS} currentStep={getStatusStep(application.status)} variant="horizontal" />
          </div>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Main content */}
        <div className="lg:col-span-2 space-y-6">
          <AdminApplicationInfo
            application={application}
            onNavigateToOriginal={(seq) => navigate(`${basePath}/applications/${seq}`)}
          />

          {/* 활동 타임라인 (audit_logs SSOT) — ADMIN/SYSTEM_ADMIN 전용.
              엔드포인트가 LEW 를 차단하므로 isAdmin 가드로 호출 자체를 막는다. */}
          {isAdmin && (
            <div id="activity" className="scroll-mt-24">
              <AdminActivityTimelineSection
                applicationSeq={applicationId}
                refreshKey={activityRefreshKey}
              />
            </div>
          )}

          {/* Phase 5 PR#3 — kVA 확정 섹션 (ADMIN/LEW) */}
          <KvaSection application={application} onUpdated={fetchData} />

          {/* PR-4 — 결제 후 kVA 사후 변경 이력 섹션 (PAID/IN_PROGRESS/COMPLETED 에서만 노출) */}
          <AdminKvaAdjustmentSection
            applicationSeq={applicationId}
            applicationStatus={application.status}
          />

          {application.sldOption === 'REQUEST_LEW' && sldRequest && (
            <AdminSldSection
              applicationSeq={applicationId}
              sldRequest={sldRequest}
              sldLewNote={sldLewNote}
              onSldLewNoteChange={setSldLewNote}
              onSldUpload={handleSldUpload}
              onSldConfirmClick={() => setShowSldConfirm(true)}
              onSldUnconfirmClick={handleSldUnconfirm}
              onSldUpdated={fetchData}
              actionLoading={actionLoading}
              existingSldFiles={files.filter((f) => f.fileType === 'DRAWING_SLD')}
              onFileDelete={handleFileDelete}
            />
          )}

          {/* EMA 제출 추적 — IN_PROGRESS 이거나 EMA 가 이미 시작된 건에만 노출(불필요한 노이즈 방지). */}
          {(application.status === 'IN_PROGRESS' ||
            (ema.ema && ema.ema.emaSubmissionStatus !== 'NOT_SUBMITTED')) && (
            <AdminEmaSection
              ema={ema.ema}
              appStatus={application.status}
              isAdmin={isAdmin}
              busy={ema.busy}
              onSubmit={ema.submit}
              onQuery={ema.query}
              onResubmit={ema.resubmit}
              onApprove={ema.approve}
              onReject={ema.reject}
              onWithdraw={ema.withdraw}
              onRevert={ema.revert}
              onUploadFile={ema.uploadFile}
              onCompleteClick={() => setShowCompleteModal(true)}
            />
          )}

          <AdminDocumentsSection
            files={files}
            status={application.status}
            uploadFileType={uploadFileType}
            onUploadFileTypeChange={setUploadFileType}
            onFileUpload={handleFileUpload}
            onFileDownload={handleFileDownload}
            onFileDelete={handleFileDelete}
          />

          {/* Phase 3 PR#2 — LEW/ADMIN 서류 요청 섹션. id="documents": 서류 알림 딥링크 타깃 */}
          <div id="documents" className="scroll-mt-24">
            <LewDocumentReviewSection
              applicationSeq={applicationId}
              canRequest={canRequestDocuments}
              applicantDisplayName={
                application.userFirstName || application.userLastName
                  ? `${application.userFirstName ?? ''} ${application.userLastName ?? ''}`.trim()
                  : application.userEmail
              }
              applicationCode={`APP-${String(application.applicationSeq).padStart(6, '0')}`}
              canFulfill={isAdmin}
            />
          </div>

          {/* LoA 교환 진행상태 + admin 등록/교체(사유 필수, 기존 파일 보관) — ADMIN/SYSTEM_ADMIN 전용 */}
          {isAdmin && (
            <div id="loa" className="scroll-mt-24">
              <AdminLoaSection
                application={application}
                loaStatus={loaStatus}
                files={files}
                onDownload={handleLoaDownload}
                onReplaced={loadLoaStatus}
              />
            </div>
          )}

          {/* id="payment": 결제 증빙/확인 요청 알림 딥링크 타깃 */}
          <div id="payment" className="scroll-mt-24">
            <AdminPaymentSection payments={payments} files={files} applicationStatus={application.status} />
          </div>

          {/* ★ Concierge 강화 PR-4 — 영수증 이력 카드 (ADMIN/SYSTEM_ADMIN 전용 표시).
              LEW 는 영수증 카드를 노출하지 않는다(스펙 §11 — invoice 는 신청자에 귀속). */}
          {isAdmin && (
            <InvoiceHistoryCard
              applicationSeq={applicationId}
              mode="admin"
              refreshKey={invoiceRefreshKey}
            />
          )}
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
          onReopenClick={handleReopen}
          onAssignLewClick={openAssignLewModal}
          onUnassignLewClick={() => setShowUnassignConfirm(true)}
          onManualPaymentClick={() => setShowManualPaymentModal(true)}
        />
      </div>

      {/* Modals */}
      <PaymentModal
        isOpen={showPaymentModal} onClose={() => setShowPaymentModal(false)}
        onConfirm={handleConfirmPayment} quoteAmount={application.quoteAmount}
        paymentForm={paymentForm} setPaymentForm={setPaymentForm} loading={actionLoading}
        assignedLewSeq={application.assignedLewSeq ?? null}
      />
      <CompleteModal
        isOpen={showCompleteModal} onClose={() => setShowCompleteModal(false)}
        onConfirm={handleComplete} completeForm={completeForm}
        setCompleteForm={setCompleteForm} loading={actionLoading}
        onUploadLicense={handleLicenseUpload}
        licenseUploaded={licenseUploadedLocal || files.some((f) => f.fileType === 'LICENSE_PDF')}
        parsing={parsingLicense}
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

      {/* ★ Concierge 강화 PR-4 — Manual Payment 모달.
          ADMIN/SYSTEM_ADMIN 만 진입 가능하도록 사이드바에서 isAdmin 가드 — 본 모달은 단지 마운트. */}
      <ManualPaymentModal
        isOpen={showManualPaymentModal}
        onClose={() => setShowManualPaymentModal(false)}
        onSubmit={handleRecordManualPayment}
        contextLabel={`Application #${application.applicationSeq}`}
        recipientName={
          application.userFirstName || application.userLastName
            ? `${application.userFirstName ?? ''} ${application.userLastName ?? ''}`.trim()
            : application.userEmail
        }
        expectedAmount={application.quoteAmount}
        loading={manualPaymentLoading}
      />
    </div>
  );
}
