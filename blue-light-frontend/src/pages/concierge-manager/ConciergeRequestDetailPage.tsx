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

function errMsg(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'message' in err) {
    return String((err as { message: unknown }).message);
  }
  return fallback;
}

export default function ConciergeRequestDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ConciergeRequestDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // ★ PR#5 Stage B: Create Application 모달 상태
  const [createAppOpen, setCreateAppOpen] = useState(false);

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
    </div>
  );
}
