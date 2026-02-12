import { useState, useRef } from 'react';
import { Card } from '../../../components/ui/Card';
import { Badge } from '../../../components/ui/Badge';
import SignaturePad, { type SignaturePadHandle } from '../../../components/domain/SignaturePad';
import loaApi from '../../../api/loaApi';
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
 * - LOA 다운로드, 전자서명, 서명 완료 상태
 */
export function ApplicationLoaSection({ application, loaStatus, onStatusUpdate }: Props) {
  const toast = useToastStore();
  const signatureRef = useRef<SignaturePadHandle>(null);
  const [signing, setSigning] = useState(false);
  const [hasSignature, setHasSignature] = useState(false);

  const [showConfirm, setShowConfirm] = useState(false);

  const handleSignLoa = () => {
    if (!signatureRef.current || signatureRef.current.isEmpty()) {
      toast.error('Please sign before submitting');
      return;
    }
    setShowConfirm(true);
  };

  const handleConfirmSign = async () => {
    setShowConfirm(false);
    if (!signatureRef.current || signatureRef.current.isEmpty()) {
      toast.error('Please sign before submitting');
      return;
    }

    setSigning(true);
    try {
      const blob = await signatureRef.current.toBlob();
      if (!blob) {
        toast.error('Failed to capture signature');
        return;
      }

      await loaApi.signLoa(application.applicationSeq, blob);
      toast.success('LOA signed successfully!');
      onStatusUpdate();
    } catch {
      toast.error('Failed to sign LOA');
    } finally {
      setSigning(false);
    }
  };

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
                ? 'You can upload the LOA from the Documents section below. Once uploaded, you can sign it here.'
                : 'The LOA will be generated once your application has been reviewed and a LEW is assigned. You will be able to sign it digitally here.'}
            </p>
          </div>
        </div>
      </Card>
    );
  }

  // LOA 서명 완료
  if (loaStatus.loaSigned) {
    return (
      <Card>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment</h2>
          <Badge variant="success">Signed</Badge>
        </div>

        <div className="space-y-3">
          {/* 서명 완료 배너 */}
          <div className="flex items-center gap-2 bg-green-50 border border-green-200 rounded-lg px-4 py-3">
            <span>✅</span>
            <div>
              <p className="text-sm font-medium text-green-800">LOA Signed Successfully</p>
              <p className="text-xs text-green-600 mt-0.5">
                Signed on{' '}
                {new Date(loaStatus.loaSignedAt!).toLocaleDateString('en-SG', {
                  year: 'numeric', month: 'short', day: 'numeric',
                  hour: '2-digit', minute: '2-digit',
                })}
              </p>
            </div>
          </div>

          {/* 다운로드 */}
          <button
            onClick={handleDownloadLoa}
            className="flex items-center gap-2 w-full px-4 py-3 bg-gray-50 hover:bg-gray-100 rounded-lg border border-gray-200 transition-colors"
          >
            <span className="text-lg">📄</span>
            <div className="flex-1 text-left">
              <p className="text-sm font-medium text-gray-800">Download Signed LOA</p>
              <p className="text-xs text-gray-500">PDF document for EMA submission</p>
            </div>
            <svg className="w-4 h-4 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
          </button>
        </div>
      </Card>
    );
  }

  // LOA 생성됨 (미서명) — 서명 UI 표시
  return (
    <Card>
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-lg font-semibold text-gray-800">Letter of Appointment</h2>
        <Badge variant="warning">Signature Required</Badge>
      </div>

      <div className="space-y-4">
        {/* LOA 다운로드 (미리보기) */}
        <button
          onClick={handleDownloadLoa}
          className="flex items-center gap-2 w-full px-4 py-3 bg-blue-50 hover:bg-blue-100 rounded-lg border border-blue-200 transition-colors"
        >
          <span className="text-lg">📄</span>
          <div className="flex-1 text-left">
            <p className="text-sm font-medium text-blue-800">Review LOA Document</p>
            <p className="text-xs text-blue-600">Download and review before signing</p>
          </div>
          <svg className="w-4 h-4 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
          </svg>
        </button>

        {/* 서명 영역 */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-2">
            Your Signature
          </label>
          <p className="text-xs text-gray-500 mb-3">
            Please draw your signature below. This will be embedded into the LOA document.
          </p>
          <SignaturePad
            ref={signatureRef}
            onSignatureChange={setHasSignature}
            disabled={signing}
          />
        </div>

        {/* 서명 제출 버튼 */}
        <button
          onClick={handleSignLoa}
          disabled={!hasSignature || signing}
          className="w-full inline-flex items-center justify-center gap-2 px-4 py-3 bg-primary-600 text-white text-sm font-medium rounded-lg hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {signing ? (
            <>
              <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              Signing...
            </>
          ) : (
            <>✍️ Sign LOA</>
          )}
        </button>
      </div>

      {/* 서명 확인 모달 */}
      {showConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="bg-white rounded-xl shadow-xl max-w-md w-full mx-4 p-6">
            <div className="flex items-center gap-3 mb-4">
              <div className="flex-shrink-0 w-10 h-10 rounded-full bg-amber-100 flex items-center justify-center">
                <span className="text-lg">✍️</span>
              </div>
              <h3 className="text-lg font-semibold text-gray-900">Confirm Signature</h3>
            </div>
            <p className="text-sm text-gray-600 mb-2">
              Are you sure you want to sign the LOA with the signature you provided?
            </p>
            <p className="text-xs text-gray-500 mb-6">
              This action cannot be undone. Your signature will be permanently embedded into the LOA document.
            </p>
            <div className="flex gap-3">
              <button
                onClick={() => setShowConfirm(false)}
                className="flex-1 px-4 py-2.5 text-sm font-medium text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleConfirmSign}
                className="flex-1 px-4 py-2.5 text-sm font-medium text-white bg-primary-600 rounded-lg hover:bg-primary-700 transition-colors"
              >
                Confirm & Sign
              </button>
            </div>
          </div>
        </div>
      )}
    </Card>
  );
}
