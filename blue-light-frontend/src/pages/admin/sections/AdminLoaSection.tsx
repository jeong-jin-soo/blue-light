import { useState, useRef } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge, type BadgeVariant } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Textarea } from '../../../components/ui/Textarea';
import { useToastStore } from '../../../stores/toastStore';
import loaApi from '../../../api/loaApi';
import type { AdminApplication, LoaStatus, LoaStage, FileInfo } from '../../../types';

type LoaFileType = 'OWNER_AUTH_LETTER' | 'LOA_FINAL';

interface Props {
  application: AdminApplication;
  loaStatus: LoaStatus | null;
  /** 신청 첨부 파일 목록 — LoA 행에 현재 파일명·업로드 시각을 표시하기 위함. */
  files: FileInfo[];
  /** 파일 다운로드 핸들러 (페이지가 fileApi.downloadFile 로 위임). */
  onDownload: (fileSeq: number, filename: string) => void;
  /** 등록/교체 성공 후 상위에서 loaStatus·files 를 다시 로드. */
  onReplaced: () => void | Promise<void>;
}

/** 업로드 시각을 간결하게 표기 (YYYY-MM-DD HH:mm). */
function formatUploadedAt(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** loaStage → 표시 라벨 + 뱃지 색상. */
const STAGE_META: Record<LoaStage, { label: string; variant: BadgeVariant }> = {
  NOT_STARTED: { label: 'Not started', variant: 'gray' },
  FORM_SENT: { label: 'Form sent to applicant', variant: 'info' },
  APPLICANT_UPLOADED: { label: 'Applicant signed copy uploaded', variant: 'warning' },
  FINAL_UPLOADED: { label: 'Final LoA uploaded', variant: 'success' },
};

const FILE_TYPE_LABEL: Record<LoaFileType, string> = {
  OWNER_AUTH_LETTER: 'Owner-signed copy (Owner Auth Letter)',
  LOA_FINAL: 'Final LoA (LEW)',
};

/**
 * Admin LoA 교환-모델 패널 (Part B).
 *
 * <p>레거시 generate/sign 모델 대신 교환 모델의 진행 상태를 보여주고,
 * ADMIN/SYSTEM_ADMIN 이 사유를 남기며 LoA 파일을 등록/교체한다.
 * 기존 파일은 서버에서 보관(삭제 안 함)되며 사유는 감사 로그에 기록된다.</p>
 */
export function AdminLoaSection({ application, loaStatus, files, onDownload, onReplaced }: Props) {
  const toast = useToastStore();

  const stage: LoaStage = loaStatus?.loaStage ?? 'NOT_STARTED';
  const stageMeta = STAGE_META[stage];

  // 현재(최신) 파일 메타 — 교체 시 파일명·업로드 시각이 바뀌어 화면에 반영됨.
  const ownerFile = files.find((f) => f.fileSeq === loaStatus?.applicantFileSeq);
  const finalFile = files.find((f) => f.fileSeq === loaStatus?.finalFileSeq);

  // ── 등록/교체 폼 상태 ──
  const [fileType, setFileType] = useState<LoaFileType>('OWNER_AUTH_LETTER');
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const reasonMissing = reason.trim().length === 0;
  const canSubmit = !!selectedFile && !reasonMissing && !submitting;

  // ── LoA 폼 전달 (NEW 전용, active 폼 있을 때) ──
  const [sendingForm, setSendingForm] = useState(false);
  const canSendForm = application.applicationType === 'NEW' && !!loaStatus?.activeFormAvailable;

  const handleSendForm = async () => {
    setSendingForm(true);
    try {
      await loaApi.adminSendLoaForm(application.applicationSeq);
      toast.success('LoA form sent to the applicant.');
      await onReplaced();
    } catch {
      toast.error('Failed to send the LoA form.');
    } finally {
      setSendingForm(false);
    }
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] ?? null;
    if (file && file.size > 20 * 1024 * 1024) {
      toast.error('File exceeds the 20MB limit.');
      e.target.value = '';
      return;
    }
    setSelectedFile(file);
    e.target.value = '';
  };

  const handleSubmit = async () => {
    if (!selectedFile || reasonMissing) return;
    setSubmitting(true);
    try {
      await loaApi.adminReplaceLoa(application.applicationSeq, fileType, selectedFile, reason.trim());
      toast.success('LoA file uploaded. Previous file retained and the reason recorded.');
      setSelectedFile(null);
      setReason('');
      await onReplaced();
    } catch {
      toast.error('Failed to upload the LoA file.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment (LoA)</h2>
        <Badge variant={stageMeta.variant}>{stageMeta.label}</Badge>
      </div>

      {/* ── 진행 상태 / 파일 ── */}
      <div className="space-y-2.5">
        {/* 신청자 서명본 */}
        <div className="flex items-center justify-between px-3 py-2.5 bg-gray-50 rounded-lg border border-gray-100">
          <div className="min-w-0">
            <p className="text-sm font-medium text-gray-800">Owner-signed copy</p>
            {loaStatus?.applicantFileSeq ? (
              <p className="text-xs text-gray-500 truncate">
                {ownerFile?.originalFilename ?? 'Uploaded'}
                {ownerFile?.uploadedAt && (
                  <span className="text-gray-400"> · {formatUploadedAt(ownerFile.uploadedAt)}</span>
                )}
              </p>
            ) : (
              <p className="text-xs text-gray-500">Not uploaded</p>
            )}
          </div>
          {loaStatus?.applicantFileSeq ? (
            <button
              onClick={() => onDownload(
                loaStatus.applicantFileSeq!,
                `LOA_owner_${application.applicationSeq}.pdf`,
              )}
              className="text-sm text-primary-600 hover:text-primary-700 font-medium flex-shrink-0"
            >
              Download
            </button>
          ) : (
            <span className="text-xs text-gray-400 flex-shrink-0">—</span>
          )}
        </div>

        {/* LEW 최종본 */}
        <div className="flex items-center justify-between px-3 py-2.5 bg-gray-50 rounded-lg border border-gray-100">
          <div className="min-w-0">
            <p className="text-sm font-medium text-gray-800">Final LoA (LEW)</p>
            {loaStatus?.finalFileSeq ? (
              <p className="text-xs text-gray-500 truncate">
                {finalFile?.originalFilename ?? 'Uploaded'}
                {finalFile?.uploadedAt && (
                  <span className="text-gray-400"> · {formatUploadedAt(finalFile.uploadedAt)}</span>
                )}
              </p>
            ) : (
              <p className="text-xs text-gray-500">Not uploaded</p>
            )}
          </div>
          {loaStatus?.finalFileSeq ? (
            <button
              onClick={() => onDownload(
                loaStatus.finalFileSeq!,
                `LOA_final_${application.applicationSeq}.pdf`,
              )}
              className="text-sm text-primary-600 hover:text-primary-700 font-medium flex-shrink-0"
            >
              Download
            </button>
          ) : (
            <span className="text-xs text-gray-400 flex-shrink-0">—</span>
          )}
        </div>

        {/* active 폼 라벨 (NEW 전용) */}
        {loaStatus?.activeFormAvailable && loaStatus.activeFormLabel && (
          <p className="text-xs text-gray-500 px-1">
            Active LoA form: <span className="font-medium text-gray-700">{loaStatus.activeFormLabel}</span>
          </p>
        )}

        {/* LoA 폼 전달 (NEW 전용) — 담당 LEW가 없거나 ADMIN이 직접 보낼 때 */}
        {canSendForm && (
          <div className="pt-1">
            <Button
              variant="outline"
              size="sm"
              loading={sendingForm}
              onClick={handleSendForm}
            >
              {stage === 'NOT_STARTED' ? 'Send LoA form to applicant' : 'Resend LoA form to applicant'}
            </Button>
          </div>
        )}
      </div>

      {/* ── admin 등록/교체 폼 ── */}
      <div className="mt-5 pt-5 border-t border-gray-100 space-y-3">
        <div>
          <p className="text-sm font-semibold text-gray-800">Register / replace LoA file</p>
          <p className="text-xs text-gray-500 mt-0.5">
            The existing file is retained and the reason is recorded for audit.
          </p>
        </div>

        {/* 파일 타입 선택 */}
        <div>
          <label htmlFor="loa-file-type" className="block text-sm font-medium text-gray-700 mb-1.5">
            File type
          </label>
          <select
            id="loa-file-type"
            value={fileType}
            onChange={(e) => setFileType(e.target.value as LoaFileType)}
            className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
          >
            <option value="OWNER_AUTH_LETTER">{FILE_TYPE_LABEL.OWNER_AUTH_LETTER}</option>
            <option value="LOA_FINAL">{FILE_TYPE_LABEL.LOA_FINAL}</option>
          </select>
        </div>

        {/* 파일 선택 */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1.5">File</label>
          {selectedFile ? (
            <div className="flex items-center justify-between px-3 py-2.5 bg-gray-50 rounded-lg border border-gray-200">
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-lg">📄</span>
                <span className="text-sm text-gray-700 truncate">{selectedFile.name}</span>
              </div>
              <button
                type="button"
                onClick={() => setSelectedFile(null)}
                className="text-gray-400 hover:text-red-500 transition-colors p-1 flex-shrink-0"
                aria-label="Remove selected file"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          ) : (
            <label className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors">
              <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
              </svg>
              <span className="text-sm text-gray-600">Choose file (PDF / JPG / PNG, ≤20MB)</span>
              <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,.jpg,.jpeg,.png"
                className="hidden"
                onChange={handleFileSelect}
              />
            </label>
          )}
        </div>

        {/* 사유 (필수) */}
        <Textarea
          label="Reason"
          required
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Why is this file being registered or replaced? (recorded for audit)"
          error={selectedFile && reasonMissing ? 'A reason is required.' : undefined}
          className="min-h-[80px]"
        />

        <Button
          variant="primary"
          size="md"
          loading={submitting}
          disabled={!canSubmit}
          onClick={handleSubmit}
        >
          Upload (replace)
        </Button>
      </div>
    </Card>
  );
}
