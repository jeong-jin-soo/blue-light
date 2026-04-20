/**
 * ConciergeLoaCollectionPanel
 * - Kaki Concierge v1.5 Phase 1 PR#6 Stage B
 * - LOA 서명 수집 인라인 패널 (Modal 아님).
 * - 3단계 UI:
 *   1) Application 없음 → 안내
 *   2) LOA 미생성 → 안내 (Admin에게 요청) — Phase 1: Manager는 generate 권한 없음
 *   3) LOA 생성됨 + 미서명 → 두 옵션 (신청자 직접 서명 안내 / Manager 대리 업로드 폼)
 *   4) 서명 완료 → 완료 표시
 */

import { useCallback, useEffect, useState, type ChangeEvent } from 'react';
import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Badge } from '../../../components/ui/Badge';
import loaApi from '../../../api/loaApi';
import type { LoaStatus, ApiError } from '../../../types';

interface Props {
  applicationSeq: number | null;
  /** 부모에게 상태 변경 알림 (detail 재조회) */
  onChange?: () => void;
}

interface NormalizedHttpError {
  response?: { status?: number; data?: ApiError };
  code?: string;
  message?: string;
}

const MAX_FILE_BYTES = 2 * 1024 * 1024; // 2MB

function errMsg(err: unknown, fallback: string): string {
  const e = err as NormalizedHttpError;
  const code = e.code ?? e.response?.data?.code;
  const msg = e.response?.data?.message ?? e.message;
  if (code === 'LOA_ALREADY_SIGNED') return 'LOA has already been signed.';
  if (code === 'LOA_NOT_FOUND') return 'Generate the LOA PDF first (ask an admin).';
  if (code === 'CONCIERGE_NOT_ASSIGNED') return 'This application is not assigned to you.';
  if (code === 'NOT_VIA_CONCIERGE') return 'This application was not created via concierge.';
  if (code === 'ACKNOWLEDGEMENT_REQUIRED') return 'Please confirm receipt from the applicant.';
  if (code === 'FORBIDDEN') return 'You do not have permission to upload this signature.';
  return msg ?? fallback;
}

