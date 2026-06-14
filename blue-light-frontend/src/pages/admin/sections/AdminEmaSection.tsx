import { useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Input } from '../../../components/ui/Input';
import { InfoBox } from '../../../components/ui/InfoBox';
import { formatEmaStatus, getEmaStatusBadge, MAX_UPLOAD_SIZE_MB } from '../../../utils/applicationUtils';
import type { ApplicationStatus, EmaSubmissionResponse, FileType } from '../../../types';

/**
 * EMA ELISE 제출 추적 섹션 (LEW EMA 탭 + ADMIN 상세 공용).
 * ema-submission-tracking-spec.md §8.2 상태별 액션 표를 그대로 구현.
 *
 * <p>EMA 도메인 상태 기계는 백엔드가 소유한다. 본 컴포넌트는 현재 상태에 맞는 액션만 노출하고,
 * 증빙(EMA_ACK)·라이선스(LICENSE_PDF) 첨부는 기존 업로드 엔드포인트를 재사용한다.</p>
 *
 * <p>설정 우선: {@code ema.emaAckRequired} 서버 응답값으로 "Required/Optional" 라벨을 동적 표기한다.</p>
 */
interface Props {
  /** EMA 제출 추적 응답 (전용 GET /ema). null 이면 로딩 중. */
  ema: EmaSubmissionResponse | null;
  /** 신청 진행 상태 — IN_PROGRESS 일 때만 액션 활성(NG3). */
  appStatus: ApplicationStatus;
  /** ADMIN/SYSTEM_ADMIN 여부 — Revert(T9) 노출 판단. */
  isAdmin: boolean;
  /** 액션 중 로딩 플래그. */
  busy: boolean;
  onSubmit: (emaReferenceNo: string) => Promise<void>;
  onQuery: (queryNote: string) => Promise<void>;
  onResubmit: (emaReferenceNo?: string) => Promise<void>;
  onApprove: () => Promise<void>;
  onReject: (reason?: string) => Promise<void>;
  onWithdraw: () => Promise<void>;
  onRevert: () => Promise<void>;
  /** 파일 업로드 (fileType = EMA_ACK | LICENSE_PDF). 업로드 후 EMA 응답 재조회 트리거. */
  onUploadFile: (file: File, fileType: FileType) => Promise<void>;
  /** Complete & Issue License — 기존 completeApplication 모달 오픈. */
  onCompleteClick: () => void;
}

