import { useRef, useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge, type BadgeVariant } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Download, Upload, FileText, CheckCircle2, Clock } from 'lucide-react';
import type { LoaStage, LoaStatus } from '../../../types';

interface Props {
  loaStatus: LoaStatus | null;
  /** LEW 최종본 업로드 (final-upload). */
  onUploadFinal: (file: File) => Promise<void>;
  /** 최종본 다운로드 (fileSeq). */
  onDownloadFile: (fileSeq: number, filename: string) => void;
  uploadingFinal: boolean;
  /** 종결(COMPLETED/EXPIRED) 건 — 업로드/교체 차단, 다운로드만 허용. */
  readOnly?: boolean;
}

const stageMeta: Record<LoaStage, { label: string; variant: BadgeVariant }> = {
  NOT_STARTED: { label: 'Not started', variant: 'gray' },
  FINAL_UPLOADED: { label: 'Final LoA uploaded', variant: 'success' },
};

/**
 * LEW 검토 — Letter of Appointment(LoA) 섹션.
 *
 * <p>신청자에게 폼 전달·서명본 수집은 <b>Documents 탭</b>의 "Letter of Appointment" 서류 요청으로
 * 일원화되었다. 이 섹션은 ① 신청자 서명본 수령 여부(읽기 전용 상태)와 ② LEW 가 보완한
 * <b>최종본 업로드</b>만 담당한다. 신청자 서명본 파일은 Documents 탭에서 다운로드한다.</p>
 */
export function LewLoaExchangeSection({
  loaStatus,
  onUploadFinal,
  onDownloadFile,
  uploadingFinal,
  readOnly = false,
}: Props) {
  const stage: LoaStage = loaStatus?.loaStage ?? 'NOT_STARTED';
  const meta = stageMeta[stage];

  const finalInputRef = useRef<HTMLInputElement>(null);
  const [selectedFinal, setSelectedFinal] = useState<File | null>(null);

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

      {/* ── Upload final LoA ── */}
      <div className="rounded-lg border border-gray-200 p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-medium text-gray-800">Upload final LoA</p>
            <p className="mt-0.5 text-xs text-gray-500">
              Download the applicant's signed copy from the Documents tab, supplement it, and upload the completed LoA here. This is submitted to EMA externally.
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

        {!readOnly && (
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
        )}
      </div>
    </Card>
  );
}
