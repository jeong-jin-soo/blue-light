import type { ReactElement } from 'react';
import type { ApplicantType } from '../../types';

interface ApplicantTypeCardProps {
  value: ApplicantType;
  checked: boolean;
  onChange: (value: ApplicantType) => void;
  name?: string;
}

/**
 * ApplicantTypeCard — 개인/법인 카드형 라디오 (Phase 1 §3B, 04-design-spec.md)
 *
 * - <input type="radio">를 sr-only로 숨기고 <label> 전체를 클릭 타겟으로 사용
 * - 상위에서 <fieldset>+<legend>로 감싸 스크린 리더 라디오 그룹 발화 보장
 * - 선택 시: border-2 border-primary-800 + bg-primary-50
 * - 레이아웃 시프트 방지 위해 미선택에도 border-2 transparent 유지
 */
const OPTIONS: Record<
  ApplicantType,
  {
    titleEn: string;
    titleKo: string;
    descEn: string;
    descKo: string;
    Icon: () => ReactElement;
  }
> = {
  INDIVIDUAL: {
    titleEn: 'Individual',
    titleKo: '개인',
    descEn: 'I am applying as an individual.',
    descKo: '개인 자격으로 신청합니다.',
    // Heroicons outline: user
    Icon: () => (
      <svg
        className="w-6 h-6"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        viewBox="0 0 24 24"
        aria-hidden="true"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M15.75 7.5a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.5 20.25a7.5 7.5 0 1115 0v.75h-15v-.75z"
        />
      </svg>
    ),
  },
  CORPORATE: {
    titleEn: 'Corporate',
    titleKo: '법인',
    descEn:
      'I am applying on behalf of a registered company (UEN).',
    descKo: '회사(UEN 보유 법인) 명의로 신청합니다.',
    // Heroicons outline: building-office-2
    Icon: () => (
      <svg
        className="w-6 h-6"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        viewBox="0 0 24 24"
        aria-hidden="true"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M2.25 21h19.5M3.75 3v18m16.5-18v18M9 6.75h1.5M9 10.5h1.5M9 14.25h1.5m3-7.5H15m-1.5 3.75H15m-1.5 3.75H15M9 21v-3.375c0-.621.504-1.125 1.125-1.125h3.75c.621 0 1.125.504 1.125 1.125V21"
        />
      </svg>
    ),
  },
};

export function ApplicantTypeCard({
  value,
  checked,
  onChange,
  name = 'applicantType',
}: ApplicantTypeCardProps) {
  const { titleEn, descEn, Icon } = OPTIONS[value];

  return (
    <label
      className={[
        'relative flex flex-col gap-2 p-4 rounded-lg cursor-pointer transition-colors duration-150',
        'border-2',
        'focus-within:ring-2 focus-within:ring-primary-500/20',
        checked
          ? 'border-primary-800 bg-primary-50'
          : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50',
      ].join(' ')}
    >
      <input
        type="radio"
        name={name}
        value={value}
        checked={checked}
        onChange={() => onChange(value)}
        className="sr-only peer"
        aria-required="true"
      />

      {/* Selected indicator */}
      {checked && (
        <span
          className="absolute top-2 right-2 w-5 h-5 rounded-full bg-primary-800 text-white flex items-center justify-center"
          aria-hidden="true"
        >
          <svg
            className="w-3 h-3"
            fill="none"
            stroke="currentColor"
            strokeWidth={3}
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M5 13l4 4L19 7"
            />
          </svg>
        </span>
      )}

      <span className={checked ? 'text-primary-800' : 'text-gray-500'}>
        <Icon />
      </span>

      <div>
        <p className="text-sm font-semibold text-gray-900">{titleEn}</p>
        <p className="text-xs text-gray-500 mt-0.5 leading-relaxed">
          {descEn}
        </p>
      </div>
    </label>
  );
}