export function AdminEmaSection({
  ema,
  appStatus,
  isAdmin,
  busy,
  onSubmit,
  onQuery,
  onResubmit,
  onApprove,
  onReject,
  onWithdraw,
  onRevert,
  onUploadFile,
  onCompleteClick,
}: Props) {
  const [referenceNo, setReferenceNo] = useState('');
  const [queryNote, setQueryNote] = useState('');
  const [rejectReason, setRejectReason] = useState('');
  const [uploadingType, setUploadingType] = useState<FileType | null>(null);

  // App 이 IN_PROGRESS 가 아니면 탭은 보이되 액션 비활성 (스펙 §8.1).
  const actionsEnabled = appStatus === 'IN_PROGRESS';

  if (!ema) {
    return (
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-2">EMA Submission</h2>
        <p className="text-sm text-gray-500">Loading EMA status…</p>
      </Card>
    );
  }

  const status = ema.emaSubmissionStatus;

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>, fileType: FileType) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (file.size > MAX_UPLOAD_SIZE_MB * 1024 * 1024) return;
    setUploadingType(fileType);
    try {
      await onUploadFile(file, fileType);
    } finally {
      setUploadingType(null);
    }
  };

  const submitReference = async () => {
    if (!referenceNo.trim()) return;
    await onSubmit(referenceNo.trim());
    setReferenceNo('');
  };

  const submitQuery = async () => {
    if (!queryNote.trim()) return;
    await onQuery(queryNote.trim());
    setQueryNote('');
  };

  const submitReject = async () => {
    await onReject(rejectReason.trim() || undefined);
    setRejectReason('');
  };

  return (
    <Card>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-800">EMA Submission (ELISE)</h2>
        <div className="flex items-center gap-2">
          <Badge variant={getEmaStatusBadge(status)}>{formatEmaStatus(status)}</Badge>
          {ema.emaGrandfathered && (
            <span title="Auto-approved before EMA tracking was introduced">
              <Badge variant="gray">Approved (legacy)</Badge>
            </span>
          )}
        </div>
      </div>

      {/* App 이 IN_PROGRESS 전이면 안내 + 액션 비활성 */}
      {!actionsEnabled && (
        <InfoBox className="mb-4">
          EMA submission can be tracked after the application is paid and processing has started.
        </InfoBox>
      )}

      {/* 제출 메타 (있을 때) */}
      {(ema.emaSubmittedAt || ema.emaReferenceNo) && status !== 'NOT_SUBMITTED' && (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-sm mb-4">
          {ema.emaReferenceNo && (
            <>
              <dt className="text-gray-500">Reference No.</dt>
              <dd className="text-gray-800 font-medium">{ema.emaReferenceNo}</dd>
            </>
          )}
          {ema.emaSubmittedAt && (
            <>
              <dt className="text-gray-500">Submitted</dt>
              <dd className="text-gray-800">{new Date(ema.emaSubmittedAt).toLocaleString()}</dd>
            </>
          )}
          {ema.emaSubmittedByName && (
            <>
              <dt className="text-gray-500">Submitted by</dt>
              <dd className="text-gray-800">{ema.emaSubmittedByName}</dd>
            </>
          )}
          {ema.emaDecisionAt && (
            <>
              <dt className="text-gray-500">Decision</dt>
              <dd className="text-gray-800">{new Date(ema.emaDecisionAt).toLocaleString()}</dd>
            </>
          )}
        </dl>
      )}

      {/* ── NOT_SUBMITTED ── */}
      {status === 'NOT_SUBMITTED' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            Not yet submitted to EMA ELISE. Enter the ELISE reference number after submitting,
            and optionally attach the acknowledgement.
          </p>
          <div className="max-w-md">
            <Input
              label="ELISE reference number"
              required
              placeholder="e.g. ELISE-2026-001234"
              value={referenceNo}
              onChange={(e) => setReferenceNo(e.target.value)}
              disabled={!actionsEnabled || busy}
            />
          </div>
          <EmaAckUpload
            ackRequired={ema.emaAckRequired}
            ackPresent={ema.emaAckPresent}
            disabled={!actionsEnabled}
            uploading={uploadingType === 'EMA_ACK'}
            onUpload={(e) => handleUpload(e, 'EMA_ACK')}
          />
          <Button
            size="sm"
            disabled={!actionsEnabled || busy || !referenceNo.trim()}
            loading={busy}
            onClick={submitReference}
          >
            Mark Submitted
          </Button>
        </div>
      )}

      {/* ── SUBMITTED / RESUBMITTED ── */}
      {(status === 'SUBMITTED' || status === 'RESUBMITTED') && (
        <div className="space-y-4">
          <EmaAckUpload
            ackRequired={ema.emaAckRequired}
            ackPresent={ema.emaAckPresent}
            disabled={!actionsEnabled}
            uploading={uploadingType === 'EMA_ACK'}
            onUpload={(e) => handleUpload(e, 'EMA_ACK')}
          />
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Query note (if EMA raises a query)
            </label>
            <textarea
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
              rows={2}
              placeholder="Describe the EMA query…"
              value={queryNote}
              onChange={(e) => setQueryNote(e.target.value)}
              disabled={!actionsEnabled || busy}
            />
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm" variant="outline"
              disabled={!actionsEnabled || busy || !queryNote.trim()}
              onClick={submitQuery}
            >
              Raise Query
            </Button>
            <Button size="sm" disabled={!actionsEnabled || busy} loading={busy} onClick={onApprove}>
              Approve
            </Button>
            <Button size="sm" variant="danger" disabled={!actionsEnabled || busy} onClick={submitReject}>
              Reject
            </Button>
            <Button size="sm" variant="ghost" disabled={!actionsEnabled || busy} onClick={onWithdraw}>
              Withdraw
            </Button>
          </div>
        </div>
      )}

      {/* ── QUERY_RAISED ── */}
      {status === 'QUERY_RAISED' && (
        <div className="space-y-4">
          {ema.emaQueryNote && (
            <div className="rounded-lg border border-warning-200 bg-warning-50 p-3">
              <p className="text-xs font-semibold text-warning-800">EMA query</p>
              <p className="text-sm text-warning-700 mt-1 whitespace-pre-wrap">{ema.emaQueryNote}</p>
            </div>
          )}
          <EmaAckUpload
            ackRequired={ema.emaAckRequired}
            ackPresent={ema.emaAckPresent}
            disabled={!actionsEnabled}
            uploading={uploadingType === 'EMA_ACK'}
            onUpload={(e) => handleUpload(e, 'EMA_ACK')}
          />
          <div className="max-w-md">
            <Input
              label="Updated reference number (optional)"
              placeholder="Leave blank to keep current"
              value={referenceNo}
              onChange={(e) => setReferenceNo(e.target.value)}
              disabled={!actionsEnabled || busy}
            />
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              disabled={!actionsEnabled || busy}
              loading={busy}
              onClick={async () => { await onResubmit(referenceNo.trim() || undefined); setReferenceNo(''); }}
            >
              Resubmit
            </Button>
            <Button size="sm" variant="ghost" disabled={!actionsEnabled || busy} onClick={onWithdraw}>
              Withdraw
            </Button>
          </div>
        </div>
      )}

      {/* ── APPROVED ── */}
      {status === 'APPROVED' && (
        <div className="space-y-4">
          {ema.emaGrandfathered && (
            <InfoBox title="Approved (legacy)">
              This application was already in progress before EMA tracking was introduced, so it was
              auto-approved. An admin can revert this if it needs correction.
            </InfoBox>
          )}
          {!ema.emaGrandfathered && (
            <p className="text-sm text-success-700">EMA submission approved.</p>
          )}

          {/* LICENSE_PDF 업로드 영역 + Complete CTA */}
          <div className="rounded-lg border border-gray-200 p-3 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-gray-700">Licence PDF</span>
              {ema.licensePdfPresent ? (
                <Badge variant="success">Uploaded</Badge>
              ) : (
                <Badge variant="warning">Required</Badge>
              )}
            </div>
            <label className="flex items-center justify-center gap-2 px-4 py-2.5 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors text-sm text-gray-600">
              <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
              </svg>
              {uploadingType === 'LICENSE_PDF' ? 'Uploading…' : ema.licensePdfPresent ? 'Replace licence PDF' : 'Upload licence PDF'}
              <input
                type="file"
                accept=".pdf,.jpg,.jpeg,.png"
                className="hidden"
                disabled={!actionsEnabled || uploadingType === 'LICENSE_PDF'}
                onChange={(e) => handleUpload(e, 'LICENSE_PDF')}
              />
            </label>
            {!ema.licensePdfPresent && (
              <p className="text-xs text-gray-500">Upload the licence PDF to enable completion.</p>
            )}
            <Button
              disabled={!actionsEnabled || !ema.canComplete || busy}
              aria-disabled={!ema.canComplete}
              onClick={onCompleteClick}
            >
              Complete &amp; Issue Licence
            </Button>
          </div>

          {isAdmin && (
            <Button size="sm" variant="ghost" disabled={!actionsEnabled || busy} onClick={onRevert}>
              Revert approval
            </Button>
          )}
        </div>
      )}

      {/* ── REJECTED ── */}
      {status === 'REJECTED' && (
        <div className="space-y-4">
          {ema.emaQueryNote && (
            <div className="rounded-lg border border-error-200 bg-error-50 p-3">
              <p className="text-xs font-semibold text-error-700">Rejection reason</p>
              <p className="text-sm text-error-600 mt-1 whitespace-pre-wrap">{ema.emaQueryNote}</p>
            </div>
          )}
          <InfoBox>
            Rejection is not final. Address the reason on EMA ELISE, then resubmit to re-enter the flow.
          </InfoBox>
          <EmaAckUpload
            ackRequired={ema.emaAckRequired}
            ackPresent={ema.emaAckPresent}
            disabled={!actionsEnabled}
            uploading={uploadingType === 'EMA_ACK'}
            onUpload={(e) => handleUpload(e, 'EMA_ACK')}
          />
          <div className="max-w-md">
            <Input
              label="Updated reference number (optional)"
              placeholder="Leave blank to keep current"
              value={referenceNo}
              onChange={(e) => setReferenceNo(e.target.value)}
              disabled={!actionsEnabled || busy}
            />
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              disabled={!actionsEnabled || busy}
              loading={busy}
              onClick={async () => { await onResubmit(referenceNo.trim() || undefined); setReferenceNo(''); }}
            >
              Resubmit
            </Button>
            {isAdmin && (
              <Button size="sm" variant="ghost" disabled={!actionsEnabled || busy} onClick={onWithdraw}>
                Withdraw
              </Button>
            )}
          </div>
        </div>
      )}

      {/* ── WITHDRAWN ── */}
      {status === 'WITHDRAWN' && (
        <div className="space-y-3">
          <p className="text-sm text-gray-600">EMA submission was withdrawn.</p>
          {isAdmin && (
            <Button size="sm" variant="ghost" disabled={!actionsEnabled || busy} onClick={onRevert}>
              Revert (restore previous state)
            </Button>
          )}
        </div>
      )}
    </Card>
  );
}

