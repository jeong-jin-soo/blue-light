import { useState, useRef, useCallback, type ChangeEvent, type DragEvent } from 'react';
import { Button } from '../ui/Button';

interface UploadedFile {
  id?: string | number;
  name: string;
  size: number;
  url?: string;
}

interface FileUploadProps {
  onUpload: (file: File) => Promise<void>;
  onRemove?: (fileId: string | number) => Promise<void>;
  files?: UploadedFile[];
  accept?: string;           // e.g., ".pdf,.jpg,.png"
  maxSizeMb?: number;
  warnSizeMb?: number;       // soft warning threshold (e.g., 2MB for ELISE)
  warnSizeMessage?: string;  // custom warning message
  label?: string;
  hint?: string;
  className?: string;
}

export function FileUpload({
  onUpload,
  onRemove,
  files = [],
  accept = '.pdf,.jpg,.jpeg,.png,.dwg,.dxf,.dgn,.tif,.tiff,.gif,.zip',
  maxSizeMb = 10,
  warnSizeMb,
  warnSizeMessage,
  label = 'Upload File',
  hint,
  className = '',
}: FileUploadProps) {
  const [isDragging, setIsDragging] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [error, setError] = useState('');
  const [warning, setWarning] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const validateAndUpload = useCallback(
    async (file: File) => {
      setError('');
      setWarning('');

      // Size check (hard limit)
      if (file.size > maxSizeMb * 1024 * 1024) {
        setError(`File size must be less than ${maxSizeMb}MB`);
        return;
      }

      // Soft warning (e.g., ELISE 2MB limit)
      if (warnSizeMb && file.size > warnSizeMb * 1024 * 1024) {
        setWarning(warnSizeMessage || `File exceeds ${warnSizeMb}MB. It may need to be resized for ELISE submission.`);
      }

      // Extension check
      if (accept) {
        const allowed = accept.split(',').map((s) => s.trim().toLowerCase());
        const ext = '.' + file.name.split('.').pop()?.toLowerCase();
        if (!allowed.includes(ext)) {
          setError(`Allowed file types: ${accept}`);
          return;
        }
      }

      setIsUploading(true);
      try {
        await onUpload(file);
      } catch {
        setError('Upload failed. Please try again.');
      } finally {
        setIsUploading(false);
      }
    },
    [onUpload, maxSizeMb, accept, warnSizeMb, warnSizeMessage]
  );

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) validateAndUpload(file);
    // Reset input so the same file can be re-selected
    if (inputRef.current) inputRef.current.value = '';
  };

  const handleDrop = (e: DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    const file = e.dataTransfer.files?.[0];
    if (file) validateAndUpload(file);
  };

  const handleDragOver = (e: DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  return (
    <div className={className}>
      {label && (
        <label className="block text-sm font-medium text-gray-700 mb-1.5">
          {label}
        </label>
      )}

      {/* Drop zone */}
      <div
        className={`relative border-2 border-dashed rounded-lg p-6 text-center transition-colors ${
          isDragging
            ? 'border-primary bg-primary-50'
            : 'border-gray-300 hover:border-gray-400'
        } ${isUploading ? 'opacity-60 pointer-events-none' : ''}`}
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragLeave={() => setIsDragging(false)}
      >
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          onChange={handleFileChange}
          className="hidden"
        />

        <div className="text-3xl text-gray-300 mb-2">
          {isUploading ? '⏳' : '📎'}
        </div>
        <p className="text-sm text-gray-600 mb-2">
          {isUploading ? 'Uploading...' : 'Drag & drop your file here, or'}
        </p>
        {!isUploading && (
          <Button
            variant="outline"
            size="sm"
            type="button"
            onClick={() => inputRef.current?.click()}
          >
            Browse Files
          </Button>
        )}
        {hint && <p className="text-xs text-gray-400 mt-2">{hint}</p>}
      </div>

      {/* Error */}
      {error && (
        <p className="mt-1.5 text-xs text-error-600">{error}</p>
      )}

      {/* Warning (soft limit) */}
      {warning && !error && (
        <p className="mt-1.5 text-xs text-amber-600">⚠️ {warning}</p>
      )}

      {/* File list */}
      {files.length > 0 && (
        <ul className="mt-3 space-y-2">
          {files.map((f, idx) => (
            <li
              key={f.id ?? idx}
              className="flex items-center justify-between px-3 py-2 bg-surface-secondary rounded-lg text-sm"
            >
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-lg">📄</span>
                <div className="min-w-0">
                  <p className="font-medium text-gray-700 truncate">{f.name}</p>
                  <p className="text-xs text-gray-400">{formatSize(f.size)}</p>
                </div>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                {f.url && (
                  <a
                    href={f.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-primary text-xs hover:underline"
                  >
                    Download
                  </a>
                )}
                {onRemove && f.id != null && (
                  <button
                    onClick={() => onRemove(f.id!)}
                    className="text-gray-400 hover:text-error-600 transition-colors"
                    type="button"
                  >
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
