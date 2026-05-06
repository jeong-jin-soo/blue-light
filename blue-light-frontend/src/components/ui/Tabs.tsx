import type { ReactNode } from 'react';
import { Badge } from './Badge';
import type { BadgeVariant } from './Badge';

export interface TabDefinition<T extends string> {
  key: T;
  label: string;
  badge?: { text: string; variant?: BadgeVariant };
  disabled?: boolean;
}

interface TabsProps<T extends string> {
  tabs: TabDefinition<T>[];
  activeKey: T;
  onChange: (key: T) => void;
  className?: string;
}

/**
 * 수평 탭 네비게이션 — 데스크탑/모바일 동일 UI.
 *
 * <p>탭 컨텐츠는 외부에서 {@code activeKey} 로 분기해 렌더링한다 (비통제 컴포넌트).</p>
 */
export function Tabs<T extends string>({ tabs, activeKey, onChange, className = '' }: TabsProps<T>) {
  return (
    <div className={`border-b border-gray-200 ${className}`} role="tablist">
      <nav className="-mb-px flex gap-1 overflow-x-auto">
        {tabs.map((tab) => {
          const active = tab.key === activeKey;
          const base =
            'inline-flex items-center gap-2 whitespace-nowrap px-4 py-2.5 text-sm font-medium border-b-2 transition-colors';
          const state = active
            ? 'border-primary-600 text-primary-700'
            : tab.disabled
              ? 'border-transparent text-gray-300 cursor-not-allowed'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300';
          return (
            <button
              key={tab.key}
              type="button"
              role="tab"
              aria-selected={active}
              aria-disabled={tab.disabled || undefined}
              disabled={tab.disabled}
              onClick={() => !tab.disabled && onChange(tab.key)}
              className={`${base} ${state}`}
            >
              <span>{tab.label}</span>
              {tab.badge && (
                <Badge variant={tab.badge.variant ?? 'gray'}>{tab.badge.text}</Badge>
              )}
            </button>
          );
        })}
      </nav>
    </div>
  );
}

interface TabPanelProps {
  active: boolean;
  children: ReactNode;
}

/** 탭 패널 래퍼 — 비활성 탭은 DOM에서 제거한다. */
export function TabPanel({ active, children }: TabPanelProps) {
  if (!active) return null;
  return <div role="tabpanel">{children}</div>;
}
