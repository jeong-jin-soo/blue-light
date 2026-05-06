import { useCallback, useEffect, useMemo, useState } from 'react';
import documentApi from '../../api/documentApi';
import { useToastStore } from '../../stores/toastStore';
import type {
  CreateDocumentRequestItem,
  DocumentRequest,
  DocumentType,
} from '../../types/document';
import { Badge } from '../ui/Badge';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { LoadingSpinner } from '../ui/LoadingSpinner';
import { Modal, ModalBody, ModalFooter, ModalHeader } from '../ui/Modal';
import { Textarea } from '../ui/Textarea';
import { prettyMime } from './documentUtils';

/**
 * Phase 3 PR#2 — LEW 서류 요청 체크리스트 모달 (AC-LU1, LU2, R1, R3, R5)
 *
 * - 7종 Document Type 체크박스 + row memo + OTHER customLabel
 * - 이미 active 상태인 type은 disabled로 중복 요청 사전 차단
 * - 활성 요청 10건 도달 시 소프트 리밋 배너 + 체크박스 전체 disabled
 * - 409 DUPLICATE_ACTIVE_REQUEST / TOO_MANY_ACTIVE_REQUESTS 핸들링
 */

const SOFT_LIMIT = 10;
const MEMO_MAX = 1000;
const CUSTOM_LABEL_MAX = 200;

interface RowState {
  checked: boolean;
  customLabel: string;
  lewNote: string;
  /** 이 row에 대한 inline 에러 (409 중복 등) */
  error?: string;
}

interface DocumentRequestModalProps {
  isOpen: boolean;
  applicationSeq: number;
  applicantDisplayName?: string;
  applicationCode?: string;
  /**
   * 상위 화면이 이미 보유한 active(REQUESTED/UPLOADED) 요청 목록.
   * 모달 내부에서 중복 요청을 UI 레벨에서 차단한다.
   */
  existingActiveRequests: DocumentRequest[];
  onClose: () => void;
  onSuccess: () => void;
}

