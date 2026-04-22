import { useCallback, useEffect, useMemo, useRef } from 'react';

/**
 * MSSL Account No — Singapore SP Group 전기 공급 계정 번호.
 * 포맷: `AAA-BB-CCCC-D` (총 10자리, 3-2-4-1 파트).
 *
 * 사용처
 * - 신청자 New Application (Step 3, optional fast-track section)
 * - LEW Review Form (Step 2, 기입/교정)
 *
 * UX 동작
 * - 4개 input이 대시(`-`)로 구분되어 렌더된다.
 * - 각 파트가 가득 차면 자동으로 다음 input에 포커스.
 * - 빈 상태에서 Backspace를 누르면 이전 input으로 이동.
 * - 붙여넣기 시 전체 10자리를 파싱해 각 파트에 분배.
 * - 빈 값 / 부분 입력 모두 허용 — 검증은 상위 컴포넌트 또는 서버가 처리.
 *
 * Props 설계 원칙
 * - controlled — `value`는 "AAA-BB-CCCC-D" 문자열 (빈 문자열도 허용).
 * - 부분 입력인 경우 누락된 파트는 빈 문자열로 유지된 상태의 하이픈 연결 문자열을 전달.
 *   (예: "123--6789-" → 파트별로 "123" / "" / "6789" / "").
 */
export interface MsslAccountInputProps {
  /** 현재 값. "AAA-BB-CCCC-D" 포맷 문자열. 빈 값은 "" 또는 undefined. */
  value: string;
  /** 값 변경 콜백. 부분 입력도 포함해 항상 4파트 대시 연결 문자열로 호출. */
  onChange: (value: string) => void;
  /** 최상위 래퍼 className. */
  className?: string;
  /** aria-label — 컨테이너 fieldset에 부여. 기본 "MSSL Account Number". */
  ariaLabel?: string;
  /** disabled 상태. */
  disabled?: boolean;
  /** 각 input의 id prefix. 라벨과 연결되는 경우 외부 주입. */
  idPrefix?: string;
}

const PART_LENGTHS = [3, 2, 4, 1] as const;

function parseValueToParts(value: string | undefined): [string, string, string, string] {
  if (!value) return ['', '', '', ''];
  const chunks = value.split('-');
  // 항상 4개 파트로 정규화 (부족분은 빈 문자열)
  const parts: string[] = [];
  for (let i = 0; i < 4; i++) {
    const raw = (chunks[i] ?? '').replace(/\D/g, '');
    parts.push(raw.slice(0, PART_LENGTHS[i]));
  }
  return parts as [string, string, string, string];
}

function partsToValue(parts: [string, string, string, string]): string {
  // 모두 비어있으면 빈 문자열 — 상위가 undefined 판단 편의
  if (parts.every((p) => p === '')) return '';
  return parts.join('-');
}

export function MsslAccountInput({
  value,
  onChange,
  className = '',
  ariaLabel = 'MSSL Account Number',
  disabled = false,
  idPrefix = 'mssl',
}: MsslAccountInputProps) {
  const parts = useMemo(() => parseValueToParts(value), [value]);
  const inputRefs = useRef<Array<HTMLInputElement | null>>([null, null, null, null]);

  const updatePart = useCallback(
    (idx: number, nextRaw: string) => {
      const digitsOnly = nextRaw.replace(/\D/g, '').slice(0, PART_LENGTHS[idx]);
      const nextParts = [...parts] as [string, string, string, string];
      nextParts[idx] = digitsOnly;
      onChange(partsToValue(nextParts));
      // 파트가 가득 찼으면 다음 input으로 자동 이동
      if (digitsOnly.length === PART_LENGTHS[idx] && idx < 3) {
        inputRefs.current[idx + 1]?.focus();
      }
    },
    [parts, onChange],
  );

  const handleKeyDown = useCallback(
    (idx: number, e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Backspace' && parts[idx] === '' && idx > 0) {
        // 빈 파트에서 Backspace → 이전 input으로 이동 (값은 건드리지 않음)
        e.preventDefault();
        const prev = inputRefs.current[idx - 1];
        if (prev) {
          prev.focus();
          // 이전 input 끝에 커서 위치
          const len = prev.value.length;
          prev.setSelectionRange(len, len);
        }
      } else if (e.key === 'ArrowRight' && idx < 3) {
        const el = e.currentTarget;
        if (el.selectionStart === el.value.length) {
          e.preventDefault();
          inputRefs.current[idx + 1]?.focus();
        }
      } else if (e.key === 'ArrowLeft' && idx > 0) {
        const el = e.currentTarget;
        if (el.selectionStart === 0) {
          e.preventDefault();
          inputRefs.current[idx - 1]?.focus();
        }
      }
    },
    [parts],
  );

  const handlePaste = useCallback(
    (e: React.ClipboardEvent<HTMLInputElement>) => {
      const pasted = e.clipboardData.getData('text');
      const digits = pasted.replace(/\D/g, '');
      if (digits.length === 0) return;
      e.preventDefault();
      // 10자리면 전체 분배, 그 외는 현재 input부터 순서대로 채움
      if (digits.length >= 10) {
        const full = digits.slice(0, 10);
        const next: [string, string, string, string] = [
          full.slice(0, 3),
          full.slice(3, 5),
          full.slice(5, 9),
          full.slice(9, 10),
        ];
        onChange(partsToValue(next));
        inputRefs.current[3]?.focus();
      } else {
        // 부분 붙여넣기 — 현재 파트부터 순서대로 분배
        const nextParts = [...parts] as [string, string, string, string];
        let remaining = digits;
        // 현재 포커스된 input 인덱스 찾기
        const activeEl = document.activeElement as HTMLInputElement | null;
        let startIdx = inputRefs.current.findIndex((r) => r === activeEl);
        if (startIdx < 0) startIdx = 0;
        for (let i = startIdx; i < 4 && remaining.length > 0; i++) {
          const take = remaining.slice(0, PART_LENGTHS[i]);
          nextParts[i] = take;
          remaining = remaining.slice(PART_LENGTHS[i]);
        }
        onChange(partsToValue(nextParts));
      }
    },
    [parts, onChange],
  );

  // value prop이 외부에서 공백 전체로 재설정되면 포커스 유지
  useEffect(() => {
    // no-op — controlled component, React가 재조정
  }, [value]);

  const inputClass =
    'w-full px-2 py-2.5 border rounded-lg text-sm text-center tabular-nums placeholder:text-gray-300 ' +
    'border-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary ' +
    'disabled:bg-gray-50 disabled:text-gray-500';

  return (
    <fieldset
      className={`flex items-center gap-1.5 ${className}`}
      aria-label={ariaLabel}
      disabled={disabled}
    >
      {PART_LENGTHS.map((len, idx) => (
        <div key={idx} className="flex items-center gap-1.5">
          <input
            ref={(el) => {
              inputRefs.current[idx] = el;
            }}
            id={`${idPrefix}-part-${idx}`}
            type="text"
            inputMode="numeric"
            autoComplete="off"
            aria-label={`MSSL part ${idx + 1} of 4, ${len} digits`}
            maxLength={len}
            placeholder={'0'.repeat(len)}
            value={parts[idx]}
            onChange={(e) => updatePart(idx, e.target.value)}
            onKeyDown={(e) => handleKeyDown(idx, e)}
            onPaste={handlePaste}
            className={inputClass}
            style={{ width: `${Math.max(len * 14 + 22, 44)}px` }}
          />
          {idx < 3 && <span className="text-gray-400 select-none" aria-hidden="true">-</span>}
        </div>
      ))}
    </fieldset>
  );
}
