import { useCallback, useState } from 'react';
import { useToastStore } from '../stores/toastStore';
import adminApi from '../api/adminApi';
import type { EmaSubmissionResponse, FileType } from '../types';

/**
 * EMA 제출 추적 액션 훅 (LEW EMA 탭 + ADMIN 상세 공용).
 * ema-submission-tracking-spec.md §8 — 전이 7종 + 파일 업로드를 묶고, 백엔드 에러코드를 토스트로 매핑.
 *
 * <p>각 전이는 성공 시 최신 {@link EmaSubmissionResponse} 를 반환받아 호출자에 전달한다
 * (호출자는 setEma 로 갱신). 실패는 토스트 + 예외 재던짐 없이 흡수(컴포넌트가 busy 만 해제).</p>
 */
interface UseEmaActions {
  busy: boolean;
  /** 현재 EMA 응답 (transition 성공 시 갱신). null = 미로딩. */
  ema: EmaSubmissionResponse | null;
  setEma: (ema: EmaSubmissionResponse | null) => void;
  /** 최초/갱신 로드. */
  refresh: () => Promise<void>;
  submit: (referenceNo: string) => Promise<void>;
  query: (note: string) => Promise<void>;
  resubmit: (referenceNo?: string) => Promise<void>;
  approve: () => Promise<void>;
  reject: (reason?: string) => Promise<void>;
  withdraw: () => Promise<void>;
  revert: () => Promise<void>;
  /** EMA_ACK / LICENSE_PDF 업로드 후 EMA 응답 재조회. */
  uploadFile: (file: File, fileType: FileType) => Promise<void>;
}

/** EMA 전이 에러코드 → 사용자 메시지 (토스트). */
const ERROR_MESSAGES: Record<string, string> = {
  EMA_NOT_APPROVED: 'EMA submission must be approved before completion.',
  LICENSE_PDF_MISSING: 'Upload the licence PDF before completing.',
  INVALID_EMA_TRANSITION: 'That action is not allowed from the current EMA state. Refreshing…',
  EMA_ACK_REQUIRED: 'An EMA acknowledgement attachment is required.',
  EMA_REFERENCE_REQUIRED: 'Enter the ELISE reference number.',
  EMA_QUERY_NOTE_REQUIRED: 'Enter the query note.',
  EMA_NOT_IN_PROGRESS: 'EMA can only be updated while the application is in progress.',
};

export function useEmaActions(applicationId: number): UseEmaActions {
  const toast = useToastStore();
  const [busy, setBusy] = useState(false);
  const [ema, setEma] = useState<EmaSubmissionResponse | null>(null);

  const refresh = useCallback(async () => {
    try {
      setEma(await adminApi.getEmaSubmission(applicationId));
    } catch {
      // 조회 실패는 조용히 — 섹션이 로딩 상태 유지
    }
  }, [applicationId]);

  /** 전이 실행 공통 래퍼: busy 토글 + 결과 갱신 + 에러코드 토스트 매핑. */
  const run = useCallback(
    async (fn: () => Promise<EmaSubmissionResponse>) => {
      setBusy(true);
      try {
        const next = await fn();
        setEma(next);
      } catch (err: unknown) {
        const e = err as { response?: { data?: { code?: string; message?: string } }; message?: string };
        const code = e?.response?.data?.code ?? null;
        const message =
          (code && ERROR_MESSAGES[code]) ||
          e?.response?.data?.message ||
          e?.message ||
          'EMA action failed';
        toast.error(message);
        // 상태 불일치(INVALID_EMA_TRANSITION)면 최신 상태로 동기화
        if (code === 'INVALID_EMA_TRANSITION') await refresh();
      } finally {
        setBusy(false);
      }
    },
    [refresh, toast],
  );

  const submit = useCallback((ref: string) => run(() => adminApi.markEmaSubmitted(applicationId, ref)), [applicationId, run]);
  const query = useCallback((note: string) => run(() => adminApi.raiseEmaQuery(applicationId, note)), [applicationId, run]);
  const resubmit = useCallback((ref?: string) => run(() => adminApi.resubmitEma(applicationId, ref)), [applicationId, run]);
  const approve = useCallback(() => run(() => adminApi.approveEma(applicationId)), [applicationId, run]);
  const reject = useCallback((reason?: string) => run(() => adminApi.rejectEma(applicationId, reason)), [applicationId, run]);
  const withdraw = useCallback(() => run(() => adminApi.withdrawEma(applicationId)), [applicationId, run]);
  const revert = useCallback(() => run(() => adminApi.revertEmaDecision(applicationId)), [applicationId, run]);

  const uploadFile = useCallback(
    async (file: File, fileType: FileType) => {
      try {
        await adminApi.uploadFile(applicationId, file, fileType);
        toast.success(fileType === 'LICENSE_PDF' ? 'Licence PDF uploaded' : 'EMA receipt uploaded');
        await refresh(); // licensePdfPresent/emaAckPresent 갱신
      } catch {
        toast.error('File upload failed');
      }
    },
    [applicationId, refresh, toast],
  );

  return { busy, ema, setEma, refresh, submit, query, resubmit, approve, reject, withdraw, revert, uploadFile };
}