export function DocumentRequestModal({
  isOpen,
  applicationSeq,
  applicantDisplayName,
  applicationCode,
  existingActiveRequests,
  onClose,
  onSuccess,
}: DocumentRequestModalProps) {
  const toast = useToastStore();

  const [catalog, setCatalog] = useState<DocumentType[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [rows, setRows] = useState<Record<string, RowState>>({});
  const [limitBannerMessage, setLimitBannerMessage] = useState<string | null>(null);

  // 이미 active 상태인 type을 맵으로 빠르게 lookup
  const activeByCode = useMemo(() => {
    const map = new Map<string, DocumentRequest>();
    for (const r of existingActiveRequests) {
      if (r.status === 'REQUESTED' || r.status === 'UPLOADED') {
        // OTHER는 customLabel까지 비교해야 하므로 별도 키
        const key =
          r.documentTypeCode === 'OTHER'
            ? `OTHER::${(r.customLabel ?? '').trim()}`
            : r.documentTypeCode;
        if (!map.has(key)) map.set(key, r);
      }
    }
    return map;
  }, [existingActiveRequests]);

  const activeCount = useMemo(
    () =>
      existingActiveRequests.filter(
        (r) => r.status === 'REQUESTED' || r.status === 'UPLOADED',
      ).length,
    [existingActiveRequests],
  );

  const softLimitReached = activeCount >= SOFT_LIMIT;

  // 모달 열릴 때 catalog fetch + state reset
  useEffect(() => {
    if (!isOpen) return;
    setRows({});
    setLimitBannerMessage(null);
    setLoading(true);
    documentApi
      .getDocumentTypes()
      .then((data) => {
        setCatalog(data);
      })
      .catch(() => {
        toast.error('Failed to load document types.');
      })
      .finally(() => setLoading(false));
    // toast는 zustand 안정 참조
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  const selectedEntries = useMemo(
    () => Object.entries(rows).filter(([, s]) => s.checked),
    [rows],
  );

  const selectedCount = selectedEntries.length;

  // 선택된 항목 중 유효하지 않은(예: OTHER customLabel 미입력) 행이 있는지
  const hasInvalid = useMemo(() => {
    return selectedEntries.some(([code, s]) => {
      if (code === 'OTHER' && s.customLabel.trim().length === 0) return true;
      return false;
    });
  }, [selectedEntries]);

  const updateRow = useCallback((code: string, patch: Partial<RowState>) => {
    setRows((prev) => {
      const current: RowState = prev[code] ?? {
        checked: false,
        customLabel: '',
        lewNote: '',
      };
      return {
        ...prev,
        [code]: { ...current, ...patch },
      };
    });
  }, []);

  const toggleChecked = (code: string) => {
    updateRow(code, { checked: !(rows[code]?.checked ?? false), error: undefined });
  };

  const handleSubmit = async () => {
    if (selectedCount === 0 || hasInvalid || submitting) return;

    const items: CreateDocumentRequestItem[] = selectedEntries.map(([code, s]) => ({
      documentTypeCode: code,
      customLabel: code === 'OTHER' ? s.customLabel.trim() : undefined,
      lewNote: s.lewNote.trim().length > 0 ? s.lewNote.trim() : undefined,
    }));

    setSubmitting(true);
    setLimitBannerMessage(null);
    try {
      const res = await documentApi.createDocumentRequests(applicationSeq, items);
      toast.success(
        `Requested ${res.created.length} document(s)`,
      );
      onSuccess();
      onClose();
    } catch (err) {
      const e = err as { code?: string; message?: string; response?: { data?: { details?: Record<string, string> } } };
      switch (e.code) {
        case 'TOO_MANY_ACTIVE_REQUESTS':
          setLimitBannerMessage(
            'Maximum 10 active requests reached. Approve or reject existing ones before trying again.',
          );
          break;
        case 'DUPLICATE_ACTIVE_REQUEST': {
          // 서버가 details에 documentTypeCode를 담아주는 컨벤션 (PR#1)
          const duplicatedCode =
            e.response?.data?.details?.documentTypeCode ?? '';
          if (duplicatedCode && rows[duplicatedCode]) {
            updateRow(duplicatedCode, {
              error: 'Already pending',
            });
          } else {
            toast.error(
              e.message ?? 'A selected document is already pending.',
            );
          }
          break;
        }
        case 'CUSTOM_LABEL_REQUIRED':
          if (rows['OTHER']) {
            updateRow('OTHER', { error: 'Label required' });
          } else {
            toast.error(e.message ?? 'Custom label required.');
          }
          break;
        case 'UNKNOWN_DOCUMENT_TYPE':
          toast.error(
            e.message ?? 'Unknown document type.',
          );
          break;
        default:
          toast.error(e.message ?? 'Failed to create requests.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const close = () => {
    if (submitting) return;
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={close}
      size="md"
      closeOnEscape={!submitting}
      closeOnOverlay={!submitting}
      ariaLabelledBy="dr-modal-title"
    >
      <ModalHeader onClose={close}>
        <div className="flex items-center gap-2">
          <span className="text-xl" aria-hidden>
            📋
          </span>
          <div>
            <h3 id="dr-modal-title" className="text-lg font-semibold text-gray-800">
              Request Documents
            </h3>
            {(applicantDisplayName || applicationCode) && (
              <p className="text-xs text-gray-500 mt-0.5">
                {applicantDisplayName && <>Applicant: {applicantDisplayName}</>}
                {applicantDisplayName && applicationCode && ' · '}
                {applicationCode}
              </p>
            )}
          </div>
        </div>
      </ModalHeader>

      <ModalBody className="!py-0">
        {(softLimitReached || limitBannerMessage) && (
          <div
            role="alert"
            className="mt-4 text-sm text-warning-700 bg-warning-50 border border-warning-500/40 rounded-md p-3"
          >
            {limitBannerMessage ??
              'Maximum 10 active requests reached. Approve or reject existing ones before trying again.'}
          </div>
        )}

        <p className="text-sm text-gray-600 my-3">
          Select the documents you need. The applicant will be notified immediately.
        </p>

        {loading ? (
          <div className="flex items-center justify-center py-10">
            <LoadingSpinner size="md" label="Loading document types..." />
          </div>
        ) : (
          <ul className="border border-gray-200 rounded-lg divide-y divide-gray-200 max-h-[60vh] overflow-y-auto">
            {catalog.map((dt) => {
              const row = rows[dt.code] ?? {
                checked: false,
                customLabel: '',
                lewNote: '',
              };
              const checked = row.checked;
              const customLabelTrimmed = row.customLabel.trim();

              // 이미 active 상태인지 — OTHER는 customLabel 매칭
              let alreadyPending: DocumentRequest | undefined;
              if (dt.code === 'OTHER') {
                alreadyPending = customLabelTrimmed
                  ? activeByCode.get(`OTHER::${customLabelTrimmed}`)
                  : undefined;
              } else {
                alreadyPending = activeByCode.get(dt.code);
              }

              const disabled =
                submitting ||
                (!!alreadyPending && dt.code !== 'OTHER') ||
                (softLimitReached && !checked);

              return (
                <li
                  key={dt.code}
                  className={`px-4 py-3 transition-colors ${
                    checked ? 'bg-primary-50' : 'bg-surface hover:bg-gray-50'
                  }`}
                >
                  <label
                    className={`flex items-start gap-3 ${
                      disabled ? 'cursor-not-allowed opacity-75' : 'cursor-pointer'
                    }`}
                  >
                    <input
                      type="checkbox"
                      className="mt-0.5 accent-primary h-4 w-4"
                      checked={checked}
                      disabled={disabled}
                      onChange={() => toggleChecked(dt.code)}
                      aria-describedby={`dr-meta-${dt.code}`}
                    />
                    <span className="text-xl flex-shrink-0" aria-hidden>
                      {dt.iconEmoji ?? '📎'}
                    </span>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between gap-2 flex-wrap">
                        <p className="text-sm font-medium text-gray-900">
                          {dt.labelEn}
                        </p>
                        {alreadyPending && dt.code !== 'OTHER' && (
                          <Badge variant="gray" dot>
                            Already pending #{alreadyPending.id}
                          </Badge>
                        )}
                      </div>
                      <p id={`dr-meta-${dt.code}`} className="text-xs text-gray-500 mt-0.5">
                        {prettyMime(dt.acceptedMime)} · max {dt.maxSizeMb}MB
                      </p>
                    </div>
                  </label>

                  {checked && (
                    <div className="ml-8 mt-3 space-y-2 animate-in">
                      {dt.code === 'OTHER' && (
                        <Input
                          label="Label"
                          required
                          placeholder="Describe the document"
                          value={row.customLabel}
                          onChange={(e) =>
                            updateRow(dt.code, {
                              customLabel: e.target.value,
                              error: undefined,
                            })
                          }
                          disabled={submitting}
                          maxLength={CUSTOM_LABEL_MAX}
                          error={
                            row.error ||
                            (dt.code === 'OTHER' &&
                            row.customLabel.length > 0 &&
                            !!alreadyPending
                              ? `Already pending #${alreadyPending.id}`
                              : undefined)
                          }
                        />
                      )}
                      <Textarea
                        label="Note to applicant (optional)"
                        rows={2}
                        placeholder="e.g., Full pages, high-resolution scan"
                        value={row.lewNote}
                        onChange={(e) =>
                          updateRow(dt.code, { lewNote: e.target.value })
                        }
                        disabled={submitting}
                        maxLength={MEMO_MAX}
                        hint={`${row.lewNote.length} / ${MEMO_MAX}`}
                      />
                      {row.error && dt.code !== 'OTHER' && (
                        <p className="text-xs text-error-600">{row.error}</p>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </ModalBody>

      <ModalFooter>
        <span className="mr-auto text-xs text-gray-500">
          {selectedCount} of {SOFT_LIMIT} active requests will be used ({activeCount} currently active)
        </span>
        <Button variant="outline" size="sm" onClick={close} disabled={submitting}>
          Cancel
        </Button>
        <Button
          size="sm"
          onClick={handleSubmit}
          loading={submitting}
          disabled={selectedCount === 0 || hasInvalid || softLimitReached}
        >
          {selectedCount === 0
            ? 'Select at least one'
            : `Send ${selectedCount} Request${selectedCount > 1 ? 's' : ''}`}
        </Button>
      </ModalFooter>
    </Modal>
  );
}

export default DocumentRequestModal;
