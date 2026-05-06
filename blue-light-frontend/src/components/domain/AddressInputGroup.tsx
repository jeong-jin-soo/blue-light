import { Input } from '../ui/Input';

/**
 * Singapore EMA ELISE 5-part address values.
 * Follows the ELISE Renewal form field split: Block / Unit / Street / Building / Postal.
 */
export interface AddressInputValues {
  block: string;
  unit: string;
  street: string;
  building: string;
  postalCode: string;
}

export type AddressInputErrors = Partial<Record<keyof AddressInputValues, string>>;

interface AddressInputGroupProps {
  /** Optional section heading rendered above the grid. */
  title?: string;
  /** Optional helper hint rendered under the heading. */
  description?: string;
  /** Current 5-part values. */
  values: AddressInputValues;
  /** Called with a NEW object containing the updated values. */
  onChange: (next: AddressInputValues) => void;
  /** Per-field error messages (optional). */
  errors?: AddressInputErrors;
  /** Disable every field at once (e.g., read-only review view). */
  disabled?: boolean;
  /**
   * Mark block / street / postalCode with a required asterisk when true.
   * NOTE: This only controls the UI affordance — actual validation lives in the caller.
   */
  required?: boolean;
}

/**
 * 싱가포르 EMA ELISE 양식의 5-part 설치/통신 주소 입력 그룹.
 *
 * - grid 2열, Street Name 은 sm:col-span-2로 한 줄 전체 사용
 * - maxLength 는 백엔드 DTO 제약과 일치
 *   (block 20 / unit 20 / street 200 / building 200 / postalCode 10)
 * - Unit placeholder 는 EMA ELISE 예시 표기 ("03-09" / "03-09,10&11") 그대로 노출
 * - `required` 는 block / street / postalCode 에만 UI 표시 (EMA 양식 필수 3필드)
 */
export function AddressInputGroup({
  title,
  description,
  values,
  onChange,
  errors,
  disabled,
  required,
}: AddressInputGroupProps) {
  const update = <K extends keyof AddressInputValues>(key: K, v: string) => {
    onChange({ ...values, [key]: v });
  };

  return (
    <div className="space-y-3">
      {(title || description) && (
        <div>
          {title && (
            <h3 className="text-sm font-semibold text-gray-800">{title}</h3>
          )}
          {description && (
            <p className="text-xs text-gray-500 mt-0.5">{description}</p>
          )}
        </div>
      )}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Input
          label="Block / House No"
          value={values.block}
          onChange={(e) => update('block', e.target.value)}
          error={errors?.block}
          disabled={disabled}
          required={required}
          maxLength={20}
          placeholder="e.g., 133"
        />
        <Input
          label="Unit #"
          value={values.unit}
          onChange={(e) => update('unit', e.target.value)}
          error={errors?.unit}
          disabled={disabled}
          maxLength={20}
          placeholder="e.g., 03-09 or 03-09,10&11"
        />
        <Input
          label="Street Name"
          className="sm:col-span-2"
          value={values.street}
          onChange={(e) => update('street', e.target.value)}
          error={errors?.street}
          disabled={disabled}
          required={required}
          maxLength={200}
          placeholder="e.g., NEW BRIDGE ROAD"
        />
        <Input
          label="Building"
          value={values.building}
          onChange={(e) => update('building', e.target.value)}
          error={errors?.building}
          disabled={disabled}
          maxLength={200}
          placeholder="e.g., CHINATOWN POINT"
        />
        <Input
          label="Postal Code"
          value={values.postalCode}
          onChange={(e) => update('postalCode', e.target.value)}
          error={errors?.postalCode}
          disabled={disabled}
          required={required}
          maxLength={10}
          placeholder="e.g., 059413"
        />
      </div>
    </div>
  );
}

/** Trimmed 5-part → single legacy `address` string (comma-joined, non-empty only). */
export function joinAddressParts(values: AddressInputValues): string {
  return [values.block, values.unit, values.street, values.building]
    .map((v) => v.trim())
    .filter((v) => v.length > 0)
    .join(', ');
}

/** True when at least one of the 5 parts is non-blank. */
export function hasAnyAddressPart(values: AddressInputValues): boolean {
  return Object.values(values).some((v) => v && v.trim().length > 0);
}
