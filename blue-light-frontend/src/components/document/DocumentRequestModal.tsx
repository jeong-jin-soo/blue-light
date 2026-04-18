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
        toast.error('서류 카탈로그를 불러오지 못했습니다. · Failed to load document types.');
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
        `서류 ${res.created.length}건 요청 완료 · Requested ${res.created.length} document(s)`,
      );
      onSuccess();
      onClose();
    } catch (err) {
      const e = err as { code?: string; message?: string; response?: { data?: { details?: Record<string, string> } } };
      switch (e.code) {
        case 'TOO_MANY_ACTIVE_REQUESTS':
          setLimitBannerMessage(
            '활성 요청 한도(10건)에 도달했습니다. 기존 요청을 승인/반려 후 다시 시도하세요. · Maximum 10 active requests reached.',
          );
          break;
        case 'DUPLICATE_ACTIVE_REQUEST': {
          // 서버가 details에 documentTypeCode를 담아주는 컨벤션 (PR#1)
          const duplicatedCode =
            e.response?.data?.details?.documentTypeCode ?? '';
          if (duplicatedCode && rows[duplicatedCode]) {
            updateRow(duplicatedCode, {
              error: '이미 요청 중입니다 · Already pending',
            });
          } else {
            toast.error(
              e.message ?? '이미 요청 중인 서류가 포함되어 있습니다. · A selected document is already pending.',
            );
          }
          break;
        }
        case 'CUSTOM_LABEL_REQUIRED':
          if (rows['OTHER']) {
            updateRow('OTHER', { error: '라벨이 필요합니다 · Label required' });
          } else {
            toast.error(e.message ?? 'Custom label required.');
          }
          break;
        case 'UNKNOWN_DOCUMENT_TYPE':
          toast.error(
            e.message ?? '알 수 없는 서류 타입입니다. · Unknown document type.',
          );
          break;
        default:
          toast.error(e.message ?? '요청 생성에 실패했습니다. · Failed to create requests.');
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
              신청자에게 서류 요청 · Request Documents
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
              '활성 요청 한도(10건)에 도달했습니다. 기존 요청을 승인/반려 후 다시 시도하세요. · Maximum 10 active requests reached.'}
          </div>
        )}

        <p className="text-sm text-gray-600 my-3">
          필요한 서류를 선택하세요. 신청자에게 즉시 알림이 전송됩니다.
          <br />
          <span className="text-gray-500">
            Select the documents you need. The applicant will be notified immediately.
          </span>
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
                          {dt.labelKo}{' '}
                          <span className="text-gray-500 font-normal">· {dt.labelEn}</span>
                        </p>
                        {alreadyPending && dt.code !== 'OTHER' && (
                          <Badge variant="gray" dot>
                            이미 요청 중 #{alreadyPending.id} · Already pending
                          </Badge>
                        )}
                      </div>
                      <p id={`dr-meta-${dt.code}`} className="text-xs text-gray-500 mt-0.5">
                        {prettyMime(dt.acceptedMime)} · 최대 {dt.maxSizeMb}MB
                      </p>
                    </div>
                  </label>

                  {checked && (
                    <div className="ml-8 mt-3 space-y-2 animate-in">
                      {dt.code === 'OTHER' && (
                        <Input
                          label="라벨 · Label"
                          required
                          placeholder="서류 설명 · Describe the document"
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
                              ? `이미 요청 중입니다 #${alreadyPending.id} · Already pending`
                              : undefined)
                          }
                        />
                      )}
                      <Textarea
                        label="신청자에게 전할 메모 (선택) · Note to applicant (optional)"
                        rows={2}
                        placeholder="예: 전체 페이지 고해상도 스캔"
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
          {selectedCount} of {SOFT_LIMIT} active requests will be used (현재 활성 {activeCount}건)
        </span>
        <Button variant="outline" size="sm" onClick={close} disabled={submitting}>
          취소 · Cancel
        </Button>
        <Button
          size="sm"
          onClick={handleSubmit}
          loading={submitting}
          disabled={selectedCount === 0 || hasInvalid || softLimitReached}
        >
          {selectedCount === 0
            ? '최소 1건 선택 · Select at least one'
            : `${selectedCount}건 요청 보내기 · Send ${selectedCount} Request${selectedCount > 1 ? 's' : ''}`}
        </Button>
      </ModalFooter>
    </Modal>
  );
}

export default DocumentRequestModal;
