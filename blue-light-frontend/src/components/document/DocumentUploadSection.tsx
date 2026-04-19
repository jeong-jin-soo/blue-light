import { useCallback, useEffect, useMemo, useState } from 'react';
import documentApi from '../../api/documentApi';
import { useToastStore } from '../../stores/toastStore';
import type { DocumentRequest, DocumentType } from '../../types/document';
import { Card, CardHeader } from '../ui/Card';
import { ConfirmDialog } from '../ui/ConfirmDialog';
import { InfoBox } from '../ui/InfoBox';
import { LoadingSpinner } from '../ui/LoadingSpinner';
import { DocumentRequestBanner } from './DocumentRequestBanner';
import { DocumentRequestCard, type DocumentRequestCardVariant } from './DocumentRequestCard';
import { formatBytes } from './documentUtils';

interface DocumentUploadSectionProps {
  applicationSeq: number;
  /** 신청자(APPLICANT)만 자발적 업로드 가능. LEW/ADMIN은 읽기 전용. */
  canUpload: boolean;
}

/**
 * 신청 상세 페이지 "서류" 섹션 컨테이너
 *
 * Phase 2 (AC-U1~U4):
 *   1. InfoBox ("업로드는 선택")
 *   2. 자발적 업로드 카드 (DocumentRequestCard variant=neutral)
 *   3. 업로드된 자발적 파일 목록
 *
 * Phase 3 PR#3 (AC-AU1/AU2/AU4, AC-S1/S4):
 *   0. DocumentRequestBanner (상단, REQUESTED/REJECTED ≥ 1건일 때)
 *   ├─ 요청 서류 카드 목록 (LEW가 요청한 것만 — 4 variant)
 *   ├─ (요청 있을 때는 InfoBox 축약 / 없을 때는 유지)
 *   ├─ 자발적 업로드 카드 (Phase 2)
 *   └─ 자발적 업로드 파일 목록
 */
