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
import { formatBytes } from './documentUtils';

/**
 * Phase 3 PR#2 — LEW/ADMIN 신청 상세의 "서류 요청" 섹션 (AC-LU3)
 *
 * - 상단 우측 "+ 서류 요청" 버튼 (DocumentRequestModal 오픈)
 * - 요청 목록 카드 (LEW 승인/반려 단계 제거 — 2026-06-18):
 *   · REQUESTED: [Cancel Request] (+ ADMIN 대리 업로드)
 *   · UPLOADED: "Received" + 파일명 + [Download]
 *   · CANCELLED: 섹션에서 제외 (fetch 시 필터)
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
  /**
   * 서류 요청 상태(승인/반려/취소/생성)가 변동될 때 호출.
   * 부모(LewReviewFormPage)가 pendingDocCount 가드 등 파생 상태를 갱신하도록
   * loadData 를 연결한다. 이게 없으면 결제 요청 가드(pendingDocCount) 가 stale 해진다.
   */
  onRequestsChanged?: () => void;
  /** ADMIN 이 신청자 대신 서류를 업로드(fulfill)할 수 있는지 (admin parity). LEW 는 fulfill 불가. */
  canFulfill?: boolean;
}

export function LewDocumentReviewSection({
  applicationSeq,
  canRequest,
  applicantDisplayName,
  applicationCode,
  onRequestsChanged,
  canFulfill = false,
}: LewDocumentReviewSectionProps) {
  const toast = useToastStore();

  const [catalog, setCatalog] = useState<DocumentType[]>([]);
  const [requests, setRequests] = useState<DocumentRequest[]>([]);
  const [loading, setLoading] = useState(true);

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [cancelTarget, setCancelTarget] = useState<DocumentRequest | null>(null);
  const [cancelLoading, setCancelLoading] = useState(false);
  const [fulfillingId, setFulfillingId] = useState<number | null>(null);

  // ADMIN 대리 업로드(fulfill) — REQUESTED 요청에 신청자 대신 파일 업로드.
  const handleFulfill = async (req: DocumentRequest, file: File) => {
    setFulfillingId(req.id);
    try {
      await documentApi.fulfillDocumentRequest(applicationSeq, req.id, file);
      toast.success('Uploaded on behalf of the applicant.');
      fetchAll();
      onRequestsChanged?.();
    } catch (err) {
      const msg = (err as { message?: string })?.message ?? 'Failed to upload on behalf.';
      toast.error(msg);
    } finally {
      setFulfillingId(null);
    }
  };

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

  // handleApprove / handleRejectSubmit 제거됨 (2026-06-18 — LEW 승인/반려 단계 폐지).

  const handleCancelConfirm = async () => {
    if (!cancelTarget) return;
    setCancelLoading(true);
    try {
      await documentApi.cancelDocumentRequest(cancelTarget.id);
      toast.success('Request cancelled');
      setCancelTarget(null);
      fetchAll();
      onRequestsChanged?.();  // 부모 가드(pendingDocCount) 갱신
    } catch (err) {
      const msg =
        (err as { message?: string })?.message ??
        'Failed to cancel request.';
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
      toast.error('Failed to download file.');
    }
  };

  return (
    <>
      <Card id="doc-requests">
        <div className="flex items-start justify-between gap-3 mb-4">
          <CardHeader
            title="Document Requests"
            description={`LEW request workflow — ${visibleRequests.length} item${visibleRequests.length === 1 ? '' : 's'}`}
          />
          {canRequest && (
            <Button
              size="sm"
              onClick={() => setShowCreateModal(true)}
              leftIcon={<span aria-hidden>＋</span>}
            >
              Request Documents
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
            No document requests yet.
          </div>
        ) : (
          <ul className="space-y-3">
            {visibleRequests.map((req) => (
              <li key={req.id}>
                <LewRequestRow
                  request={req}
                  documentType={catalogByCode.get(req.documentTypeCode) ?? null}
                  onCancel={() => setCancelTarget(req)}
                  onDownload={() => handleDownload(req)}
                  canFulfill={canFulfill}
                  fulfilling={fulfillingId === req.id}
                  onFulfill={(file) => handleFulfill(req, file)}
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
          onSuccess={() => {
            fetchAll();
            onRequestsChanged?.();  // 새 요청 생성 → 부모 가드(pendingDocCount) 갱신
          }}
        />
      )}

      <ConfirmDialog
        isOpen={cancelTarget !== null}
        onClose={() => (cancelLoading ? undefined : setCancelTarget(null))}
        onConfirm={handleCancelConfirm}
        title="Cancel request"
        message={
          cancelTarget
            ? `Cancel request #${cancelTarget.id}? The applicant will no longer see it.`
            : ''
        }
        confirmLabel="Cancel Request"
        cancelLabel="Keep"
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
  UPLOADED: { border: 'border-success-500/40', bg: 'bg-success-50' },
};

function LewRequestRow({
  request,
  documentType,
  onCancel,
  onDownload,
  canFulfill,
  fulfilling,
  onFulfill,
}: {
  request: DocumentRequest;
  documentType: DocumentType | null;
  onCancel: () => void;
  onDownload: () => void;
  canFulfill: boolean;
  fulfilling: boolean;
  onFulfill: (file: File) => void;
}) {
  const style = variantStyle[request.status] ?? { border: 'border-gray-200', bg: 'bg-surface' };
  const label =
    request.customLabel ??
    documentType?.labelEn ??
    documentType?.code ??
    request.documentTypeCode;

  const statusBadge = (() => {
    switch (request.status) {
      case 'REQUESTED':
        return (
          <Badge variant="warning" dot>
            Requested
          </Badge>
        );
      case 'UPLOADED':
        return (
          <Badge variant="success" dot>
            Received
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

      {/* 액션 버튼 — 불법 전이 버튼은 렌더링하지 않음. 승인/반려 단계 제거(2026-06-18). */}
      <div className="flex justify-end gap-2 mt-3 flex-wrap">
        {request.status === 'UPLOADED' && request.fulfilledFileSeq && (
          <Button size="sm" variant="ghost" onClick={onDownload}>
            Download
          </Button>
        )}
        {request.status === 'REQUESTED' && (
          <Button size="sm" variant="ghost" onClick={onCancel}>
            Cancel Request
          </Button>
        )}
        {/* ADMIN 대리 업로드 — REQUESTED 요청에 신청자 대신 파일 첨부 (admin parity) */}
        {canFulfill && request.status === 'REQUESTED' && (
          <label className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg border border-primary/40 text-primary cursor-pointer hover:bg-primary-50 transition-colors ${fulfilling ? 'opacity-50 pointer-events-none' : ''}`}>
            {fulfilling ? 'Uploading…' : '↥ Upload on behalf'}
            <input
              type="file"
              accept={documentType?.acceptedMime ?? '.pdf,.jpg,.jpeg,.png'}
              className="hidden"
              disabled={fulfilling}
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) onFulfill(f);
                e.target.value = '';
              }}
            />
          </label>
        )}
      </div>
    </div>
  );
}

export default LewDocumentReviewSection;
