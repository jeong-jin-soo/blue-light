import { useState, useCallback } from 'react';
import { useToastStore } from '../stores/toastStore';

interface UseAsyncOptions {
  /** 성공 시 토스트 메시지 */
  successMessage?: string;
  /** 실패 시 토스트 메시지 */
  errorMessage?: string;
  /** 성공 후 콜백 */
  onSuccess?: () => void;
}

/**
 * 비동기 액션 실행 + loading/error 관리 + toast 피드백 훅
 *
 * @example
 * const { execute, loading } = useAsync();
 * const handleApprove = () => execute(
 *   () => adminApi.approveForPayment(id),
 *   { successMessage: 'Approved!', errorMessage: 'Failed', onSuccess: fetchData }
 * );
 */
export function useAsync() {
  const [loading, setLoading] = useState(false);
  const toast = useToastStore();

  const execute = useCallback(async <T>(
    asyncFn: () => Promise<T>,
    options?: UseAsyncOptions,
  ): Promise<T | undefined> => {
    setLoading(true);
    try {
      const result = await asyncFn();
      if (options?.successMessage) {
        toast.success(options.successMessage);
      }
      options?.onSuccess?.();
      return result;
    } catch {
      if (options?.errorMessage) {
        toast.error(options.errorMessage);
      }
      return undefined;
    } finally {
      setLoading(false);
    }
  }, [toast]);

  return { execute, loading };
}
