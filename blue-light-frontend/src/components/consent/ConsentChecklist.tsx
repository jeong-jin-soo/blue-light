/**
 * ConsentChecklist
 * - Kaki Concierge v1.5 Phase 1 PR#3 Stage A
 * - 다건 동의 체크박스 공용 컴포넌트 (ConciergeRequestPage + 향후 SignupPage 공유)
 * - "Agree to all" 편의 토글 선택 지원
 */

import { useMemo, type ReactNode } from 'react';

export interface ConsentItem {
  /** 내부 식별자 (payload 키와 일치) — 예: 'pdpa', 'terms', 'signup', 'delegation', 'marketing' */
  key: string;
  /** 체크박스 라벨 (React 노드 허용 — 링크 삽입 등) */
  label: ReactNode;
  /** 필수 여부 — true이면 라벨에 빨간 별표 + aria-required */
  required: boolean;
  /** 약관 전문 링크 (선택) */
  termsUrl?: string;
  /** 보조 설명 (선택) */
  helpText?: ReactNode;
}

interface Props {
  items: ConsentItem[];
  values: Record<string, boolean>;
  onChange: (key: string, checked: boolean) => void;
  /** "Agree to all" 편의 토글. 제공 시 상단에 노출됨. */
  onChangeAll?: (checked: boolean) => void;
  disabled?: boolean;
  error?: string;
  className?: string;
}

export function ConsentChecklist({
  items,
  values,
  onChange,
  onChangeAll,
  disabled,
  error,
  className = '',
}: Props) {
  const allChecked = useMemo(
    () => items.length > 0 && items.every((i) => values[i.key]),
    [items, values]
  );

  return (
    <fieldset
      className={`border border-gray-200 rounded-lg p-4 ${className}`}
      aria-describedby={error ? 'consent-error' : undefined}
    >
      <legend className="text-sm font-medium text-gray-800 px-2">Consents</legend>

      {onChangeAll && (
        <label className="flex items-center gap-2 pb-3 mb-3 border-b border-gray-100 cursor-pointer">
          <input
            type="checkbox"
            checked={allChecked}
            onChange={(e) => onChangeAll(e.target.checked)}
            disabled={disabled}
            className="h-4 w-4 rounded border-gray-300 text-concierge-500 focus:ring-concierge-400"
          />
          <span className="text-sm font-semibold text-gray-900">Agree to all</span>
        </label>
      )}

      <ul className="space-y-3">
        {items.map((item) => (
          <li key={item.key} className="flex items-start gap-2">
            <input
              id={`consent-${item.key}`}
              type="checkbox"
              checked={!!values[item.key]}
              onChange={(e) => onChange(item.key, e.target.checked)}
              disabled={disabled}
              className="mt-0.5 h-4 w-4 rounded border-gray-300 text-concierge-500 focus:ring-concierge-400"
              aria-required={item.required}
            />
            <label
              htmlFor={`consent-${item.key}`}
              className="text-sm text-gray-800 flex-1 cursor-pointer"
            >
              <span>
                {item.label}
                {item.required && (
                  <span className="text-red-600 ml-1" aria-label="required">
                    *
                  </span>
                )}
              </span>
              {item.termsUrl && (
                <a
                  href={item.termsUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="ml-2 text-concierge-600 hover:text-concierge-700 text-xs underline"
                >
                  View
                </a>
              )}
              {item.helpText && (
                <p className="text-xs text-gray-500 mt-1">{item.helpText}</p>
              )}
            </label>
          </li>
        ))}
      </ul>

      {error && (
        <p id="consent-error" className="mt-3 text-sm text-red-600" role="alert">
          {error}
        </p>
      )}
    </fieldset>
  );
}
