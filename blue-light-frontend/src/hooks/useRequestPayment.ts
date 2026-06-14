import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import lewReviewApi from '../api/lewReviewApi';
import { useToastStore } from '../stores/toastStore';

/**
 * LEW 결제 요청(Request Payment) 공통 훅 — PR3 옵션 R.
 *
 * <p>진입 페이지(LewApplicationDetailPage)와 리뷰 폼(LewReviewFormPage)이 동일한
 * 결제 요청 로직(API 호출 + 서버 가드 위반 코드 매핑 + toast + 후처리)을 공유한다.
 * 이전에는 진입 페이지에만 핸들러가 있어 리뷰 폼에서 결제 요청 시 동선이 끊겼다
 * (UX 검토 결론: 결제 요청은 '신청-수준 액션'이라 검토 화면에도 있어야 함).</p>
 *
 * <p>결제 요청은 신청자에게 결제 알림 이메일이 발송되는 비가역 액션이므로, 호출 측이
 * confirm dialog 로 한 번 더 확인한 뒤 {@link RequestPaymentApi.run} 을 호출한다.</p>
 *
 * 서버 가드(LewReviewService.requestPayment): kVA CONFIRMED + 미해결 서류 0건 + LoA 수령(신청자 서명본 업로드 이상).
 * SLD 는 가드에서 제외(결제 후 작업). 위반 시 백엔드가 409 + code 반환.
 */
export interface UseRequestPaymentOptions {
  /** 결제 요청 성공 후 호출 — 보통 데이터 리프레시(상태가 PENDING_PAYMENT로 전이). */
  onSuccess?: () => void | Promise<void>;
  /**
   * 가드 위반(KVA_NOT_CONFIRMED / DOCUMENT_REQUESTS_PENDING / LOA_NOT_RECEIVED)으로 리뷰가 필요할 때 호출.
   * - 진입 페이지: 리뷰 폼으로 navigate.
   * - 리뷰 폼: 이미 그 화면이므로 해당 탭으로 점프(또는 no-op).
   * 미지정 시 기본 동작은 리뷰 URL로 navigate.
   */
  onNeedsReview?: (reason: 'kva' | 'documents' | 'loa') => void;
  /** INVALID_STATUS_TRANSITION(다른 곳에서 이미 전이됨) 시 호출 — 보통 리프레시. */
  onStaleState?: () => void | Promise<void>;
}

export interface RequestPaymentApi {
  /** 결제 요청 실행 (confirm 이후 호출). */
  run: () => Promise<void>;
  /** API 호출 진행 중 여부 — 버튼 로딩/중복 클릭 방지용. */
  requesting: boolean;
}

export function useRequestPayment(
  applicationId: number,
  options: UseRequestPaymentOptions = {},
): RequestPaymentApi {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [requesting, setRequesting] = useState(false);

  const { onSuccess, onNeedsReview, onStaleState } = options;

  const run = useCallback(async () => {
    setRequesting(true);
    try {
      await lewReviewApi.requestPayment(applicationId);
      toast.success('Payment requested. The applicant will be notified to pay the licence fee.');
      await onSuccess?.();
    } catch (err: unknown) {
      const e = err as {
        response?: { data?: { code?: string; message?: string } };
        message?: string;
      };
      const code = e?.response?.data?.code ?? null;
      const message = e?.response?.data?.message ?? e?.message ?? 'Failed to request payment';
      const reviewUrl = `/lew/applications/${applicationId}/review`;
      switch (code) {
        case 'KVA_NOT_CONFIRMED':
          toast.error('kVA must be confirmed before requesting payment.');
          if (onNeedsReview) onNeedsReview('kva');
          else navigate(reviewUrl);
          break;
        case 'DOCUMENT_REQUESTS_PENDING':
          toast.error('Resolve all pending document requests before requesting payment.');
          if (onNeedsReview) onNeedsReview('documents');
          else navigate(reviewUrl);
          break;
        case 'LOA_NOT_RECEIVED':
        case 'LOA_NOT_FINALIZED':
          toast.error('The final LoA must be uploaded before requesting payment.');
          if (onNeedsReview) onNeedsReview('loa');
          else navigate(reviewUrl);
          break;
        case 'INVALID_STATUS_TRANSITION':
          toast.warning('This application is no longer in review — refreshing latest state.');
          await onStaleState?.();
          break;
        case 'APPLICATION_NOT_ASSIGNED':
          toast.error('You are no longer assigned to this application.');
          navigate('/lew/applications');
          break;
        default:
          toast.error(message);
      }
    } finally {
      setRequesting(false);
    }
  }, [applicationId, navigate, toast, onSuccess, onNeedsReview, onStaleState]);

  return { run, requesting };
}
