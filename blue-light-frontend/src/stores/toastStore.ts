import { create } from 'zustand';

export type ToastType = 'success' | 'error' | 'warning' | 'info';

export interface Toast {
  id: string;
  type: ToastType;
  message: string;
  duration?: number;
}

interface ToastState {
  toasts: Toast[];
  success: (message: string, duration?: number) => void;
  error: (message: string, duration?: number) => void;
  warning: (message: string, duration?: number) => void;
  info: (message: string, duration?: number) => void;
  remove: (id: string) => void;
}

let toastId = 0;

export const useToastStore = create<ToastState>((set) => ({
  toasts: [],

  success: (message, duration = 5000) => {
    const id = String(++toastId);
    set((s) => ({ toasts: [...s.toasts, { id, type: 'success', message, duration }] }));
    if (duration > 0) setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), duration);
  },

  error: (message, duration = 8000) => {
    const id = String(++toastId);
    set((s) => ({ toasts: [...s.toasts, { id, type: 'error', message, duration }] }));
    if (duration > 0) setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), duration);
  },

  warning: (message, duration = 5000) => {
    const id = String(++toastId);
    set((s) => ({ toasts: [...s.toasts, { id, type: 'warning', message, duration }] }));
    if (duration > 0) setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), duration);
  },

  info: (message, duration = 5000) => {
    const id = String(++toastId);
    set((s) => ({ toasts: [...s.toasts, { id, type: 'info', message, duration }] }));
    if (duration > 0) setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), duration);
  },

  remove: (id) => {
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
  },
}));
