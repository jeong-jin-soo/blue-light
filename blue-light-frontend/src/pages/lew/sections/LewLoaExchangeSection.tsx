import { useRef, useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge, type BadgeVariant } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Download, Send, Upload, FileText, CheckCircle2, Clock } from 'lucide-react';
import type { ApplicationType, LoaStage, LoaStatus } from '../../../types';

interface Props {
  applicationType: ApplicationType;
  loaStatus: LoaStatus | null;
  /** NEW: active 폼 신청자에게 전달 (send-form). */
  onSendForm: () => Promise<void>;
  /** LEW 최종본 업로드 (final-upload). */
  onUploadFinal: (file: File) => Promise<void>;
  /** 신청자 서명본 다운로드 (fileSeq). */
  onDownloadFile: (fileSeq: number, filename: string) => void;
  sendingForm: boolean;
  uploadingFinal: boolean;
}

const stageMeta: Record<LoaStage, { label: string; variant: BadgeVariant }> = {
  NOT_STARTED: { label: 'Not started', variant: 'gray' },
  FORM_SENT: { label: 'Form sent to applicant', variant: 'info' },
  APPLICANT_UPLOADED: { label: 'Applicant uploaded', variant: 'warning' },
  FINAL_UPLOADED: { label: 'Final LoA uploaded', variant: 'success' },
};

/**
 * LEW 검토 — LoA 교환 모델 섹션 (loa-exchange-redesign-spec.md §4.3, PR3b).
 *
 * <p>흐름: ① Send LoA form (NEW, FORM_SENT 전) → ② Download applicant LoA → ③ Upload final LoA.
 * RENEWAL 은 send-form 단계를 숨기고 다운로드 + 최종본 업로드만 노출한다.</p>
 */
