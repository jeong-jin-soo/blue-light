import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import type { AdminApplication, LoaStatus } from '../../../types';

interface Props {
  application: AdminApplication;
  loaStatus: LoaStatus | null;
  onGenerate: () => Promise<void>;
  onDownload: (fileSeq: number, filename: string) => void;
  generating: boolean;
}

/**
 * Admin/LEW LOA (Letter of Appointment) 섹션
 * - LOA 생성, 상태 확인, 다운로드
 */
export function AdminLoaSection({ application, loaStatus, onGenerate, onDownload, generating }: Props) {
  const lewAssigned = !!application.assignedLewSeq;
  const profileComplete = !!(
    application.userCompanyName &&
    application.userUen &&
    application.userDesignation &&
    application.userCorrespondenceAddress
  );

  const canGenerate = lewAssigned && profileComplete;

  return (
    <Card>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment (LOA)</h2>
        {loaStatus?.loaSigned && (
          <Badge variant="success">Signed</Badge>
        )}
        {loaStatus?.loaGenerated && !loaStatus?.loaSigned && (
          <Badge variant="warning">Awaiting Signature</Badge>
        )}
      </div>

      {/* LEW 미할당 경고 */}
      {!lewAssigned && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
          <div className="flex items-start gap-2">
            <span className="text-sm">ℹ️</span>
            <div>
              <p className="text-sm font-medium text-gray-700">LEW Assignment Required</p>
              <p className="text-xs text-gray-500 mt-0.5">
                A LEW must be assigned to this application before generating the LOA.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* 프로필 미완성 경고 */}
      {lewAssigned && !profileComplete && (
        <div className="bg-warning-50 border border-warning-200 rounded-lg p-4">
          <div className="flex items-start gap-2">
            <span className="text-sm">⚠️</span>
            <div>
              <p className="text-sm font-medium text-warning-800">Incomplete Applicant Profile</p>
              <p className="text-xs text-warning-700 mt-0.5">
                The following are required for LOA:{' '}
                {[
                  !application.userCompanyName && 'Company Name',
                  !application.userUen && 'UEN',
                  !application.userDesignation && 'Designation',
                  !application.userCorrespondenceAddress && 'Correspondence Address',
                ].filter(Boolean).join(', ')}.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* LOA 생성 가능 — 아직 미생성 */}
      {canGenerate && !loaStatus?.loaGenerated && (
        <div className="space-y-3">
          <p className="text-sm text-gray-600">
            Generate the LOA document with applicant and LEW details. The applicant will be able to sign it digitally.
          </p>
          <button
            onClick={onGenerate}
            disabled={generating}
            className="inline-flex items-center gap-2 px-4 py-2 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {generating ? (
              <>
                <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                Generating...
              </>
            ) : (
              <>📄 Generate LOA</>
            )}
          </button>
        </div>
      )}

      {/* LOA 생성 완료 */}
      {loaStatus?.loaGenerated && (
        <div className="space-y-3">
          {/* 다운로드 */}
          <div className="flex items-center justify-between bg-gray-50 rounded-lg p-3 border border-gray-100">
            <div className="flex items-center gap-2">
              <span className="text-lg">📄</span>
              <div>
                <p className="text-sm font-medium text-gray-800">
                  LOA_{application.applicationSeq}.pdf
                </p>
                <p className="text-xs text-gray-500">
                  {loaStatus.loaSigned
                    ? `Signed on ${new Date(loaStatus.loaSignedAt!).toLocaleDateString()}`
                    : 'Waiting for applicant signature'}
                </p>
              </div>
            </div>
            <button
              onClick={() => loaStatus.loaFileSeq && onDownload(
                loaStatus.loaFileSeq,
                `LOA_${application.applicationSeq}.pdf`
              )}
              className="text-sm text-primary-600 hover:text-primary-700 font-medium"
            >
              Download
            </button>
          </div>

          {/* 서명 상태 상세 */}
          {loaStatus.loaSigned && (
            <div className="flex items-center gap-2 text-sm text-green-700 bg-green-50 rounded-lg px-3 py-2">
              <span>✅</span>
              <span>
                Applicant signed on{' '}
                {new Date(loaStatus.loaSignedAt!).toLocaleDateString('en-SG', {
                  year: 'numeric', month: 'short', day: 'numeric',
                  hour: '2-digit', minute: '2-digit',
                })}
              </span>
            </div>
          )}

          {/* 재생성 버튼 (미서명 상태에서만) */}
          {!loaStatus.loaSigned && (
            <button
              onClick={onGenerate}
              disabled={generating}
              className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-700 disabled:opacity-50"
            >
              🔄 Regenerate LOA
            </button>
          )}
        </div>
      )}
    </Card>
  );
}
