/**
 * ConciergeRequestDetailPage
 * - Kaki Concierge v1.5 Phase 1 PR#4 Stage B
 * - /concierge-manager/requests/:id
 * - 상세 뷰: Breadcrumb + 상태 헤더 + 2컬럼(좌: Timeline/AccountStatus, 우: Notes + ActionBar)
 */

import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { ConciergeStatusBadge } from '../../components/concierge/ConciergeStatusBadge';
import ConciergeTimeline from './sections/ConciergeTimeline';
import ConciergeNotesPanel from './sections/ConciergeNotesPanel';
import ConciergeAccountStatusPanel from './sections/ConciergeAccountStatusPanel';
import ConciergeActionBar from './sections/ConciergeActionBar';
import ConciergeCreateApplicationModal from './sections/ConciergeCreateApplicationModal';
import ConciergeLoaCollectionPanel from './sections/ConciergeLoaCollectionPanel';
import conciergeManagerApi, {
  type ConciergeRequestDetail,
  type ConciergeStatus,
  type NoteChannel,
} from '../../api/conciergeManagerApi';
// ★ Concierge 강화 PR-4 — Manual payment + LEW 배정 모달 + 영수증 이력 카드.
import { ConciergeManualPaymentModal } from '../../components/concierge-manager/ConciergeManualPaymentModal';
import { AssignLewModal } from '../../components/concierge-manager/AssignLewModal';
import { useToastStore } from '../../stores/toastStore';
import type { ManualPaymentPayload } from '../../types/manualPayment';
import type { AssignLewRequestPayload } from '../../types/concierge';

function errMsg(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'message' in err) {
    return String((err as { message: unknown }).message);
  }
  return fallback;
}

