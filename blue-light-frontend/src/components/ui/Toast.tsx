import { useToastStore, type ToastType } from '../../stores/toastStore';

const typeConfig: Record<ToastType, { bg: string; icon: string; border: string }> = {
  success: { bg: 'bg-success-50', icon: '✓', border: 'border-success-200' },
  error:   { bg: 'bg-error-50',   icon: '✕', border: 'border-error-200' },
  warning: { bg: 'bg-warning-50', icon: '⚠', border: 'border-warning-200' },
  info:    { bg: 'bg-info-50',    icon: 'ℹ', border: 'border-info-200' },
};

const textColors: Record<ToastType, string> = {
  success: 'text-success-700',
  error:   'text-error-700',
  warning: 'text-warning-700',
  info:    'text-info-700',
};

const iconBg: Record<ToastType, string> = {
  success: 'bg-success-500',
  error:   'bg-error-500',
  warning: 'bg-warning-500',
  info:    'bg-info-500',
};

/**
 * Toast container. Render once in App.tsx.
 * Usage: useToastStore.getState().success('Done!')
 */
export function ToastProvider() {
  const { toasts, remove } = useToastStore();

  return (
    <div
      className="fixed top-4 right-4 z-[100] flex flex-col gap-2 max-w-sm"
      aria-live="assertive"
      aria-atomic="false"
      role="status"
    >
      {toasts.map((toast) => {
        const config = typeConfig[toast.type];
        return (
          <div
            key={toast.id}
            className={`flex items-start gap-3 px-4 py-3 rounded-lg border shadow-dropdown ${config.bg} ${config.border} animate-in`}
            role="alert"
          >
            <span
              className={`flex-shrink-0 w-5 h-5 rounded-full flex items-center justify-center text-[10px] text-white font-bold ${iconBg[toast.type]}`}
            >
              {config.icon}
            </span>
            <p className={`text-sm font-medium flex-1 ${textColors[toast.type]}`}>
              {toast.message}
            </p>
            <button
              onClick={() => remove(toast.id)}
              className="flex-shrink-0 text-gray-400 hover:text-gray-600 transition-colors"
              aria-label="Close notification"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        );
      })}
    </div>
  );
}
