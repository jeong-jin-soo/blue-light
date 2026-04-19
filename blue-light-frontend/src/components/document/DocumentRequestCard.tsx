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

  // ── Phase 3 (PR#2 타입 선언, PR#3에서 프로덕션 연결) ─────────
  /** UPLOADED → APPROVED (LEW 전용) */
  onApprove?: () => void | Promise<void>;
  /** UPLOADED → REJECTED (LEW 전용) */
  onReject?: () => void | Promise<void>;
  /** REQUESTED → CANCELLED (LEW 전용) */
  onCancel?: () => void | Promise<void>;
  /** REQUESTED/REJECTED → UPLOADED (신청자 전용, PR#3) */
  onReupload?: (file: File) => Promise<void>;
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
      setError(`File too large (max ${selectedType.maxSizeMb}MB).`);
      return;
    }
    if (!isMimeAccepted(f, selectedType.acceptedMime)) {
      setFile(null);
      setError(
        `File type not allowed for ${selectedType.labelEn}.`,
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
        'Upload failed.';
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
            Upload a document
          </h4>
          <p className="text-xs text-gray-500 mt-0.5">
            Upload optional supporting documents.
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
              label="Label"
              required
              placeholder="Describe this document"
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
            Upload
          </Button>
        </div>
      </div>
    </>
  );
}

// ─────────────────────────────────────────────
// Request body — Phase 3 PR#3 프로덕션 4 variant
//   requested: LEW 메모 + 업로드 영역 + [Upload]
//   uploaded : 파일 요약 + [Replace file]
//   approved : 승인 시각
//   rejected : 사유 + 이전 파일 힌트 + 업로드 영역 + [Upload new file]
// ─────────────────────────────────────────────
function RequestBody({
  variant,
  documentType,
  request,
  onReupload,
  readOnly,
}: DocumentRequestCardProps) {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | undefined>();

  const badge = (() => {
    switch (variant) {
      case 'requested':
        return <Badge variant="warning">Action needed</Badge>;
      case 'uploaded':
        return <Badge variant="info">Waiting for LEW</Badge>;
      case 'approved':
        return <Badge variant="success">Approved</Badge>;
      case 'rejected':
        return <Badge variant="error">Needs re-upload</Badge>;
      default:
        return null;
    }
  })();

  const label = request?.customLabel ?? documentType?.labelEn ?? documentType?.code ?? '—';
  const acceptedMime = documentType?.acceptedMime ?? '';
  const maxSizeMb = documentType?.maxSizeMb ?? 10;
  const canReupload =
    (variant === 'requested' || variant === 'rejected' || variant === 'uploaded') &&
    typeof onReupload === 'function' &&
    !readOnly;

  const handleFileSelect = (f: File | null) => {
    setError(undefined);
    if (!f) {
      setFile(null);
      return;
    }
    if (documentType) {
      const maxBytes = maxSizeMb * 1024 * 1024;
      if (f.size > maxBytes) {
        setFile(null);
        setError(`File too large (max ${maxSizeMb}MB).`);
        return;
      }
      if (!isMimeAccepted(f, acceptedMime)) {
        setFile(null);
        setError(`File type not allowed for ${documentType.labelEn}.`);
        return;
      }
    }
    setFile(f);
  };

  const handleSubmit = async () => {
    if (!onReupload || !file) return;
    setUploading(true);
    try {
      await onReupload(file);
      setFile(null);
      setError(undefined);
    } catch (err) {
      const msg =
        (err as { message?: string })?.message ??
        'Upload failed.';
      setError(msg);
    } finally {
      setUploading(false);
    }
  };

  const uploadButtonLabel =
    variant === 'rejected'
      ? 'Upload new file'
      : variant === 'uploaded'
        ? 'Replace file'
        : 'Upload';

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

      {/* LEW 메모 (requested/rejected에서 공통 노출) */}
      {variant === 'requested' && request?.lewNote && (
        <div className="mb-3">
          <p className="text-xs font-medium text-gray-500 mb-1">
            Note from LEW
          </p>
          <blockquote className="border-l-2 border-warning-500 pl-3 text-sm text-gray-700 italic">
            {request.lewNote}
          </blockquote>
        </div>
      )}

      {/* 템플릿 링크 */}
      {variant === 'requested' && documentType?.templateUrl && (
        <p className="text-xs text-gray-600 mb-3">
          💡{' '}
          <a
            href={documentType.templateUrl}
            target="_blank"
            rel="noreferrer"
            className="text-primary underline"
          >
            Download template
          </a>
        </p>
      )}

      {/* UPLOADED — 파일 요약 */}
      {variant === 'uploaded' && (
        <div className="mb-3">
          <p className="text-sm text-gray-800">
            <span className="font-medium">{request?.fulfilledFilename ?? '—'}</span>
            {request?.fulfilledFileSize != null && (
              <span className="text-gray-500"> · {formatBytes(request.fulfilledFileSize)}</span>
            )}
          </p>
          <p className="text-xs text-gray-500 mt-0.5">
            LEW is reviewing. You will be notified.
          </p>
        </div>
      )}

      {/* REJECTED — 사유 + 이전 파일 힌트 */}
      {variant === 'rejected' && (
        <div className="mb-3">
          <p className="text-sm text-gray-800 mb-2">
            LEW rejected your upload.
          </p>
          {request?.rejectionReason && (
            <blockquote className="border-l-2 border-error-500 pl-3 text-sm text-gray-700 italic">
              {request.rejectionReason}
            </blockquote>
          )}
          {request?.fulfilledFilename && (
            <p className="text-xs text-gray-500 mt-2">
              Previous:{' '}
              <span className="font-medium">{request.fulfilledFilename}</span> (kept in history)
            </p>
          )}
        </div>
      )}

      {/* APPROVED */}
      {variant === 'approved' && (
        <div className="flex items-center gap-2 text-xs text-success-700">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24" aria-hidden>
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
          </svg>
          <span>
            Approved by LEW.
            {request?.reviewedAt && (
              <> · {new Date(request.reviewedAt).toLocaleString()}</>
            )}
          </span>
        </div>
      )}

      {/* 업로드 영역 — requested/rejected/uploaded(Replace) */}
      {canReupload && documentType && (
        <div className="mt-3 space-y-3">
          <DocumentUploadArea
            acceptedMime={acceptedMime}
            maxSizeMb={maxSizeMb}
            selectedFile={file}
            onFileSelect={handleFileSelect}
            disabled={readOnly}
            uploading={uploading}
            error={error}
          />
          <div className="flex justify-end">
            <Button
              onClick={handleSubmit}
              loading={uploading}
              disabled={!file || uploading || readOnly}
              variant={variant === 'uploaded' ? 'outline' : 'primary'}
              size="sm"
            >
              {uploadButtonLabel}
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
