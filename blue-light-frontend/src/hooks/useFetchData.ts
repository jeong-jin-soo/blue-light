import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useToastStore } from '../stores/toastStore';

interface UseFetchDataOptions {
  /** 에러 시 토스트 메시지 */
  errorMessage?: string;
  /** 에러 시 리다이렉트 경로 */
  errorRedirect?: string;
}

interface UseFetchDataResult<T> {
  /** 로드된 데이터 */
  data: T | null;
  /** 로딩 상태 */
  loading: boolean;
  /** 데이터 다시 불러오기 */
  refetch: () => void;
  /** 데이터 직접 설정 */
  setData: React.Dispatch<React.SetStateAction<T | null>>;
}

/**
 * 초기 데이터 로딩 패턴 추상화 훅
 * - loading 상태 관리
 * - 에러 시 토스트 + 선택적 리다이렉트
 * - refetch 지원
 *
 * @example
 * const { data: app, loading, refetch } = useFetchData(
 *   () => adminApi.getApplication(id),
 *   [id],
 *   { errorMessage: 'Failed to load', errorRedirect: '/admin' }
 * );
 */
export function useFetchData<T>(
  fetchFn: () => Promise<T>,
  deps: React.DependencyList,
  options?: UseFetchDataOptions,
): UseFetchDataResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();
  const toast = useToastStore();

  const refetch = useCallback(async () => {
    try {
      const result = await fetchFn();
      setData(result);
    } catch {
      if (options?.errorMessage) {
        toast.error(options.errorMessage);
      }
      if (options?.errorRedirect) {
        navigate(options.errorRedirect);
      }
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    refetch();
  }, [refetch]);

  return { data, loading, refetch, setData };
}