export default function ConciergeRequestDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const toast = useToastStore();
  const [detail, setDetail] = useState<ConciergeRequestDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // ★ PR#5 Stage B: Create Application 모달 상태
  const [createAppOpen, setCreateAppOpen] = useState(false);
  // ★ Concierge 강화 PR-4 — LEW 배정 + 별도 수금 모달 상태.
  const [assignLewOpen, setAssignLewOpen] = useState(false);
  const [assignLewLoading, setAssignLewLoading] = useState(false);
  const [manualPaymentOpen, setManualPaymentOpen] = useState(false);
  const [manualPaymentLoading, setManualPaymentLoading] = useState(false);

  const reload = useCallback(async () => {
    if (!id) return;
    try {
      setLoading(true);
      setError(null);
      const data = await conciergeManagerApi.getDetail(Number(id));
      setDetail(data);
    } catch (err) {
      setError(errMsg(err, 'Failed to load request'));
      setDetail(null);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    reload();
  }, [reload]);

  const handleTransition = async (nextStatus: ConciergeStatus) => {
    if (!detail) return;
    const updated = await conciergeManagerApi.transitionStatus(
      detail.conciergeRequestSeq,
      { nextStatus }
    );
    setDetail(updated);
  };

  const handleAddNote = async (channel: NoteChannel, content: string) => {
    if (!detail) return;
    await conciergeManagerApi.addNote(detail.conciergeRequestSeq, {
      channel,
      content,
    });
    // 노트 추가 후 상세 재조회 (firstContactAt/status 변경 가능성)
    await reload();
  };

  const handleResend = async () => {
    if (!detail) return;
    await conciergeManagerApi.resendSetupEmail(detail.conciergeRequestSeq);
    await reload();
  };

  const handleCancel = async (reason: string) => {
    if (!detail) return;
    const updated = await conciergeManagerApi.cancel(detail.conciergeRequestSeq, {
      reason,
    });
    setDetail(updated);
  };

  const handleSendQuote = async (payload: {
    quotedAmount: number;
    callScheduledAt?: string | null;
    note?: string | null;
  }) => {
    if (!detail) return;
    const updated = await conciergeManagerApi.sendQuote(
      detail.conciergeRequestSeq,
      payload,
    );
    setDetail(updated);
  };

  // ★ Concierge 강화 PR-4 — LEW 배정/재배정 핸들러.
  // 스펙 §5.3, AC-L1~L4. selfAssigned=true 면 toast 메시지를 다르게 표시.
  const handleAssignLew = async (payload: AssignLewRequestPayload) => {
    if (!detail) return;
    setAssignLewLoading(true);
    // finally 블록에서 로딩 해제 — 에러는 모달이 자체 errMsg 로 표시하도록 자연 propagate.
    try {
      const response = await conciergeManagerApi.assignLew(detail.conciergeRequestSeq, payload);
      if (response.selfAssigned) {
        toast.success('You assigned this request to yourself.');
      } else if (response.previousLewSeq) {
        toast.success(`Request reassigned to ${response.assignedLewName}.`);
      } else {
        toast.success(`Assigned to ${response.assignedLewName}. LEW notified.`);
      }
      setAssignLewOpen(false);
      await reload();
    } finally {
      setAssignLewLoading(false);
    }
  };

  // ★ Concierge 강화 PR-4 — 별도 수금 핸들러.
  // 스펙 §7.3, AC-A4. AFTER_COMMIT 으로 영수증 발행 + 이메일.
  const handleRecordManualPayment = async (payload: ManualPaymentPayload) => {
    if (!detail) return;
    setManualPaymentLoading(true);
    // finally 블록에서 로딩 해제 — 에러는 모달이 자체 errMsg 로 표시하도록 자연 propagate.
    try {
      const response = await conciergeManagerApi.recordManualPayment(
        detail.conciergeRequestSeq,
        payload,
      );
      const issuedNote = payload.receiptIssue !== false
        ? response.invoiceNumber
          ? ` Receipt ${response.invoiceNumber} issued.`
          : ' Receipt will be issued shortly.'
        : '';
      toast.success(`Payment of SGD ${Number(payload.amount).toFixed(2)} recorded.${issuedNote}`);
      setManualPaymentOpen(false);
      await reload();
    } finally {
      setManualPaymentLoading(false);
    }
  };

  if (!id) {
    return (
      <div className="max-w-6xl mx-auto">
        <p className="text-sm text-error-700">Invalid request id.</p>
      </div>
    );
  }

  if (loading && !detail) {
    return (
      <div className="max-w-6xl mx-auto">
        <p className="text-sm text-gray-500">Loading...</p>
      </div>
    );
  }

  if (error && !detail) {
    return (
      <div className="max-w-6xl mx-auto">
        <div
          role="alert"
          className="p-3 rounded-md bg-error-50 border border-error-200 text-sm text-error-700"
        >
          {error}
        </div>
        <div className="mt-3">
          <Button variant="outline" size="sm" onClick={() => navigate('/concierge-manager/requests')}>
            Back to list
          </Button>
        </div>
      </div>
    );
  }

  if (!detail) return null;

  return (
    <div className="max-w-6xl mx-auto">
      {/* Breadcrumb */}
      <nav aria-label="Breadcrumb" className="mb-3 text-sm">
        <ol className="flex items-center gap-1.5 text-gray-500">
          <li>
            <Link
              to="/concierge-manager/dashboard"
              className="hover:text-gray-800"
            >
              Dashboard
            </Link>
          </li>
          <li aria-hidden="true">/</li>
          <li>
            <Link
              to="/concierge-manager/requests"
              className="hover:text-gray-800"
            >
              Requests
            </Link>
          </li>
          <li aria-hidden="true">/</li>
          <li className="text-gray-800 font-mono">{detail.publicCode}</li>
        </ol>
      </nav>

      {/* 상태 헤더 */}
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-gray-900">
            {detail.submitterName}
          </h1>
          <div className="mt-1 text-sm text-gray-600 space-x-3">
            <span className="font-mono text-gray-500">{detail.publicCode}</span>
            <span className="break-all">{detail.submitterEmail}</span>
            <span>{detail.submitterPhone}</span>
          </div>
        </div>
        <div className="flex flex-col items-end gap-1.5">
          <ConciergeStatusBadge status={detail.status} />
          {detail.slaBreached && <Badge variant="error">SLA Breach</Badge>}
        </div>
      </div>

      {/* Memo (있을 때만) */}
      {detail.memo && (
        <Card padding="md" className="mb-4 bg-gray-50">
          <div className="text-xs font-medium text-gray-500 mb-1">Memo</div>
          <p className="text-sm text-gray-800 whitespace-pre-wrap break-words">
            {detail.memo}
          </p>
        </Card>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* 좌측: Timeline + Applicant status */}
        <div className="lg:col-span-1 space-y-4">
          <Card padding="md">
            <h2 className="text-sm font-semibold text-gray-800 mb-3">Timeline</h2>
            <ConciergeTimeline detail={detail} />
          </Card>

          <Card padding="md">
            <h2 className="text-sm font-semibold text-gray-800 mb-3">Applicant</h2>
            <ConciergeAccountStatusPanel
              applicantStatus={detail.applicantStatus}
              onResend={handleResend}
            />
          </Card>

          {detail.applicationSeq && (
            <Card padding="md">
              <h2 className="text-sm font-semibold text-gray-800 mb-2">
                Linked application
              </h2>
              <p className="text-sm text-gray-700">
                Application #{detail.applicationSeq}
              </p>
            </Card>
          )}

          {/* ★ Concierge 강화 PR-3/PR-4 — LEW 배정 패널 (CONTACTING/QUOTE_SENT/APPLICATION_CREATED/LEW_ASSIGNED 에서 노출).
              스펙 §5.3, AC-L1~L4. ADMIN/매니저 전용 — 본 페이지는 매니저 워크스페이스이므로 항상 표시 가능. */}
          {(detail.status === 'CONTACTING'
            || detail.status === 'QUOTE_SENT'
            || detail.status === 'APPLICATION_CREATED'
            || detail.status === 'LEW_ASSIGNED') && (
            <Card padding="md">
              <h2 className="text-sm font-semibold text-gray-800 mb-2">
                LEW assignment
              </h2>
              {detail.assignedLewSeq ? (
                <div className="space-y-2 text-sm">
                  <div className="rounded-md bg-primary-50 border border-primary-100 p-2">
                    <p className="font-medium text-primary-800">
                      {detail.assignedLewName ?? `LEW #${detail.assignedLewSeq}`}
                    </p>
                    {detail.assignedLewEmail && (
                      <p className="text-xs text-primary-700 break-all">{detail.assignedLewEmail}</p>
                    )}
                    {detail.lewAssignedAt && (
                      <p className="text-xs text-primary-600 mt-1">
                        Assigned {new Date(detail.lewAssignedAt).toLocaleString()}
                      </p>
                    )}
                  </div>
                  <Button variant="outline" size="sm" fullWidth onClick={() => setAssignLewOpen(true)}>
                    Reassign LEW
                  </Button>
                </div>
              ) : (
                <div>
                  <p className="text-sm text-gray-600 mb-2">No LEW assigned yet.</p>
                  <Button variant="outline" size="sm" fullWidth onClick={() => setAssignLewOpen(true)}>
                    Assign LEW
                  </Button>
                </div>
              )}
            </Card>
          )}

          {/* ★ Concierge 강화 PR-4 — 별도 수금 패널 (CANCELLED 외 모든 상태).
              스펙 §7.3, AC-A4. CANCELLED 와 COMPLETED 는 백엔드 거부 — UI 도 노출 안함. */}
          {detail.status !== 'CANCELLED' && detail.status !== 'COMPLETED' && (
            <Card padding="md">
              <h2 className="text-sm font-semibold text-gray-800 mb-2">
                Offline payment
              </h2>
              <p className="text-xs text-gray-500 mb-2">
                Record bank transfer / PayNow QR / cash payments received outside the platform.
                A receipt PDF is auto-generated and emailed to the applicant.
              </p>
              <Button variant="outline" size="sm" fullWidth onClick={() => setManualPaymentOpen(true)}>
                💰 Record manual payment
              </Button>
            </Card>
          )}

          {/* ★ PR#6 Stage B: LOA 서명 수집 패널 */}
          <ConciergeLoaCollectionPanel
            applicationSeq={detail.applicationSeq}
            onChange={() => {
              void reload();
            }}
          />
        </div>

        {/* 우측: ActionBar + Notes */}
        <div className="lg:col-span-2 space-y-4">
          <Card padding="md">
            <h2 className="text-sm font-semibold text-gray-800 mb-3">Actions</h2>
            <ConciergeActionBar
              detail={detail}
              onTransition={handleTransition}
              onCancel={handleCancel}
              onCreateApplication={() => setCreateAppOpen(true)}
              onSendQuote={handleSendQuote}
            />
          </Card>

          <Card padding="md">
            <h2 className="text-sm font-semibold text-gray-800 mb-3">Notes</h2>
            <ConciergeNotesPanel notes={detail.notes} onAdd={handleAddNote} />
          </Card>
        </div>
      </div>

      {/* ★ PR#5 Stage B: 대리 Application 생성 모달 */}
      <ConciergeCreateApplicationModal
        conciergeRequestSeq={detail.conciergeRequestSeq}
        submitterName={detail.submitterName}
        isOpen={createAppOpen}
        onClose={() => setCreateAppOpen(false)}
        onCreated={() => {
          setCreateAppOpen(false);
          // 상태/타임라인/applicationSeq 등 업데이트 반영
          void reload();
        }}
      />

      {/* ★ Concierge 강화 PR-4 — LEW 배정 모달 */}
      <AssignLewModal
        isOpen={assignLewOpen}
        onClose={() => setAssignLewOpen(false)}
        onSubmit={handleAssignLew}
        currentAssigneeName={detail.assignedLewName}
        currentAssigneeSeq={detail.assignedLewSeq}
        loading={assignLewLoading}
      />

      {/* ★ Concierge 강화 PR-4 — Manual Payment 모달 */}
      <ConciergeManualPaymentModal
        isOpen={manualPaymentOpen}
        onClose={() => setManualPaymentOpen(false)}
        onSubmit={handleRecordManualPayment}
        publicCode={detail.publicCode}
        submitterName={detail.submitterName}
        quotedAmount={detail.quotedAmount}
        loading={manualPaymentLoading}
      />
    </div>
  );
}