export function DocumentUploadSection({
  applicationSeq,
  canUpload,
}: DocumentUploadSectionProps) {
  const toast = useToastStore();

  const [catalog, setCatalog] = useState<DocumentType[]>([]);
  const [requests, setRequests] = useState<DocumentRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<DocumentRequest | null>(null);
  const [deleting, setDeleting] = useState(false);

  const showDevMockups = useMemo(() => {
    if (typeof window === 'undefined') return false;
    return new URLSearchParams(window.location.search).get('devMockups') === '1';
  }, []);

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
      const msg = (err as { message?: string })?.message ?? 'Failed to load documents';
      toast.error(msg);
    } finally {
      setLoading(false);
    }
    // 의도적으로 toast를 deps에서 제외 (zustand store 참조는 stable)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applicationSeq]);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  const handleUpload = async ({
    documentTypeCode,
    customLabel,
    file,
  }: {
    documentTypeCode: string;
    customLabel?: string;
    file: File;
  }) => {
    setUploading(true);
    try {
      await documentApi.uploadVoluntaryDocument(applicationSeq, {
        documentTypeCode,
        customLabel,
        file,
      });
      toast.success('Uploaded');
      // 목록 새로고침 (낙관적 업데이트 대신 서버 source of truth 사용)
      const refreshed = await documentApi.getDocumentRequests(applicationSeq);
      setRequests(refreshed);
    } catch (err) {
      const msg =
        (err as { message?: string })?.message ??
        'Upload failed.';
      toast.error(msg);
      throw err; // DocumentRequestCard 내부 에러 표시용
    } finally {
      setUploading(false);
    }
  };

  /**
   * Phase 3 PR#3 — LEW 요청 건에 대한 fulfill/재업로드 (AC-S1, AC-S4, AC-AU4)
   *
   * - 성공 시 서버 응답으로 해당 요청만 교체 (낙관적 업데이트는 응답값으로 대체)
   * - 실패 시 카드 내부 에러로 전파
   */
  const handleReupload = async (requestId: number, file: File) => {
    try {
      const updated = await documentApi.fulfillDocumentRequest(
        applicationSeq,
        requestId,
        file,
      );
      setRequests((prev) => prev.map((r) => (r.id === requestId ? updated : r)));
      toast.success('Uploaded · LEW will be notified');
    } catch (err) {
      const msg =
        (err as { message?: string })?.message ??
        'Upload failed.';
      toast.error(msg);
      throw err;
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await documentApi.deleteDocument(applicationSeq, deleteTarget.id);
      toast.success('Deleted');
      setRequests((prev) => prev.filter((r) => r.id !== deleteTarget.id));
      setDeleteTarget(null);
    } catch (err) {
      const msg = (err as { message?: string })?.message ?? 'Delete failed.';
      toast.error(msg);
    } finally {
      setDeleting(false);
    }
  };

  // ─────────────────────────────────────────────
  // 분류
  //   · LEW가 요청한 것: `requested_at`이 채워진 요청 (REQUESTED/UPLOADED/APPROVED/REJECTED)
  //   · 신청자가 자발 업로드: requested_at 없는 UPLOADED (Phase 2 패턴)
  //   CANCELLED는 신청자 화면에서 제외.
  // ─────────────────────────────────────────────
  const lewRequested = useMemo(
    () =>
      requests.filter(
        (r) => r.status !== 'CANCELLED' && r.requestedAt != null,
      ),
    [requests],
  );
  const voluntaryUploaded = useMemo(
    () =>
      requests.filter((r) => r.status === 'UPLOADED' && r.requestedAt == null),
    [requests],
  );

  const hasActiveRequests = lewRequested.some(
    (r) => r.status === 'REQUESTED' || r.status === 'REJECTED',
  );

  const variantOf = (r: DocumentRequest): DocumentRequestCardVariant => {
    switch (r.status) {
      case 'REQUESTED':
        return 'requested';
      case 'UPLOADED':
        return 'uploaded';
      case 'APPROVED':
        return 'approved';
      case 'REJECTED':
        return 'rejected';
      default:
        return 'requested';
    }
  };

  return (
    <>
      <Card>
        <CardHeader title="Documents" description="Supporting documents" />

        {loading ? (
          <div className="flex items-center justify-center py-10">
            <LoadingSpinner size="md" label="Loading documents..." />
          </div>
        ) : (
          <>
            {/* 상단 경고 배너 (AC-AU1) */}
            {canUpload && hasActiveRequests && (
              <div className="mb-4">
                <DocumentRequestBanner requests={lewRequested} />
              </div>
            )}

            {/* LEW 요청 서류 섹션 (AC-AU2) */}
            {lewRequested.length > 0 && (
              <section id="doc-requests" className="mb-8">
                <div className="flex items-center justify-between mb-3">
                  <h3
                    className="text-sm font-semibold text-gray-800"
                    tabIndex={-1}
                  >
                    Requested by LEW{' '}
                    <span className="text-gray-500 font-normal">({lewRequested.length})</span>
                  </h3>
                </div>
                <div className="space-y-3">
                  {lewRequested.map((req) => {
                    const dt = catalogByCode.get(req.documentTypeCode) ?? null;
                    return (
                      <div key={req.id} id={`doc-req-${req.id}`}>
                        <DocumentRequestCard
                          variant={variantOf(req)}
                          documentType={dt}
                          request={req}
                          readOnly={!canUpload}
                          onReupload={
                            canUpload
                              ? (file) => handleReupload(req.id, file)
                              : undefined
                          }
                        />
                      </div>
                    );
                  })}
                </div>
              </section>
            )}

            {/* InfoBox — 요청이 없을 때만 노출 (요청 있으면 상단 배너가 대체) */}
            {!hasActiveRequests && (
              <InfoBox title="Upload is optional for now">
                Your LEW may request documents during review. You can also upload anything you already have — it speeds things up.
              </InfoBox>
            )}

            {/* 자발적 업로드 카드 (Phase 2 그대로) */}
            {canUpload && (
              <div className="mt-6">
                <DocumentRequestCard
                  variant="neutral"
                  catalog={catalog}
                  onUpload={handleUpload}
                  uploading={uploading}
                />
              </div>
            )}

            {/* 자발적 업로드 파일 목록 */}
            <div className="mt-8">
              <div className="flex items-center justify-between mb-3">
                <h4 className="text-sm font-semibold text-gray-800">
                  Uploaded{' '}
                  <span className="text-gray-500 font-normal">({voluntaryUploaded.length})</span>
                </h4>
              </div>

              {voluntaryUploaded.length === 0 ? (
                <div className="text-center py-8 text-sm text-gray-500 border border-dashed border-gray-200 rounded-lg">
                  <span className="text-3xl block mb-2" aria-hidden>
                    🗂
                  </span>
                  No documents yet. Upload when ready.
                </div>
              ) : (
                <ul className="divide-y divide-gray-200 border border-gray-200 rounded-lg">
                  {voluntaryUploaded.map((req) => {
                    const dt = catalogByCode.get(req.documentTypeCode);
                    const label = req.customLabel ?? dt?.labelEn ?? req.documentTypeCode;
                    const sizeText =
                      req.fulfilledFileSize != null ? formatBytes(req.fulfilledFileSize) : '';
                    const dateText = req.fulfilledAt
                      ? new Date(req.fulfilledAt).toLocaleDateString()
                      : '';
                    return (
                      <li
                        key={req.id}
                        className="flex items-center gap-3 px-4 py-3 hover:bg-gray-50"
                      >
                        <span className="text-2xl flex-shrink-0" aria-hidden>
                          {dt?.iconEmoji ?? '📄'}
                        </span>
                        <div className="flex-1 min-w-0">
                          <p className="text-sm font-medium text-gray-900 truncate">
                            {req.fulfilledFilename ?? label}
                          </p>
                          <p className="text-xs text-gray-500 mt-0.5">
                            {label}
                            {sizeText && <> · {sizeText}</>}
                            {dateText && <> · {dateText}</>}
                          </p>
                        </div>
                        {canUpload && (
                          <button
                            type="button"
                            onClick={() => setDeleteTarget(req)}
                            className="flex-shrink-0 p-2 text-error-600 hover:bg-error-50 rounded-md transition-colors"
                            aria-label={`Delete ${req.fulfilledFilename ?? label}`}
                          >
                            <svg
                              className="w-4 h-4"
                              fill="none"
                              stroke="currentColor"
                              strokeWidth={2}
                              viewBox="0 0 24 24"
                              aria-hidden
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6M1 7h22M10 3h4a1 1 0 011 1v3H9V4a1 1 0 011-1z"
                              />
                            </svg>
                          </button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>

            {showDevMockups && <DevMockupSkeletons catalog={catalog} />}
          </>
        )}
      </Card>

      <ConfirmDialog
        isOpen={deleteTarget !== null}
        onClose={() => (deleting ? undefined : setDeleteTarget(null))}
        onConfirm={handleDelete}
        title="Delete document"
        message={`Delete this document? It cannot be undone.${
          deleteTarget?.fulfilledFilename ? `\n\n${deleteTarget.fulfilledFilename}` : ''
        }`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        variant="danger"
        loading={deleting}
      />
    </>
  );
}

// ─────────────────────────────────────────────
// Dev mockups — ?devMockups=1 에서만 렌더
// Phase 3 PR#3 프로덕션 활성화 후에도 비교/QA 목적으로 유지
// ─────────────────────────────────────────────
function DevMockupSkeletons({ catalog }: { catalog: DocumentType[] }) {
  const sampleType = catalog.find((c) => c.code === 'SP_ACCOUNT') ?? catalog[0];
  if (!sampleType) return null;

  const baseRequest = {
    id: 99999,
    applicationSeq: 0,
    documentTypeCode: sampleType.code,
    createdAt: new Date().toISOString(),
  };

  const variants = ['requested', 'uploaded', 'approved', 'rejected'] as const;

  const mockFor = (v: 'requested' | 'uploaded' | 'approved' | 'rejected'): DocumentRequest => {
    switch (v) {
      case 'requested':
        return {
          ...baseRequest,
          status: 'REQUESTED',
          lewNote: 'Please attach the SP account holder PDF for verification.',
          requestedAt: new Date().toISOString(),
        };
      case 'uploaded':
        return {
          ...baseRequest,
          status: 'UPLOADED',
          fulfilledFilename: 'sp_account.pdf',
          fulfilledFileSize: 524288,
          fulfilledAt: new Date().toISOString(),
        };
      case 'approved':
        return {
          ...baseRequest,
          status: 'APPROVED',
          fulfilledFilename: 'sp_account.pdf',
          reviewedAt: new Date().toISOString(),
        };
      case 'rejected':
        return {
          ...baseRequest,
          status: 'REJECTED',
          fulfilledFilename: 'sp_account.pdf',
          rejectionReason: 'The file is blurry. Please upload a clearer copy.',
          reviewedAt: new Date().toISOString(),
        };
    }
  };

  return (
    <div className="mt-10 border-t border-dashed border-gray-300 pt-6">
      <p className="text-xs font-semibold text-gray-500 mb-3 uppercase tracking-wide">
        Dev mockups — Phase 3 variant skeletons (?devMockups=1)
      </p>
      <div className="space-y-3">
        {variants.map((v) => (
          <DocumentRequestCard
            key={v}
            variant={v}
            documentType={sampleType}
            request={mockFor(v)}
          />
        ))}
      </div>
    </div>
  );
}
