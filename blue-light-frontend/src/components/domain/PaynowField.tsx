import { Input } from '../ui/Input';
import {
  PAYNOW_TYPES,
  PAYNOW_TYPE_LABELS,
  PAYNOW_PLACEHOLDER,
  isValidPaynow,
  type PaynowType,
} from '../../constants/paynow';

interface PaynowFieldProps {
  type: PaynowType;
  value: string;
  onTypeChange: (t: PaynowType) => void;
  onValueChange: (v: string) => void;
  label?: string;
  /** true면 라벨에 * 표시 */
  required?: boolean;
  disabled?: boolean;
  /** 명시 에러 메시지(우선). 없으면 값이 형식 위반일 때 기본 메시지 표시. */
  error?: string;
  hint?: string;
}

/**
 * LEW 본인 PayNow 수취 계정 입력 — 유형 택1 버튼 + 값 입력 + 형식 검증 메시지.
 * 셋업/자가가입/프로필 화면이 공유한다(검증은 constants/paynow.ts isValidPaynow).
 */
export function PaynowField({
  type,
  value,
  onTypeChange,
  onValueChange,
  label = 'PayNow (for receiving payments)',
  required = false,
  disabled = false,
  error,
  hint,
}: PaynowFieldProps) {
  const formatError =
    type === 'MOBILE'
      ? 'Enter an 8-digit Singapore mobile number (e.g. 97771983).'
      : 'Enter a 10-character Company UEN (e.g. 201837490N).';
  const shownError = error ?? (value && !isValidPaynow(type, value) ? formatError : undefined);

  return (
    <div>
      <label className="block text-sm font-medium text-gray-700 mb-1.5">
        {label}
        {required && <span className="text-error-500 ml-0.5">*</span>}
      </label>
      <div className="grid grid-cols-2 gap-2 mb-2">
        {PAYNOW_TYPES.map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => onTypeChange(t)}
            disabled={disabled}
            className={`p-2.5 border-2 rounded-lg text-center text-sm font-medium transition-all ${
              type === t
                ? 'border-primary bg-primary/5 text-primary'
                : 'border-gray-200 bg-white text-gray-600 hover:border-gray-300'
            }`}
          >
            {PAYNOW_TYPE_LABELS[t]}
          </button>
        ))}
      </div>
      <Input
        label={type === 'MOBILE' ? 'Mobile number' : 'Company UEN'}
        value={value}
        onChange={(e) => onValueChange(e.target.value)}
        placeholder={PAYNOW_PLACEHOLDER[type]}
        error={shownError}
        hint={hint}
        disabled={disabled}
      />
    </div>
  );
}

export default PaynowField;
