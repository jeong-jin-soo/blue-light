import { useCallback, useEffect, useMemo, useState } from 'react';
import documentApi from '../../api/documentApi';
import fileApi from '../../api/fileApi';
import { useToastStore } from '../../stores/toastStore';
import type { DocumentRequest, DocumentType } from '../../types/document';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';
import { Card, CardHeader } from '../ui/Card';
import { ConfirmDialog } from '../ui/ConfirmDialog';
import { LoadingSpinner } from '../ui/LoadingSpinner';
import { DocumentRequestModal } from './DocumentRequestModal';
import { RejectReasonModal } from './RejectReasonModal';
import { formatBytes } from './documentUtils';

/**
 * Phase 3 PR#2 — LEW/ADMIN 신청 상세의 "서류 요청" 섹션 (AC-LU3)
 *
 * - 상단 우측 "+ 서류 요청" 버튼 (DocumentRequestModal 오픈)
 * - 요청 목록 카드:
 *   · REQUESTED: [Cancel Request]
 *   · UPLOADED: 파일명 + [Reject] + [Approve]
 *   · APPROVED / REJECTED: 읽기 전용
 *   · CANCELLED: 섹션에서 제외 (fetch 시 필터)
 * - Approve 낙관적 업데이트 → 실패 시 롤백 + Toast
 * - Reject 클릭 → RejectReasonModal 열기
 * - Cancel 클릭 → ConfirmDialog 확인 후 API 호출
 *
 * Phase 2의 DocumentUploadSection(자발적 업로드)과는 별개로 노출된다 — "서류 요청" 워크플로 전용.
 */

interface LewDocumentReviewSectionProps {
  applicationSeq: number;
  /** 버튼 권한 가드 — ADMIN 또는 assigned LEW 이외에는 모달 트리거 미노출 */
  canRequest: boolean;
  applicantDisplayName?: string;
  applicationCode?: string;
}

