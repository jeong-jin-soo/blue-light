import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../ui/Modal';
import { Button } from '../../ui/Button';
import { Badge } from '../../ui/Badge';
import { LoadingSpinner } from '../../ui/LoadingSpinner';
import { getManualEmailDetail } from '../../../api/adminManualEmailApi';
import type { ManualEmailDispatchHistoryItem } from '../../../types/manualEmail';
import { statusBadgeVariant, statusLabel } from './statusBadge';

interface Props {
  isOpen: boolean;
  dispatchSeq: number | null;
  onClose: () => void;
}

/**
 * 발송 이력 상세 모달 — 전체 본문 + 수신자 풀 리스트 + 실패 사유 + 신청 링크.
 *
 * <p>스펙: doc/Project Analysis/admin-manual-email-spec.md §7.2.2 / §5.3.</p>
 */
export function ManualEmailHistoryDetailModal({ isOpen, dispatchSeq, onClose }: Props) {
  const [item, setItem] = useState<ManualEmailDispatchHistoryItem | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen || !dispatchSeq) return;
    let cancelled = false;
    const run = async () => {
      setLoading(true);
      setError(null);
      setItem(null);
      try {
        const detail = await getManualEmailDetail(dispatchSeq);
        if (!cancelled) setItem(detail);
      } catch (e) {
        if (!cancelled) setError((e as { message?: string })?.message || 'Failed to load detail');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void run();
    return () => {
      cancelled = true;
    };
  }, [isOpen, dispatchSeq]);

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="lg">
      <ModalHeader title={`Manual email #${dispatchSeq ?? ''}`} onClose={onClose} />
      <ModalBody>
        {loading && (
          <div className="flex items-center justify-center py-8 gap-2 text-gray-500">
            <LoadingSpinner /> Loading…
          </div>
        )}
        {error && (
          <div className="rounded-md bg-red-50 border border-red-200 px-3 py-2 text-sm text-red-700">
            {error}
          </div>
        )}
        {item && (
          <div className="space-y-4 text-sm">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <div className="text-xs text-gray-500">Status</div>
                <Badge variant={statusBadgeVariant(item.dispatchStatus)}>
                  {statusLabel(item.dispatchStatus)}
                </Badge>
                <span className="ml-2 text-xs text-gray-500">
                  {item.sentCount} sent / {item.failedCount} failed
                </span>
              </div>
              <div>
                <div className="text-xs text-gray-500">Sent at</div>
                <div className="text-gray-800">{item.dispatchedAt ?? '(pending)'}</div>
              </div>
              <div>
                <div className="text-xs text-gray-500">Recipient type</div>
                <div className="text-gray-800">{item.recipientType}</div>
              </div>
              <div>
                <div className="text-xs text-gray-500">Total recipients</div>
                <div className="text-gray-800">{item.recipientCount}</div>
              </div>
              {item.categoryTag && (
                <div>
                  <div className="text-xs text-gray-500">Category</div>
                  <div className="text-gray-800">{item.categoryTag}</div>
                </div>
              )}
              {item.relatedApplicationSeq && (
                <div>
                  <div className="text-xs text-gray-500">Related application</div>
                  <Link
                    to={`/admin/applications/${item.relatedApplicationSeq}`}
                    className="text-primary hover:underline"
                  >
                    #{item.relatedApplicationSeq}
                  </Link>
                </div>
              )}
            </div>

            <div>
              <div className="text-xs text-gray-500">Subject</div>
              <div className="text-gray-900 font-medium break-words">{item.subject}</div>
            </div>

            <div>
              <div className="text-xs text-gray-500 mb-1">Body</div>
              <pre className="p-3 bg-gray-50 border border-gray-200 rounded-md text-xs text-gray-800 max-h-72 overflow-auto whitespace-pre-wrap break-words">
                {item.bodyText}
              </pre>
            </div>

            <div>
              <div className="text-xs text-gray-500 mb-1">
                Recipients ({item.recipientCount})
              </div>
              <div className="flex flex-wrap gap-1.5">
                {item.recipientUserSeqs && item.recipientUserSeqs.length > 0 ? (
                  // MULTI: emails 배열에 시스템 사용자 + 외부 모두 포함됨 (백엔드 resolveAllRecipientEmails)
                  (item.recipientEmails ?? []).map((email, idx) => (
                    <span
                      key={`m-${idx}-${email}`}
                      className="inline-flex items-center px-2 py-0.5 rounded-full bg-gray-100 text-gray-800 text-xs border border-gray-200"
                    >
                      {email}
                    </span>
                  ))
                ) : item.recipientEmails && item.recipientEmails.length > 0 ? (
                  // EXTERNAL/MULTI without system users
                  item.recipientEmails.map((email, idx) => (
                    <span
                      key={`x-${idx}-${email}`}
                      className="inline-flex items-center px-2 py-0.5 rounded-full bg-gray-100 text-gray-800 text-xs border border-gray-200"
                    >
                      {email}
                    </span>
                  ))
                ) : (
                  // 단일
                  <span className="inline-flex items-center px-2 py-0.5 rounded-full bg-gray-100 text-gray-800 text-xs border border-gray-200">
                    {item.recipientEmail ?? '(unknown)'}
                  </span>
                )}
              </div>
            </div>

            {item.failedReason && (
              <div>
                <div className="text-xs text-gray-500 mb-1">Failure reason</div>
                <pre className="p-3 bg-red-50 border border-red-200 rounded-md text-xs text-red-800 max-h-40 overflow-auto whitespace-pre-wrap break-words">
                  {item.failedReason}
                </pre>
              </div>
            )}
          </div>
        )}
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" size="sm" onClick={onClose}>
          Close
        </Button>
      </ModalFooter>
    </Modal>
  );
}
