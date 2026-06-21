import { Card } from '../../../components/ui/Card';
import { Badge, type BadgeVariant } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Download } from 'lucide-react';
import fileApi from '../../../api/fileApi';
import { useToastStore } from '../../../stores/toastStore';
import type { Application, LoaStage, LoaStatus } from '../../../types';

interface Props {
  application: Application;
  loaStatus: LoaStatus | null;
}

const stageMeta: Record<LoaStage, { label: string; variant: BadgeVariant }> = {
  NOT_STARTED: { label: 'Pending', variant: 'gray' },
  FINAL_UPLOADED: { label: 'Completed', variant: 'success' },
};

/**
 * Applicant Letter of Appointment 섹션 — 읽기 전용 상태 요약.
 *
 * <p>폼 다운로드·서명본 업로드는 위 <b>Documents</b> 섹션의 "Letter of Appointment" 서류 요청으로
 * 일원화되었다. 이 섹션은 진행 상태와 (LEW 최종본이 있으면) 최종본 다운로드만 제공한다.</p>
 */
export function ApplicationLoaSection({ application, loaStatus }: Props) {
  const toast = useToastStore();
  const isRenewal = application.applicationType === 'RENEWAL';
  const stage: LoaStage = loaStatus?.loaStage ?? 'NOT_STARTED';
  const meta = stageMeta[stage];
  const finalAvailable = !!loaStatus?.finalFileSeq;

  const handleDownloadFinal = async () => {
    if (!loaStatus?.finalFileSeq) return;
    try {
      await fileApi.downloadFile(loaStatus.finalFileSeq, `LoA_final_${application.applicationSeq}`);
    } catch {
      toast.error('Failed to download the final Letter of Appointment');
    }
  };

  return (
    <Card>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment</h2>
        <Badge variant={meta.variant}>{meta.label}</Badge>
      </div>

      <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
        <p className="text-sm text-gray-600">
          Your Letter of Appointment is handled in the <strong>Documents</strong> section above.{' '}
          {isRenewal
            ? 'When your LEW requests it, upload your signed copy there.'
            : 'When your LEW requests it, download the form there, sign it offline, and upload the signed copy.'}
        </p>

        {finalAvailable && (
          <div className="mt-3">
            <Button
              size="sm"
              variant="outline"
              leftIcon={<Download className="h-4 w-4" />}
              onClick={handleDownloadFinal}
            >
              Download final LoA
            </Button>
          </div>
        )}
      </div>
    </Card>
  );
}
