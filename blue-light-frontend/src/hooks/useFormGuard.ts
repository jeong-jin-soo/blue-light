import { useEffect } from 'react';
import { useBlocker } from 'react-router';

/**
 * Warns the user before navigating away from a page with unsaved changes.
 * - Handles browser-level navigation (tab close, refresh) via `beforeunload`
 * - Handles SPA navigation (Link, navigate) via React Router's `useBlocker`
 *
 * @param isDirty - whether the form has unsaved changes
 * @param message - optional custom warning message (only used for beforeunload)
 */
export function useFormGuard(isDirty: boolean, message?: string) {
  // Browser-level navigation guard (refresh, close tab)
  useEffect(() => {
    if (!isDirty) return;

    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      // Modern browsers ignore custom messages but still show a generic dialog
      return message || 'You have unsaved changes. Are you sure you want to leave?';
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [isDirty, message]);

  // SPA navigation guard (react-router)
  const blocker = useBlocker(isDirty);

  useEffect(() => {
    if (blocker.state === 'blocked') {
      const leave = window.confirm(
        message || 'You have unsaved changes. Are you sure you want to leave?'
      );
      if (leave) {
        blocker.proceed();
      } else {
        blocker.reset();
      }
    }
  }, [blocker, message]);
}