export function LewDocumentReviewSection({
  applicationSeq,
  canRequest,
  applicantDisplayName,
  applicationCode,
}: LewDocumentReviewSectionProps) {
  const toast = useToastStore();

  const [catalog, setCatalog] = useState<DocumentType[]>([]);
  const [requests, setRequests] = useState<DocumentRequest[]>([]);
  const [loading, setLoading] = useState(true);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [rejectTarget, setRejectTarget] = useState<DocumentRequest | null>(null);
  const [cancelTarget, setCancelTarget] = useState<DocumentRequest | null>(null);
  const [cancelLoading, setCancelLoading] = useState(false);
  const [approvingId, setApprovingId] = useState<number | null>(null);

  const catalogByCode = useMemo(() => {
    const map = new Map<string, DocumentType>();
    for (const dt of catalog) map.set(dt.code, dt);
    return map;
  }, [catalog]);

  const fetchAll = useCallback(async () => {
    try {
      const [catalogData, requestData] = await Promise.all([
        documentApi.getDocumentTypes(),
        documentApi.getDocumentRequests(applicationSeq),
      ]);
      setCatalog(catalogData);
      setRequests(requestData);
    } catch (err) {
      const msg = (err as { message?: string })?.message ?? 'Failed to load document requests';
      toast.error(msg);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applicationSeq]);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  // CANCELLED는 UI에서 제거 (AC-S5 + §2 LEW 섹션 설계)
  const visibleRequests = useMemo(
    () => requests.filter((r) => r.status !== 'CANCELLED'),
    [requests],
  );

  const activeRequests = useMemo(
    () => requests.filter((r) => r.status === 'REQUESTED' || r.status === 'UPLOADED'),
    [requests],
  );

  const handleApprove = async (req: DocumentRequest) => {
    // 낙관적 업데이트
    const prev = requests;
    setApprovingId(req.id);
    setRequests((list) =>
      list.map((r) =>
        r.id === req.id
          ? {
              ...r,
              status: 'APPROVED',
              reviewedAt: new Date().toISOString(),
            }
          : r,
      ),
    );
    try {
      await documentApi.approveDocumentRequest(req.id);
      toast.success('승인되었습니다 · Approved');
      // 서버 상태로 refresh (reviewedBy 등 메타 정확화)
      fetchAll();
    } catch (err) {
      // 롤백
      setRequests(prev);
      const msg =
        (err as { message?: string })?.message ??
        '승인에 실패했습니다. 다시 시도해 주세요. · Failed to approve.';
      toast.error(msg);
    } finally {
      setApprovingId(null);
    }
  };

  const handleRejectSubmit = async (reason: string) => {
    if (!rejectTarget) return;
    try {
      await documentApi.rejectDocumentRequest(rejectTarget.id, reason);
      toast.success('반려되었습니다 · Rejected');
      setRejectTarget(null);
      fetchAll();
    } catch (err) {
      const msg =
        (err as { message?: string })?.message ??
        '반려 처리에 실패했습니다. · Failed to reject.';
      toast.error(msg);
      // 모달은 유지해서 재시도 가능하게
      throw err;
    }
  };

  const handleCancelConfirm = async () => {
    if (!cancelTarget) return;
    setCancelLoading(true);
    try {
      await documentApi.cancelDocumentRequest(cancelTarget.id);
      toast.success('요청이 취소되었습니다 · Request cancelled');
      setCancelTarget(null);
      fetchAll();
    } catch (err) {
      const msg =
        (err as { message?: string })?.message ??
        '취소에 실패했습니다. · Failed to cancel request.';
      toast.error(msg);
    } finally {
      setCancelLoading(false);
    }
  };

  const handleDownload = async (req: DocumentRequest) => {
    if (!req.fulfilledFileSeq) return;
    try {
      await fileApi.downloadFile(
        req.fulfilledFileSeq,
        req.fulfilledFilename ?? `document-${req.id}`,
      );
    } catch {
      toast.error('다운로드에 실패했습니다. · Failed to download file.');
    }
  };

  return (
    <>
      <Card id="doc-requests">
        <div className="flex items-start justify-between gap-3 mb-4">
          <CardHeader
            title="서류 요청 · Document Requests"
            description={`LEW 요청 워크플로 — ${visibleRequests.length}건`}
          />
          {canRequest && (
            <Button
              size="sm"
              onClick={() => setShowCreateModal(true)}
              leftIcon={<span aria-hidden>＋</span>}
            >
              서류 요청 · Request Documents
            </Button>
          )}
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-10">
            <LoadingSpinner size="md" label="Loading document requests..." />
          </div>
        ) : visibleRequests.length === 0 ? (
          <div className="text-center py-8 text-sm text-gray-500 border border-dashed border-gray-200 rounded-lg">
            <span className="text-3xl block mb-2" aria-hidden>
              📋
            </span>
            아직 요청한 서류가 없습니다.
            <br />
            No document requests yet.
          </div>
        ) : (
          <ul className="space-y-3">
            {visibleRequests.map((req) => (
              <li key={req.id}>
                <LewRequestRow
                  request={req}
                  documentType={catalogByCode.get(req.documentTypeCode) ?? null}
                  approving={approvingId === req.id}
                  onApprove={() => handleApprove(req)}
                  onReject={() => setRejectTarget(req)}
                  onCancel={() => setCancelTarget(req)}
                  onDownload={() => handleDownload(req)}
                />
              </li>
            ))}
          </ul>
        )}
      </Card>

      {showCreateModal && (
        <DocumentRequestModal
          isOpen={showCreateModal}
          applicationSeq={applicationSeq}
          applicantDisplayName={applicantDisplayName}
          applicationCode={applicationCode}
          existingActiveRequests={activeRequests}
          onClose={() => setShowCreateModal(false)}
          onSuccess={() => fetchAll()}
        />
      )}

      <RejectReasonModal
        isOpen={rejectTarget !== null}
        requestId={rejectTarget?.id ?? null}
        documentLabel={
          rejectTarget
            ? rejectTarget.customLabel ??
              catalogByCode.get(rejectTarget.documentTypeCode)?.labelKo ??
              rejectTarget.documentTypeCode
            : undefined
        }
        onClose={() => setRejectTarget(null)}
        onSubmit={handleRejectSubmit}
      />

      <ConfirmDialog
        isOpen={cancelTarget !== null}
        onClose={() => (cancelLoading ? undefined : setCancelTarget(null))}
        onConfirm={handleCancelConfirm}
        title="요청 취소 · Cancel request"
        message={
          cancelTarget
            ? `요청 #${cancelTarget.id}을(를) 취소할까요? 신청자 화면에서도 사라집니다.\nCancel request #${cancelTarget.id}? The applicant will no longer see it.`
            : ''
        }
        confirmLabel="요청 취소 · Cancel Request"
        cancelLabel="되돌아가기 · Keep"
        variant="danger"
        loading={cancelLoading}
      />
    </>
  );
}

// ─────────────────────────────────────────────
// LEW Request Row — status별 variant 조립
// ─────────────────────────────────────────────

const variantStyle: Record<string, { border: string; bg: string }> = {
  REQUESTED: { border: 'border-warning-500/40', bg: 'bg-warning-50' },
  UPLOADED: { border: 'border-info-500/40', bg: 'bg-info-50' },
  APPROVED: { border: 'border-success-500/40', bg: 'bg-success-50' },
  REJECTED: { border: 'border-error-500/40', bg: 'bg-error-50' },
};

