import { useMemo, useState } from 'react';
import type { DocumentRequest, DocumentType } from '../../types/document';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { DocumentTypeSelector } from './DocumentTypeSelector';
import { DocumentUploadArea } from './DocumentUploadArea';
import { formatBytes, isMimeAccepted } from './documentUtils';

export type DocumentRequestCardVariant =
  | 'neutral'
  | 'requested'
  | 'uploaded'
  | 'approved'
  | 'rejected';

interface DocumentRequestCardProps {
  variant: DocumentRequestCardVariant;
  /** 전체 카탈로그 — neutral variant에서 selector 표시 */
  catalog?: DocumentType[];
  /** 요청 엔티티 (neutral variant에서는 null) */
  request?: DocumentRequest | null;
  /** 요청이 있을 때 참조하는 type 메타 (badge/아이콘 표시) */
  documentType?: DocumentType | null;

  /** Phase 2 자발적 업로드 */
  onUpload?: (payload: { documentTypeCode: string; customLabel?: string; file: File }) => Promise<void>;
  uploading?: boolean;

  /** 신청자가 읽기 전용으로 열람 중 (LEW/ADMIN) 등 */
  readOnly?: boolean;
}

const variantStyle: Record<DocumentRequestCardVariant, string> = {
  neutral:   'border-gray-200 bg-surface',
  requested: 'border-warning-500/40 bg-warning-50',
  uploaded:  'border-info-500/40 bg-info-50',
  approved:  'border-success-500/40 bg-success-50',
  rejected:  'border-error-500/40 bg-error-50',
};

/**
 * DocumentRequestCard — 공용 카드
 *
 * Phase 2: 프로덕션 경로에서는 `neutral` variant만 사용 (자발적 업로드 UI).
 * 나머지 4종 (`requested`/`uploaded`/`approved`/`rejected`)은 Phase 3 대비 skeleton.
 * 04-design-spec.md §3/§6 참조.
 */