export function ConciergeLoaCollectionPanel({ applicationSeq, onChange }: Props) {
  const [loaStatus, setLoaStatus] = useState<LoaStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Upload form state
  const [file, setFile] = useState<File | null>(null);
  const [memo, setMemo] = useState('');
  const [acknowledged, setAcknowledged] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (!applicationSeq) return;
    try {
      setLoading(true);
      setLoadError(null);
      const status = await loaApi.getLoaStatus(applicationSeq);
      setLoaStatus(status);
    } catch (err) {
      setLoadError(errMsg(err, 'Failed to load LOA status'));
      setLoaStatus(null);
    } finally {
      setLoading(false);
    }
  }, [applicationSeq]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    setUploadError(null);
    const selected = e.target.files?.[0] ?? null;
    if (selected && selected.size > MAX_FILE_BYTES) {
      setUploadError('File exceeds 2 MB limit.');
      setFile(null);
      e.target.value = '';
      return;
    }
    setFile(selected);
  };

  const handleUpload = async () => {
    if (!applicationSeq || !file || !acknowledged) return;
    setUploading(true);
    setUploadError(null);
    try {
      await loaApi.uploadLoaSignatureByManager(applicationSeq, {
        signature: file,
        memo,
        acknowledgeReceipt: true,
      });
      await reload();
      onChange?.();
      // 성공 후 폼 초기화
      setFile(null);
      setMemo('');
      setAcknowledged(false);
    } catch (err) {
      setUploadError(errMsg(err, 'Failed to upload signature'));
    } finally {
      setUploading(false);
    }
  };

  const statusBadge = () => {
    if (!loaStatus) return null;
    if (loaStatus.loaSigned) return <Badge variant="success">Signed</Badge>;
    if (loaStatus.loaGenerated) return <Badge variant="warning">Awaiting signature</Badge>;
    return <Badge variant="gray">Not generated</Badge>;
  };

  // ── 분기 1: Application 없음 ──
  if (!applicationSeq) {
    return (
      <Card padding="md">
        <h3 className="text-sm font-semibold text-gray-900 mb-1">LOA signature collection</h3>
        <p className="text-xs text-gray-600">
          Create an application first. LOA signature collection becomes available after the
          application is created.
        </p>
      </Card>
    );
  }

  return (
    <Card padding="md">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-gray-900">LOA signature collection</h3>
        {statusBadge()}
      </div>

      {loadError && (
        <div
          role="alert"
          className="mb-3 p-2 rounded bg-error-50 border border-error-200 text-xs text-error-700"
        >
          {loadError}
        </div>
      )}

      {loading && <p className="text-xs text-gray-500">Loading...</p>}

      {/* ── 분기 2: LOA 미생성 ── */}
      {!loading && loaStatus && !loaStatus.loaGenerated && (
        <div className="p-3 rounded border border-gray-200 bg-gray-50">
          <p className="text-xs text-gray-700">
            The LOA PDF has not been generated yet. Please ask an administrator to generate it
            via the application detail page.
          </p>
          <p className="text-xs text-gray-500 mt-2">
            <em>Phase 1: Only administrators can generate the LOA PDF.</em>
          </p>
        </div>
      )}

      {/* ── 분기 3: LOA 생성됨 + 미서명 — 두 옵션 ── */}
      {!loading && loaStatus && loaStatus.loaGenerated && !loaStatus.loaSigned && (
        <div className="space-y-4">
          {/* Option 1 — 신청자 직접 서명 안내 */}
          <div className="p-3 rounded border border-gray-200 bg-gray-50">
            <div className="text-xs font-semibold text-gray-700 mb-1">
              Option 1 — Applicant signs directly
            </div>
            <p className="text-xs text-gray-600">
              The applicant logs in and signs the LOA from their dashboard. They already received
              an activation link via email at the time of request.
            </p>
          </div>

          {/* Option 2 — Manager 대리 업로드 */}
          <div className="p-3 rounded border-2 border-concierge-300 bg-concierge-50">
            <div className="text-xs font-semibold text-concierge-800 mb-2">
              Option 2 — Upload signature on behalf
            </div>
            <p className="text-xs text-gray-700 mb-3">
              If the applicant emailed or handed you a signature image, upload it here. They will
              receive a confirmation email with a 7-day window to dispute.
            </p>

            <div className="space-y-3">
              <div>
                <label
                  htmlFor="loa-signature-file"
                  className="block text-xs text-gray-700 mb-1"
                >
                  Signature image <span className="text-gray-500">(PNG/JPG, max 2MB)</span>
                </label>
                <input
                  id="loa-signature-file"
                  type="file"
                  accept="image/png,image/jpeg"
                  onChange={handleFileChange}
                  disabled={uploading}
                  className="block text-xs"
                />
                {file && (
                  <p className="mt-1 text-xs text-gray-500">
                    Selected: {file.name} ({Math.round(file.size / 1024)} KB)
                  </p>
                )}
              </div>

              <div>
                <label
                  htmlFor="loa-upload-memo"
                  className="block text-xs text-gray-700 mb-1"
                >
                  Memo <span className="text-gray-500">(optional)</span>
                </label>
                <textarea
                  id="loa-upload-memo"
                  value={memo}
                  onChange={(e) => setMemo(e.target.value)}
                  rows={2}
                  maxLength={500}
                  disabled={uploading}
                  placeholder="e.g. Received via email on 2026-04-20 from applicant"
                  className="w-full rounded border border-gray-300 px-2 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-concierge-400/40 focus:border-concierge-500"
                />
                <p className="mt-1 text-xs text-gray-500">{memo.length}/500</p>
              </div>

              <label className="flex items-start gap-2 text-xs text-gray-700 cursor-pointer">
                <input
                  type="checkbox"
                  checked={acknowledged}
                  onChange={(e) => setAcknowledged(e.target.checked)}
                  disabled={uploading}
                  className="mt-0.5 h-4 w-4 rounded border-gray-300 text-concierge-500 focus:ring-concierge-400"
                />
                <span>
                  I acknowledge that the applicant{' '}
                  <strong>provided this signature directly</strong> (via email, in-person, or other
                  agreed channel).
                </span>
              </label>

              {uploadError && (
                <div
                  role="alert"
                  className="p-2 rounded bg-error-50 border border-error-200 text-xs text-error-700"
                >
                  {uploadError}
                </div>
              )}

              <Button
                variant="concierge"
                size="sm"
                onClick={handleUpload}
                disabled={!file || !acknowledged || uploading}
                loading={uploading}
              >
                Upload signature
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ── 분기 4: 서명 완료 ── */}
      {!loading && loaStatus && loaStatus.loaSigned && (
        <div className="p-3 rounded border border-success-200 bg-success-50">
          <p className="text-xs text-success-700">
            LOA signed{loaStatus.loaSignedAt && (
              <>
                {' '}at{' '}
                <strong>{new Date(loaStatus.loaSignedAt).toLocaleString()}</strong>
              </>
            )}
            .
          </p>
        </div>
      )}
    </Card>
  );
}

export default ConciergeLoaCollectionPanel;