export function LewLoaExchangeSection({
  applicationType,
  loaStatus,
  onSendForm,
  onUploadFinal,
  onDownloadFile,
  sendingForm,
  uploadingFinal,
}: Props) {
  const isRenewal = applicationType === 'RENEWAL';
  const stage: LoaStage = loaStatus?.loaStage ?? 'NOT_STARTED';
  const meta = stageMeta[stage];

  const finalInputRef = useRef<HTMLInputElement>(null);
  const [selectedFinal, setSelectedFinal] = useState<File | null>(null);

  const applicantUploaded = !!loaStatus?.applicantFileSeq;
  const finalUploaded = !!loaStatus?.finalFileSeq;

  const handleFinalSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) setSelectedFinal(file);
    e.target.value = '';
  };

  const handleFinalUpload = async () => {
    if (!selectedFinal) return;
    await onUploadFinal(selectedFinal);
    setSelectedFinal(null);
  };

  return (
    <Card>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment (LoA)</h2>
        <Badge variant={meta.variant}>{meta.label}</Badge>
      </div>

      {/* ── Step 1: Send LoA form (NEW only) ── */}
      {!isRenewal && (
        <div className="mb-4 rounded-lg border border-gray-200 p-4">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-sm font-medium text-gray-800">1. Send LoA form to applicant</p>
              <p className="mt-0.5 text-xs text-gray-500">
                Share the latest LoA form so the applicant can sign it offline and upload the signed copy.
              </p>
              {loaStatus?.activeFormLabel && (
                <p className="mt-1 text-xs text-gray-400">Active form: {loaStatus.activeFormLabel}</p>
              )}
            </div>
            {/*
             * "Sent" 는 오직 loaStage 가 실제로 전진했을 때만 표기한다.
             * applicantFileSeq/finalFileSeq(파일 존재)는 일반 문서 업로드·DocumentRequest("LOA")
             * 경로로도 생성되므로 send-form 을 호출하지 않았는데 "Sent" 로 오표기되는 원인이었다.
             * loaStage 전이(markLoaFormSent/markLoaApplicantUploaded/markLoaFinalUploaded)만이
             * LoA 교환 흐름의 정본 — NOT_STARTED 가 아니면 폼이 전달된 것이다.
             */}
            {stage !== 'NOT_STARTED' ? (
              <Badge variant="success">
                <CheckCircle2 className="h-3.5 w-3.5" /> Sent
              </Badge>
            ) : (
              <Button
                size="sm"
                onClick={onSendForm}
                loading={sendingForm}
                leftIcon={<Send className="h-4 w-4" />}
                disabled={!loaStatus?.activeFormAvailable && stage === 'NOT_STARTED'}
              >
                Send form
              </Button>
            )}
          </div>
          {!loaStatus?.activeFormAvailable && stage === 'NOT_STARTED' && (
            <p className="mt-2 text-xs text-warning-700">
              No active LoA form is configured. Ask an administrator to upload one before sending.
            </p>
          )}
        </div>
      )}

      {/* ── Step 2: Download applicant signed LoA ── */}
      <div className="mb-4 rounded-lg border border-gray-200 p-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-sm font-medium text-gray-800">
              {isRenewal ? '1. Applicant LoA' : '2. Applicant signed LoA'}
            </p>
            <p className="mt-0.5 text-xs text-gray-500">
              {applicantUploaded
                ? 'The applicant has uploaded a signed LoA. Download it to review and complete.'
                : 'Waiting for the applicant to upload their signed LoA.'}
            </p>
          </div>
          {applicantUploaded ? (
            <div className="flex flex-shrink-0 items-center gap-2">
              <Badge variant="success">
                <CheckCircle2 className="h-3.5 w-3.5" /> Received
              </Badge>
              <Button
                size="sm"
                variant="outline"
                leftIcon={<Download className="h-4 w-4" />}
                onClick={() =>
                  loaStatus?.applicantFileSeq &&
                  onDownloadFile(loaStatus.applicantFileSeq, `LoA_applicant_${loaStatus.applicationSeq}`)
                }
              >
                Download
              </Button>
            </div>
          ) : (
            <Badge variant="gray">
              <Clock className="h-3.5 w-3.5" /> Pending
            </Badge>
          )}
        </div>
      </div>

      {/* ── Step 3: Upload final LoA ── */}
      <div className="rounded-lg border border-gray-200 p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-medium text-gray-800">
              {isRenewal ? '2. Upload final LoA' : '3. Upload final LoA'}
            </p>
            <p className="mt-0.5 text-xs text-gray-500">
              Upload the completed LoA after supplementing the applicant's submission. This is submitted to EMA externally.
            </p>
          </div>
          {finalUploaded ? (
            <div className="flex flex-shrink-0 items-center gap-2">
              <Badge variant="success">
                <CheckCircle2 className="h-3.5 w-3.5" /> Uploaded
              </Badge>
              <Button
                size="sm"
                variant="outline"
                leftIcon={<Download className="h-4 w-4" />}
                onClick={() =>
                  loaStatus?.finalFileSeq &&
                  onDownloadFile(loaStatus.finalFileSeq, `LoA_final_${loaStatus.applicationSeq}`)
                }
              >
                Download
              </Button>
            </div>
          ) : (
            <Badge variant="gray">
              <Clock className="h-3.5 w-3.5" /> Pending
            </Badge>
          )}
        </div>

        <div className="mt-3">
          <input
            ref={finalInputRef}
            type="file"
            accept=".pdf,.jpg,.jpeg,.png"
            className="hidden"
            onChange={handleFinalSelect}
          />
          {selectedFinal ? (
            <div className="flex items-center gap-2">
              <div className="flex min-w-0 flex-1 items-center gap-2 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
                <FileText className="h-4 w-4 flex-shrink-0 text-gray-400" />
                <span className="truncate text-sm text-gray-700">{selectedFinal.name}</span>
                <button
                  type="button"
                  onClick={() => setSelectedFinal(null)}
                  className="ml-auto flex-shrink-0 text-gray-400 hover:text-red-500"
                  aria-label="Remove selected file"
                >
                  ✕
                </button>
              </div>
              <Button
                size="sm"
                onClick={handleFinalUpload}
                loading={uploadingFinal}
                leftIcon={<Upload className="h-4 w-4" />}
              >
                Upload
              </Button>
            </div>
          ) : (
            <Button
              size="sm"
              variant="outline"
              onClick={() => finalInputRef.current?.click()}
              leftIcon={<Upload className="h-4 w-4" />}
            >
              {finalUploaded ? 'Replace final LoA' : 'Upload final LoA'}
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
}
