import type { ReactNode } from 'react';

interface DashboardCardProps {
  label: string;
  value: number | string;
  icon?: ReactNode;
  trend?: {
    value: string;
    isPositive: boolean;
  };
  className?: string;
  onClick?: () => void;
}

export function DashboardCard({
  label,
  value,
  icon,
  trend,
  className = '',
  onClick,
}: DashboardCardProps) {
  const Wrapper = onClick ? 'button' : 'div';

  return (
    <Wrapper
      className={`bg-surface rounded-xl shadow-card p-5 text-left transition-shadow ${
        onClick ? 'hover:shadow-dropdown cursor-pointer' : ''
      } ${className}`}
      onClick={onClick}
    >
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-medium text-gray-500">{label}</span>
        {icon && <span className="text-2xl">{icon}</span>}
      </div>
      <div className="flex items-end gap-2">
        <span className="text-2xl font-bold text-gray-800">{value}</span>
        {trend && (
          <span
            className={`text-xs font-medium ${
              trend.isPositive ? 'text-success-600' : 'text-error-600'
            }`}
          >
            {trend.value}
          </span>
        )}
      </div>
    </Wrapper>
  );
}
