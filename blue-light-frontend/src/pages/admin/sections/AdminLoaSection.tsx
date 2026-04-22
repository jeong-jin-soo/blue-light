import { useState, useRef } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import type { AdminApplication, LoaStatus } from '../../../types';

interface Props {
  application: AdminApplication;
  loaStatus: LoaStatus | null;
  onGenerate: () => Promise<void>;
  onUploadLoa: (file: File) => Promise<void>;
  onDownload: (fileSeq: number, filename: string) => void;
  generating: boolean;
  uploading: boolean;
}

/**
 * Admin/LEW LOA (Letter of Appointment) 섹션
 * - NEW: LOA 자동 생성 + 서명
 * - RENEWAL: LOA 업로드 (관계기관에서 받은 문서) + 서명
 */
export function AdminLoaSection({
  application, loaStatus, onGenerate, onUploadLoa, onDownload, generating, uploading,
}: Props) {
  const isRenewal = application.applicationType === 'RENEWAL';
  const lewAssigned = !!application.assignedLewSeq;
  const isCorporate = application.applicantType === 'CORPORATE';
  // CORPORATE만 Company/UEN/Designation 필요. INDIVIDUAL은 무조건 완비된 것으로 간주.
  // Correspondence Address는 Installation address fallback 가능하므로 경고 제외 (V-1).
  const missingCorporateFields: string[] = isCorporate
    ? [
        !application.userCompanyName && 'Company Name',
        !application.userUen && 'UEN',
        !application.userDesignation && 'Designation',
      ].filter((v): v is string => !!v)
    : [];
  const profileComplete = missingCorporateFields.length === 0;
  const canGenerate = lewAssigned && profileComplete;

  // LOA 업로드용 파일 상태 (RENEWAL)
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      if (file.size > 10 * 1024 * 1024) {
        // 10MB 제한
        return;
      }
      setSelectedFile(file);
    }
    e.target.value = '';
  };

  const handleUpload = async () => {
    if (!selectedFile) return;
    await onUploadLoa(selectedFile);
    setSelectedFile(null);
  };

  const handleReplaceLoa = () => {
    fileInputRef.current?.click();
  };

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

      {/* ── NEW 타입: 기존 Generate 워크플로우 ── */}
      {!isRenewal && (
        <>
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

          {/* 프로필 미완성 경고 — CORPORATE 전용 */}
          {lewAssigned && !profileComplete && (
            <div className="bg-warning-50 border border-warning-200 rounded-lg p-4">
              <div className="flex items-start gap-2">
                <span className="text-sm">⚠️</span>
                <div>
                  <p className="text-sm font-medium text-warning-800">Corporate Applicant Profile Incomplete</p>
                  <p className="text-xs text-warning-700 mt-0.5">
                    Missing: {missingCorporateFields.join(', ')}.
                    Ask the applicant to update their profile before generating LOA.
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
        </>
      )}

      {/* ── RENEWAL 타입: 업로드 워크플로우 ── */}
      {isRenewal && !loaStatus?.loaGenerated && (
        <div className="space-y-3">
          <p className="text-sm text-gray-600">
            For renewal applications, upload the LOA document received from the relevant authority. The applicant will be able to sign it digitally.
          </p>

          {/* 파일 선택 영역 */}
          {selectedFile ? (
            <div className="flex items-center justify-between px-3 py-2.5 bg-gray-50 rounded-lg border border-gray-200">
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-lg">📄</span>
                <div className="min-w-0">
                  <p className="text-sm font-medium text-gray-700 truncate">{selectedFile.name}</p>
                  <p className="text-xs text-gray-400">
                    {selectedFile.size < 1024 * 1024
                      ? `${(selectedFile.size / 1024).toFixed(1)} KB`
                      : `${(selectedFile.size / (1024 * 1024)).toFixed(1)} MB`}
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setSelectedFile(null)}
                className="text-gray-400 hover:text-red-500 transition-colors p-1"
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
              <span className="text-sm text-gray-600">Choose LOA file</span>
              <input
                type="file"
                accept=".pdf,.jpg,.jpeg,.png"
                className="hidden"
                onChange={handleFileSelect}
              />
            </label>
          )}

          {/* 업로드 버튼 */}
          {selectedFile && (
            <button
              onClick={handleUpload}
              disabled={uploading}
              className="inline-flex items-center gap-2 px-4 py-2 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {uploading ? (
                <>
                  <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Uploading...
                </>
              ) : (
                <>📤 Upload LOA</>
              )}
            </button>
          )}
        </div>
      )}

      {/* ── 공통: LOA 생성/업로드 완료 ── */}
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

          {/* 미서명 상태에서의 재생성/재업로드 */}
          {!loaStatus.loaSigned && (
            isRenewal ? (
              // RENEWAL: Replace LOA (재업로드)
              <div className="space-y-2">
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".pdf,.jpg,.jpeg,.png"
                  className="hidden"
                  onChange={handleFileSelect}
                />
                {selectedFile ? (
                  <div className="flex items-center gap-2">
                    <div className="flex items-center gap-2 flex-1 min-w-0 px-3 py-2 bg-gray-50 rounded-lg border border-gray-200">
                      <span className="text-sm">📄</span>
                      <span className="text-sm text-gray-700 truncate">{selectedFile.name}</span>
                      <button
                        type="button"
                        onClick={() => setSelectedFile(null)}
                        className="text-gray-400 hover:text-red-500 ml-auto flex-shrink-0"
                        aria-label="Remove selected file"
                      >
                        <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                        </svg>
                      </button>
                    </div>
                    <button
                      onClick={handleUpload}
                      disabled={uploading}
                      className="inline-flex items-center gap-1 px-3 py-2 text-xs font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 disabled:opacity-50"
                    >
                      {uploading ? 'Uploading...' : '📤 Upload'}
                    </button>
                  </div>
                ) : (
                  <button
                    onClick={handleReplaceLoa}
                    disabled={uploading}
                    className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-700 disabled:opacity-50"
                  >
                    🔄 Replace LOA
                  </button>
                )}
              </div>
            ) : (
              // NEW: Regenerate LOA
              <button
                onClick={onGenerate}
                disabled={generating}
                className="inline-flex items-center gap-1 text-xs text-gray-500 hover:text-gray-700 disabled:opacity-50"
              >
                🔄 Regenerate LOA
              </button>
            )
          )}
        </div>
      )}
    </Card>
  );
}
