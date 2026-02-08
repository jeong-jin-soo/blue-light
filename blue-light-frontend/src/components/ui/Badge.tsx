import type { ReactNode } from 'react';

export type BadgeVariant = 'gray' | 'primary' | 'success' | 'warning' | 'error' | 'info';

interface BadgeProps {
  children: ReactNode;
  variant?: BadgeVariant;
  dot?: boolean;
  className?: string;
}

const variantClasses: Record<BadgeVariant, string> = {
  gray:    'bg-gray-100 text-gray-700',
  primary: 'bg-primary-100 text-primary-800',
  success: 'bg-success-50 text-success-700',
  warning: 'bg-warning-50 text-warning-700',
  error:   'bg-error-50 text-error-700',
  info:    'bg-info-50 text-info-600',
};

const dotColors: Record<BadgeVariant, string> = {
  gray:    'bg-gray-400',
  primary: 'bg-primary-600',
  success: 'bg-success-500',
  warning: 'bg-warning-500',
  error:   'bg-error-500',
  info:    'bg-info-500',
};

export function Badge({ children, variant = 'gray', dot = false, className = '' }: BadgeProps) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium ${variantClasses[variant]} ${className}`}
    >
      {dot && (
        <span className={`w-1.5 h-1.5 rounded-full ${dotColors[variant]}`} />
      )}
      {children}
    </span>
  );
}
