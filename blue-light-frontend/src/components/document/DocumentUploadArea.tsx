import { useId, useRef, useState } from 'react';
import { formatBytes, prettyMime } from './documentUtils';

interface DocumentUploadAreaProps {
  acceptedMime: string;
  maxSizeMb: number;
  selectedFile: File | null;
  onFileSelect: (file: File | null) => void;
  disabled?: boolean;
  uploading?: boolean;
  error?: string;
}

/**
 * 드래그 앤 드롭 가능한 파일 업로드 영역
 * - 04-design-spec §8 준수 (label 감싸기 패턴, native input, sr-only)
 * - 클릭/드롭/키보드 모두 지원
 */
export function DocumentUploadArea({
  acceptedMime,
  maxSizeMb,
  selectedFile,
  onFileSelect,
  disabled,
  uploading,
  error,
}: DocumentUploadAreaProps) {
  const [isDragOver, setIsDragOver] = useState(false);
  const inputId = useId();
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFiles = (files: FileList | null) => {
    if (!files || files.length === 0) return;
    onFileSelect(files[0]);
  };

  const isBusy = !!uploading;
  const isDisabled = !!disabled;

  return (
    <div>
      <label
        htmlFor={inputId}
        aria-busy={isBusy}
        onDragOver={(e) => {
          if (isDisabled || isBusy) return;
          e.preventDefault();
          setIsDragOver(true);
        }}
        onDragLeave={() => setIsDragOver(false)}
        onDrop={(e) => {
          if (isDisabled || isBusy) return;
          e.preventDefault();
          setIsDragOver(false);
          handleFiles(e.dataTransfer.files);
        }}
        className={[
          'block border-2 border-dashed rounded-lg p-6 text-center transition-colors',
          'min-h-[140px] sm:min-h-[120px] flex flex-col items-center justify-center gap-2',
          isDisabled || isBusy
            ? 'border-gray-200 bg-gray-50 opacity-60 cursor-not-allowed'
            : isDragOver
              ? 'border-primary bg-primary-50 ring-2 ring-primary/20 cursor-pointer'
              : 'border-gray-300 bg-gray-50 hover:border-primary hover:bg-primary-50 cursor-pointer',
        ].join(' ')}
      >
        <input
          ref={inputRef}
          id={inputId}
          type="file"
          className="sr-only"
          accept={acceptedMime}
          disabled={isDisabled || isBusy}
          onChange={(e) => handleFiles(e.target.files)}
        />
        <svg
          className="w-8 h-8 text-gray-400"
          fill="none"
          stroke="currentColor"
          strokeWidth={2}
          viewBox="0 0 24 24"
          aria-hidden
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M12 4v12m0-12l-4 4m4-4l4 4M4 20h16"
          />
        </svg>
        {selectedFile ? (
          <>
            <p className="text-sm font-medium text-gray-900">{selectedFile.name}</p>
            <p className="text-xs text-gray-500">
              {formatBytes(selectedFile.size)} · Click to change
            </p>
          </>
        ) : (
          <>
            <p className="text-sm font-medium text-gray-700">
              Drag &amp; drop or click
            </p>
            <p className="text-xs text-gray-500">
              {prettyMime(acceptedMime)} · max {maxSizeMb}MB
            </p>
          </>
        )}
      </label>

      {error && (
        <p className="text-xs text-error-600 mt-2 flex items-center gap-1" role="alert">
          <span aria-hidden>⚠</span>
          {error}
        </p>
      )}

      {selectedFile && !uploading && !isDisabled && (
        <button
          type="button"
          onClick={() => {
            onFileSelect(null);
            if (inputRef.current) inputRef.current.value = '';
          }}
          className="text-xs text-gray-500 hover:text-gray-700 underline mt-2"
        >
          Clear
        </button>
      )}
    </div>
  );
}
