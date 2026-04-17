import type { DocumentType } from '../../types/document';
import { Select } from '../ui/Select';
import { formatTypeOptionLabel } from './documentUtils';

interface DocumentTypeSelectorProps {
  catalog: DocumentType[];
  value: string;
  onChange: (code: string) => void;
  disabled?: boolean;
  error?: string;
}

/**
 * Document Type 드롭다운 (shared-Select 래퍼)
 * - 옵션 라벨: "{iconEmoji} {labelKo} · {PDF · PNG · JPG} · 최대 {N}MB"
 * - 선택된 type의 helpText / templateUrl은 드롭다운 아래에 별도 노출
 *
 * 04-design-spec §7 준수 — native `<select>` 재사용, 커스텀 2줄 드롭다운 금지.
 */
export function DocumentTypeSelector({
  catalog,
  value,
  onChange,
  disabled,
  error,
}: DocumentTypeSelectorProps) {
  const options = catalog.map((dt) => ({
    value: dt.code,
    label: formatTypeOptionLabel(dt),
  }));

  const selected = catalog.find((dt) => dt.code === value);

  return (
    <div>
      <Select
        label="서류 종류 / Document Type"
        required
        placeholder="서류 종류 선택 · Select type"
        options={options}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        error={error}
      />
      {selected?.helpText && (
        <p className="text-xs text-gray-500 mt-2 flex items-start gap-1.5">
          <span aria-hidden>💡</span>
          <span>{selected.helpText}</span>
        </p>
      )}
      {selected?.templateUrl && (
        <a
          href={selected.templateUrl}
          target="_blank"
          rel="noreferrer"
          className="text-xs text-primary underline mt-1 inline-block"
        >
          템플릿 다운로드 · Download template
        </a>
      )}
    </div>
  );
}
