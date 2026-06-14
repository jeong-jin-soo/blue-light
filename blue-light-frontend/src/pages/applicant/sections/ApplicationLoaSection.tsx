import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import fileApi from '../../../api/fileApi';
import { useToastStore } from '../../../stores/toastStore';
import type { Application, LoaStatus } from '../../../types';

interface Props {
  application: Application;
  loaStatus: LoaStatus | null;
  onStatusUpdate: () => void;
}

/**
 * Applicant LOA (Letter of Appointment) 섹션
 * - LOA 문서 다운로드만 제공. 인앱 전자서명 기능은 제거됨 (2026-06-13).
 */
export function ApplicationLoaSection({ application, loaStatus }: Props) {
  const toast = useToastStore();

  const handleDownloadLoa = async () => {
    if (!loaStatus?.loaFileSeq) return;
    try {
      await fileApi.downloadFile(
        loaStatus.loaFileSeq,
        `LOA_${application.applicationSeq}.pdf`
      );
    } catch {
      toast.error('Failed to download LOA');
    }
  };

  // LOA 미생성
  if (!loaStatus?.loaGenerated) {
    return (
      <Card>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment</h2>
          <Badge variant="gray">Pending</Badge>
        </div>
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
          <div className="flex items-start gap-2">
            <span className="text-sm">ℹ️</span>
            <p className="text-sm text-gray-600">
              {application.applicationType === 'RENEWAL'
                ? 'You can upload the LOA from the Documents section below. Once it is ready, you can download it here.'
                : 'The LOA will be generated once your application has been reviewed and a LEW is assigned. You will be able to download it here.'}
            </p>
          </div>
        </div>
      </Card>
    );
  }

  // LOA 생성됨 — 문서 다운로드
  return (
    <Card>
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment</h2>
        <Badge variant="gray">Ready</Badge>
      </div>

      <button
        onClick={handleDownloadLoa}
        className="flex items-center gap-2 w-full px-4 py-3 bg-gray-50 hover:bg-gray-100 rounded-lg border border-gray-200 transition-colors"
      >
        <span className="text-lg">📄</span>
        <div className="flex-1 text-left">
          <p className="text-sm font-medium text-gray-800">Download LOA</p>
          <p className="text-xs text-gray-500">PDF document for your records</p>
        </div>
        <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
      </button>
    </Card>
  );
}
