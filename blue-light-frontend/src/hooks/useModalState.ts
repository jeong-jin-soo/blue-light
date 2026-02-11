import { useState, useCallback } from 'react';

/**
 * 모달 open/close 상태 관리 훅
 * - 다수의 useState를 하나의 레코드로 통합
 * - 타입 안전한 모달 키 관리
 *
 * @example
 * const modal = useModalState(['payment', 'complete', 'revision'] as const);
 * modal.open('payment');     // 모달 열기
 * modal.close('payment');    // 모달 닫기
 * modal.isOpen('payment');   // 모달 열림 여부
 * modal.closeAll();          // 모든 모달 닫기
 */
export function useModalState<K extends string>(keys: readonly K[]) {
  const [openModals, setOpenModals] = useState<Record<string, boolean>>(() => {
    const initial: Record<string, boolean> = {};
    keys.forEach(k => { initial[k] = false; });
    return initial;
  });

  const open = useCallback((key: K) => {
    setOpenModals(prev => ({ ...prev, [key]: true }));
  }, []);

  const close = useCallback((key: K) => {
    setOpenModals(prev => ({ ...prev, [key]: false }));
  }, []);

  const toggle = useCallback((key: K) => {
    setOpenModals(prev => ({ ...prev, [key]: !prev[key] }));
  }, []);

  const isOpen = useCallback((key: K): boolean => {
    return !!openModals[key];
  }, [openModals]);

  const closeAll = useCallback(() => {
    setOpenModals(prev => {
      const next = { ...prev };
      Object.keys(next).forEach(k => { next[k] = false; });
      return next;
    });
  }, []);

  return { open, close, toggle, isOpen, closeAll };
}
