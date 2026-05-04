import { useEffect, useMemo, useRef, useState } from 'react';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../ui/Modal';
import { Button } from '../../ui/Button';
import { LoadingSpinner } from '../../ui/LoadingSpinner';
import { previewManualEmail } from '../../../api/adminManualEmailApi';
import type { ManualEmailPreviewResponse, SendManualEmailRequest } from '../../../types/manualEmail';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onEdit: () => void;
  payload: SendManualEmailRequest | null;
}

/**
 * 발송 전 미리보기 모달 — POST /api/admin/manual-emails/preview 결과를 iframe sandbox 로 렌더한다.
 *
 * <p>스펙: doc/Project Analysis/admin-manual-email-spec.md §5.4 / §7.2.1.</p>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>iframe {@code sandbox=""} (특권 일체 비활성) + {@code srcDoc} 으로 HTML 주입.</li>
 *   <li>네트워크/스크립트/폼/팝업 모두 차단 — XSS·악성 링크 클릭 불가.</li>
 *   <li>본문은 백엔드 {@link ManualEmailHtmlRenderer} 에서 이미 escape 된 안전한 HTML.</li>
 * </ul>
 */
export function ManualEmailPreviewModal({ isOpen, onClose, onEdit, payload }: Props) {
  const [data, setData] = useState<ManualEmailPreviewResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const requestSeq = useRef(0);

  useEffect(() => {
    if (!isOpen || !payload) return;
    const seq = ++requestSeq.current;
    let cancelled = false;
    const run = async () => {
      setLoading(true);
      setError(null);
      setData(null);
      try {
        const resp = await previewManualEmail(payload);
        if (!cancelled && seq === requestSeq.current) setData(resp);
      } catch (err) {
        if (!cancelled && seq === requestSeq.current) {
          const msg = (err as { message?: string })?.message || 'Failed to render preview';
          setError(msg);
        }
      } finally {
        if (!cancelled && seq === requestSeq.current) setLoading(false);
      }
    };
    void run();
    return () => {
      cancelled = true;
    };
  }, [isOpen, payload]);

  const subjectLine = useMemo(() => data?.renderedSubject ?? payload?.subject ?? '', [data, payload]);

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="lg">
      <ModalHeader title="Email preview" onClose={onClose} />
      <ModalBody className="!p-0">
        <div className="px-4 py-3 sm:px-6 border-b border-gray-100 bg-gray-50 text-sm">
          <div className="text-gray-500">Subject</div>
          <div className="font-medium text-gray-900 break-words">{subjectLine || '(no subject)'}</div>
        </div>
        <div className="p-4 sm:p-6 min-h-[300px]">
          {loading && (
            <div className="flex items-center justify-center py-12 text-gray-500 gap-2">
              <LoadingSpinner /> <span>Rendering preview…</span>
            </div>
          )}
          {error && (
            <div className="rounded-md bg-red-50 border border-red-200 px-3 py-2 text-sm text-red-700">
              {error}
            </div>
          )}
          {!loading && !error && data && (
            <iframe
              title="manual-email-preview"
              sandbox=""
              srcDoc={data.renderedHtmlPreview}
              className="w-full h-[480px] border border-gray-200 rounded-md bg-white"
            />
          )}
        </div>
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" size="sm" onClick={onEdit} disabled={loading}>
          Edit
        </Button>
        <Button size="sm" onClick={onClose} disabled={loading}>
          Close
        </Button>
      </ModalFooter>
    </Modal>
  );
}