/** EMA_ACK(접수증) 업로드 — 필수/선택 라벨은 서버 ema.ack.required 값에 따라 동적(설정 우선). */
function EmaAckUpload({
  ackRequired,
  ackPresent,
  disabled,
  uploading,
  onUpload,
}: {
  ackRequired: boolean;
  ackPresent: boolean;
  disabled: boolean;
  uploading: boolean;
  onUpload: (e: React.ChangeEvent<HTMLInputElement>) => void;
}) {
  return (
    <div className="rounded-lg border border-gray-200 p-3 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-gray-700">
          EMA acknowledgement
          <span className={`ml-2 text-xs font-normal ${ackRequired ? 'text-error-600' : 'text-gray-400'}`}>
            {ackRequired ? 'Required' : 'Optional'}
          </span>
        </span>
        {ackPresent && <Badge variant="success">Attached</Badge>}
      </div>
      <label className="flex items-center justify-center gap-2 px-4 py-2 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors text-sm text-gray-600">
        <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
        </svg>
        {uploading ? 'Uploading…' : ackPresent ? 'Replace receipt' : 'Upload ELISE receipt'}
        <input
          type="file"
          accept=".pdf,.jpg,.jpeg,.png"
          className="hidden"
          disabled={disabled || uploading}
          onChange={onUpload}
        />
      </label>
    </div>
  );
}
