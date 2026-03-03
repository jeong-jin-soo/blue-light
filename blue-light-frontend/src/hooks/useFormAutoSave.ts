import { useEffect, useRef, useCallback } from 'react';

/**
 * 폼 자동 저장 훅 — sessionStorage 기반
 * - formData가 변경될 때마다 debounce 후 sessionStorage에 저장
 * - 마운트 시 저장된 데이터가 있으면 복원 콜백 호출
 * - clear()로 저장 데이터 삭제 (submit 성공 시)
 *
 * @param key        sessionStorage 키 (예: "new-application-draft")
 * @param formData   현재 폼 데이터
 * @param setFormData 폼 데이터 setter
 * @param options    debounce ms (기본 500), onRestore 콜백
 * @returns { clear, hasSavedDraft }
 */
export function useFormAutoSave<T>(
  key: string,
  formData: T,
  setFormData: (data: T) => void,
  options?: {
    debounceMs?: number;
    /** 저장된 데이터 복원 시 호출 (true 반환 시 복원) */
    onRestore?: (savedData: T) => boolean;
  }
) {
  const debounceMs = options?.debounceMs ?? 500;
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isInitialMount = useRef(true);
  const hasSavedDraftRef = useRef(false);

  // 마운트 시 sessionStorage에서 복원 시도
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem(key);
      if (saved) {
        const parsed = JSON.parse(saved) as T;
        hasSavedDraftRef.current = true;

        if (options?.onRestore) {
          const shouldRestore = options.onRestore(parsed);
          if (shouldRestore) {
            setFormData(parsed);
          } else {
            // 사용자가 복원 거부 → 삭제
            sessionStorage.removeItem(key);
            hasSavedDraftRef.current = false;
          }
        } else {
          // onRestore 미지정 시 자동 복원
          setFormData(parsed);
        }
      }
    } catch {
      sessionStorage.removeItem(key);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  // formData 변경 시 debounce 저장
  useEffect(() => {
    // 초기 마운트 건너뛰기 (복원된 데이터 저장 방지)
    if (isInitialMount.current) {
      isInitialMount.current = false;
      return;
    }

    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }

    timerRef.current = setTimeout(() => {
      try {
        sessionStorage.setItem(key, JSON.stringify(formData));
      } catch {
        // sessionStorage 용량 초과 등 무시
      }
    }, debounceMs);

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
      }
    };
  }, [key, formData, debounceMs]);

  // 명시적 삭제 (submit 성공 시 호출)
  const clear = useCallback(() => {
    sessionStorage.removeItem(key);
    hasSavedDraftRef.current = false;
  }, [key]);

  return { clear, hasSavedDraft: hasSavedDraftRef.current };
}