export function DocumentRequestCard(props: DocumentRequestCardProps) {
  const { variant } = props;

  return (
    <div className={`rounded-lg border p-5 ${variantStyle[variant]}`}>
      {variant === 'neutral' ? (
        <NeutralBody {...props} />
      ) : (
        <RequestBody {...props} />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────
// Neutral — Phase 2 자발적 업로드
// ─────────────────────────────────────────────
function NeutralBody({
  catalog = [],
  onUpload,
  uploading,
  readOnly,
}: DocumentRequestCardProps) {
  const [typeCode, setTypeCode] = useState('');
  const [customLabel, setCustomLabel] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | undefined>();

  const selectedType = useMemo(
    () => catalog.find((dt) => dt.code === typeCode) ?? null,
    [catalog, typeCode],
  );

  const isOther = typeCode === 'OTHER';
  const canUpload =
    !!selectedType &&
    !!file &&
    (!isOther || customLabel.trim().length > 0) &&
    !uploading;

  const handleFileSelect = (f: File | null) => {
    setError(undefined);
    if (!f || !selectedType) {
      setFile(f);
      return;
    }
    // 클라 1차 검증 — 서버 검증은 최종 가드
    const maxBytes = selectedType.maxSizeMb * 1024 * 1024;
    if (f.size > maxBytes) {
      setFile(null);
      setError(`파일이 너무 큽니다 (최대 ${selectedType.maxSizeMb}MB). · File too large.`);
      return;
    }
    if (!isMimeAccepted(f, selectedType.acceptedMime)) {
      setFile(null);
      setError(
        `${selectedType.labelKo}에 허용되지 않는 형식입니다. · File type not allowed.`,
      );
      return;
    }
    setFile(f);
  };

  const handleUpload = async () => {
    if (!onUpload || !selectedType || !file) return;
    try {
      await onUpload({
        documentTypeCode: selectedType.code,
        customLabel: isOther ? customLabel.trim() : undefined,
        file,
      });
      // 성공 → 파일만 reset, 타입은 유지 (연속 업로드 편의, UX §2 상태 전이 3)
      setFile(null);
      setError(undefined);
      if (isOther) setCustomLabel('');
    } catch (err) {
      const msg =
        (err as { message?: string })?.message ??
        '업로드에 실패했습니다. · Upload failed.';
      setError(msg);
    }
  };

  return (
    <>
      <div className="flex items-start gap-3 mb-4">
        <span className="text-2xl" aria-hidden>
          📎
        </span>
        <div>
          <h4 className="text-sm font-semibold text-gray-900">
            서류 업로드 · Upload a document
          </h4>
          <p className="text-xs text-gray-500 mt-0.5">
            원하는 서류를 자발적으로 업로드할 수 있습니다. · Upload optional supporting documents.
          </p>
        </div>
      </div>

      <div className="space-y-4">
        <DocumentTypeSelector
          catalog={catalog}
          value={typeCode}
          onChange={(v) => {
            setTypeCode(v);
            setFile(null);
            setError(undefined);
          }}
          disabled={readOnly || uploading}
        />

        {isOther && (
          <div className="animate-in">
            <Input
              label="라벨 · Label"
              required
              placeholder="이 서류를 설명해 주세요 · Describe this document"
              value={customLabel}
              onChange={(e) => setCustomLabel(e.target.value)}
              disabled={readOnly || uploading}
              maxLength={200}
            />
          </div>
        )}

        {selectedType && (
          <DocumentUploadArea
            acceptedMime={selectedType.acceptedMime}
            maxSizeMb={selectedType.maxSizeMb}
            selectedFile={file}
            onFileSelect={handleFileSelect}
            disabled={readOnly}
            uploading={uploading}
            error={error}
          />
        )}

        <div className="flex justify-end">
          <Button
            onClick={handleUpload}
            loading={uploading}
            disabled={!canUpload || readOnly}
          >
            업로드 · Upload
          </Button>
        </div>
      </div>
    </>
  );
}

// ─────────────────────────────────────────────
// Request body — Phase 3 skeleton (requested/uploaded/approved/rejected)
// Phase 2는 `?devMockups=1`에서만 확인
// ─────────────────────────────────────────────
function RequestBody({ variant, documentType, request }: DocumentRequestCardProps) {
  const badge = (() => {
    switch (variant) {
      case 'requested':
        return <Badge variant="warning">요청됨 · Requested</Badge>;
      case 'uploaded':
        return <Badge variant="info">검토 대기 · Under Review</Badge>;
      case 'approved':
        return <Badge variant="success">승인됨 · Approved</Badge>;
      case 'rejected':
        return <Badge variant="error">반려됨 · Rejected</Badge>;
      default:
        return null;
    }
  })();

  const label = request?.customLabel ?? documentType?.labelKo ?? documentType?.code ?? '—';

  return (
    <>
      <div className="flex items-start justify-between gap-3 mb-3">
        <div className="flex items-center gap-2">
          <span className="text-xl" aria-hidden>
            {documentType?.iconEmoji ?? '📎'}
          </span>
          <div>
            <h4 className="text-sm font-semibold text-gray-900">{label}</h4>
            {documentType?.labelEn && (
              <p className="text-xs text-gray-500 mt-0.5">{documentType.labelEn}</p>
            )}
          </div>
        </div>
        {badge}
      </div>

      {variant === 'requested' && request?.lewNote && (
        <blockquote className="border-l-2 border-warning-500 pl-3 text-sm text-gray-700 italic my-3">
          {request.lewNote}
        </blockquote>
      )}

      {variant === 'uploaded' && request?.fulfilledFilename && (
        <p className="text-xs text-gray-700">
          {request.fulfilledFilename}
          {request.fulfilledFileSize != null && (
            <> · {formatBytes(request.fulfilledFileSize)}</>
          )}
        </p>
      )}

      {variant === 'rejected' && request?.rejectionReason && (
        <blockquote className="border-l-2 border-error-500 pl-3 text-sm text-gray-700 italic my-3">
          {request.rejectionReason}
        </blockquote>
      )}

      {variant === 'approved' && (
        <p className="text-xs text-success-700 flex items-center gap-1">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24" aria-hidden>
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
          </svg>
          LEW가 승인했습니다. · Approved by LEW.
        </p>
      )}
    </>
  );
}