function LewRequestRow({
  request,
  documentType,
  approving,
  onApprove,
  onReject,
  onCancel,
  onDownload,
}: {
  request: DocumentRequest;
  documentType: DocumentType | null;
  approving: boolean;
  onApprove: () => void;
  onReject: () => void;
  onCancel: () => void;
  onDownload: () => void;
}) {
  const style = variantStyle[request.status] ?? { border: 'border-gray-200', bg: 'bg-surface' };
  const label =
    request.customLabel ??
    documentType?.labelKo ??
    documentType?.code ??
    request.documentTypeCode;

  const statusBadge = (() => {
    switch (request.status) {
      case 'REQUESTED':
        return (
          <Badge variant="warning" dot>
            요청됨 · Requested
          </Badge>
        );
      case 'UPLOADED':
        return (
          <Badge variant="info" dot>
            검토 대기 · Under Review
          </Badge>
        );
      case 'APPROVED':
        return (
          <Badge variant="success" dot>
            승인됨 · Approved
          </Badge>
        );
      case 'REJECTED':
        return (
          <Badge variant="error" dot>
            반려됨 · Rejected
          </Badge>
        );
      default:
        return <Badge variant="gray">{request.status}</Badge>;
    }
  })();

  const requestedAt = request.requestedAt
    ? new Date(request.requestedAt).toLocaleString()
    : '';

  return (
    <div
      id={`doc-req-${request.id}`}
      className={`rounded-lg border p-4 transition-colors ${style.border} ${style.bg}`}
    >
      <div className="flex items-start justify-between gap-3 mb-2 flex-wrap">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-xl flex-shrink-0" aria-hidden>
            {documentType?.iconEmoji ?? '📎'}
          </span>
          <div className="min-w-0">
            <p className="text-sm font-semibold text-gray-900 truncate">
              #{request.id} · {label}
            </p>
            {requestedAt && (
              <p className="text-xs text-gray-500 mt-0.5">Requested {requestedAt}</p>
            )}
          </div>
        </div>
        {statusBadge}
      </div>

      {request.lewNote && (
        <blockquote className="border-l-2 border-warning-500 pl-3 text-sm text-gray-700 italic mb-2">
          "{request.lewNote}"
        </blockquote>
      )}

      {request.status === 'UPLOADED' && request.fulfilledFilename && (
        <div className="text-xs text-gray-700 mb-2 flex items-center gap-2 flex-wrap">
          <span className="font-medium">{request.fulfilledFilename}</span>
          {request.fulfilledFileSize != null && (
            <span className="text-gray-500">· {formatBytes(request.fulfilledFileSize)}</span>
          )}
          {request.fulfilledAt && (
            <span className="text-gray-500">
              · {new Date(request.fulfilledAt).toLocaleString()}
            </span>
          )}
        </div>
      )}

      {request.status === 'REJECTED' && request.rejectionReason && (
        <div className="bg-surface border-l-2 border-error-500 rounded p-3 mb-2">
          <p className="text-xs font-medium text-gray-500 mb-1">
            반려 사유 · Rejection reason
          </p>
          <p className="text-sm text-gray-700 whitespace-pre-wrap">
            {request.rejectionReason}
          </p>
          <p className="text-xs text-gray-500 mt-1">
            신청자의 재업로드를 기다립니다. · Awaiting applicant re-upload.
          </p>
        </div>
      )}

      {request.status === 'APPROVED' && (
        <p className="text-xs text-success-700 flex items-center gap-1 mb-2">
          <svg
            className="w-4 h-4"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            viewBox="0 0 24 24"
            aria-hidden
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
          </svg>
          승인되었습니다 · Approved
          {request.reviewedAt && (
            <span className="text-gray-500 ml-1">
              · {new Date(request.reviewedAt).toLocaleString()}
            </span>
          )}
        </p>
      )}

      {/* 액션 버튼 — 불법 전이 버튼은 렌더링하지 않음 (AC-S5/S6 사전 차단) */}
      <div className="flex justify-end gap-2 mt-3 flex-wrap">
        {(request.status === 'UPLOADED' || request.status === 'APPROVED') &&
          request.fulfilledFileSeq && (
            <Button size="sm" variant="ghost" onClick={onDownload}>
              다운로드 · Download
            </Button>
          )}
        {request.status === 'REQUESTED' && (
          <Button size="sm" variant="ghost" onClick={onCancel}>
            요청 취소 · Cancel Request
          </Button>
        )}
        {request.status === 'UPLOADED' && (
          <>
            <Button
              size="sm"
              variant="outline"
              className="text-error-700 border-error-500/40 hover:bg-error-50"
              onClick={onReject}
              disabled={approving}
            >
              반려 · Reject
            </Button>
            <Button size="sm" onClick={onApprove} loading={approving}>
              승인 · Approve ✓
            </Button>
          </>
        )}
      </div>
    </div>
  );
}

export default LewDocumentReviewSection;
