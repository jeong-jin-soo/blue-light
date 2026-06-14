import { useRef, useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge, type BadgeVariant } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Download, Upload, FileText, CheckCircle2 } from 'lucide-react';
import fileApi from '../../../api/fileApi';
import loaApi from '../../../api/loaApi';
import { useToastStore } from '../../../stores/toastStore';
import type { Application, LoaStage, LoaStatus } from '../../../types';

interface Props {
  application: Application;
  loaStatus: LoaStatus | null;
  onStatusUpdate: () => void;
}

const stageMeta: Record<LoaStage, { label: string; variant: BadgeVariant }> = {
  NOT_STARTED: { label: 'Pending', variant: 'gray' },
  FORM_SENT: { label: 'Action required', variant: 'warning' },
  APPLICANT_UPLOADED: { label: 'Uploaded', variant: 'info' },
  FINAL_UPLOADED: { label: 'Completed', variant: 'success' },
};

/**
 * Applicant LoA (Letter of Appointment) 섹션 — 교환 모델 (loa-exchange-redesign-spec.md §4.2, PR3b).
 *
 * <p>인앱 디지털 서명 폐기. NEW: LEW 가 폼을 전달하면 ① active 폼 다운로드 → 오프라인 서명 →
 * ② 서명본 업로드. RENEWAL: 폼 다운로드 없이 서명본 업로드(또는 Documents 섹션으로 안내).</p>
 */
export function ApplicationLoaSection({ application, loaStatus, onStatusUpdate }: Props) {
  const toast = useToastStore();
  const isRenewal = application.applicationType === 'RENEWAL';
  const stage: LoaStage = loaStatus?.loaStage ?? 'NOT_STARTED';
  const meta = stageMeta[stage];

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const applicantUploaded = !!loaStatus?.applicantFileSeq;
  // NEW: LEW 가 폼을 전달했거나 이미 업로드를 시작한 단계여야 업로드 UI 노출.
  const canUpload = isRenewal || stage !== 'NOT_STARTED';
  // NEW + active 폼 존재 시 폼 다운로드 노출.
  const showFormDownload = !isRenewal && !!loaStatus?.activeFormAvailable;

  const handleDownloadForm = async () => {
    setDownloading(true);
    try {
      const blob = await loaApi.downloadActiveLoaForm(application.applicationSeq);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `LoA_form_${application.applicationSeq}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch {
      toast.error('Failed to download LoA form');
    } finally {
      setDownloading(false);
    }
  };

  const handleDownloadApplicant = async () => {
    if (!loaStatus?.applicantFileSeq) return;
    try {
      await fileApi.downloadFile(loaStatus.applicantFileSeq, `LoA_${application.applicationSeq}`);
    } catch {
      toast.error('Failed to download LoA');
    }
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) setSelectedFile(file);
    e.target.value = '';
  };

  const handleUpload = async () => {
    if (!selectedFile) return;
    setUploading(true);
    try {
      await loaApi.uploadApplicantLoa(application.applicationSeq, selectedFile);
      toast.success('Signed LoA uploaded');
      setSelectedFile(null);
      onStatusUpdate();
    } catch {
      toast.error('Failed to upload signed LoA');
    } finally {
      setUploading(false);
    }
  };

  return (
    <Card>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment</h2>
        <Badge variant={meta.variant}>{meta.label}</Badge>
      </div>

      {/* 안내 — 아직 폼 전달 전 (NEW) */}
      {!isRenewal && stage === 'NOT_STARTED' && (
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
          <p className="text-sm text-gray-600">
            Your LEW will share the Letter of Appointment form once your application has been reviewed.
            You will then download it, sign it offline, and upload the signed copy here.
          </p>
        </div>
      )}

      {/* RENEWAL 안내 */}
      {isRenewal && !applicantUploaded && (
        <div className="mb-3 rounded-lg border border-gray-200 bg-gray-50 p-4">
          <p className="text-sm text-gray-600">
            Upload your signed Letter of Appointment below, or from the Documents section.
          </p>
        </div>
      )}

      {/* Step 1: download active form (NEW) */}
      {showFormDownload && (
        <div className="mb-3 rounded-lg border border-gray-200 p-4">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-sm font-medium text-gray-800">1. Download the LoA form</p>
              <p className="mt-0.5 text-xs text-gray-500">
                Download, print, and sign the form offline.
                {loaStatus?.activeFormLabel ? ` (${loaStatus.activeFormLabel})` : ''}
              </p>
            </div>
            <Button
              size="sm"
              variant="outline"
              loading={downloading}
              leftIcon={<Download className="h-4 w-4" />}
              onClick={handleDownloadForm}
            >
              Download form
            </Button>
          </div>
        </div>
      )}

      {/* Step 2: upload signed LoA */}
      {canUpload && (
        <div className="rounded-lg border border-gray-200 p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-sm font-medium text-gray-800">
                {showFormDownload ? '2. Upload signed LoA' : 'Upload signed LoA'}
              </p>
              <p className="mt-0.5 text-xs text-gray-500">
                {applicantUploaded
                  ? 'Your signed LoA has been received. You can replace it if needed.'
                  : 'Upload the signed copy (PDF, JPG, or PNG).'}
              </p>
            </div>
            {applicantUploaded && (
              <Badge variant="success">
                <CheckCircle2 className="h-3.5 w-3.5" /> Received
              </Badge>
            )}
          </div>

          <div className="mt-3">
            <input
              ref={fileInputRef}
              type="file"
              accept=".pdf,.jpg,.jpeg,.png"
              className="hidden"
              onChange={handleFileSelect}
            />
            {selectedFile ? (
              <div className="flex items-center gap-2">
                <div className="flex min-w-0 flex-1 items-center gap-2 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
                  <FileText className="h-4 w-4 flex-shrink-0 text-gray-400" />
                  <span className="truncate text-sm text-gray-700">{selectedFile.name}</span>
                  <button
                    type="button"
                    onClick={() => setSelectedFile(null)}
                    className="ml-auto flex-shrink-0 text-gray-400 hover:text-red-500"
                    aria-label="Remove selected file"
                  >
                    ✕
                  </button>
                </div>
                <Button
                  size="sm"
                  onClick={handleUpload}
                  loading={uploading}
                  leftIcon={<Upload className="h-4 w-4" />}
                >
                  Upload
                </Button>
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => fileInputRef.current?.click()}
                  leftIcon={<Upload className="h-4 w-4" />}
                >
                  {applicantUploaded ? 'Replace signed LoA' : 'Upload signed LoA'}
                </Button>
                {applicantUploaded && (
                  <Button
                    size="sm"
                    variant="ghost"
                    leftIcon={<Download className="h-4 w-4" />}
                    onClick={handleDownloadApplicant}
                  >
                    Download
                  </Button>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </Card>
  );
}
