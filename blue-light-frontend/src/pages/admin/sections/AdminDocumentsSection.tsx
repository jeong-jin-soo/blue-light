import { useState, useRef, useCallback, type ChangeEvent, type DragEvent } from 'react';
import { Card } from '../../../components/ui/Card';
import { FilePreviewCard } from '../../../components/domain/FilePreviewCard';
import {
  DOCUMENT_CATEGORIES,
  groupFilesByCategory,
  MAX_UPLOAD_SIZE_MB,
  ALLOWED_UPLOAD_EXTENSIONS,
  type DocumentCategory,
} from '../../../utils/applicationUtils';
import type { FileInfo, FileType } from '../../../types';

interface Props {
  files: FileInfo[];
  status: string;
  uploadFileType: FileType;
  onUploadFileTypeChange: (type: FileType) => void;
  onFileUpload: (file: File, fileType?: FileType) => Promise<void>;
  onFileDownload: (file: FileInfo) => void;
  onFileDelete?: (fileId: number) => Promise<void>;
}

/** Compact inline upload area for admin categories */
function AdminUploadZone({
  category,
  onUpload,
}: {
  category: DocumentCategory;
  onUpload: (file: File, fileType: FileType) => Promise<void>;
}) {
  const [isDragging, setIsDragging] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [error, setError] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const fileType = category.fileTypes[0];

  const validateAndUpload = useCallback(
    async (file: File) => {
      setError('');
      if (file.size > MAX_UPLOAD_SIZE_MB * 1024 * 1024) {
        setError(`File size must be less than ${MAX_UPLOAD_SIZE_MB}MB`);
        return;
      }
      const allowed = ALLOWED_UPLOAD_EXTENSIONS.split(',').map((s) => s.trim().toLowerCase());
      const ext = '.' + file.name.split('.').pop()?.toLowerCase();
      if (!allowed.includes(ext)) {
        setError(`Allowed file types: ${ALLOWED_UPLOAD_EXTENSIONS}`);
        return;
      }

      setIsUploading(true);
      try {
        await onUpload(file, fileType);
      } catch {
        setError('Upload failed. Please try again.');
      } finally {
        setIsUploading(false);
      }
    },
    [onUpload, fileType],
  );

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) validateAndUpload(file);
    if (inputRef.current) inputRef.current.value = '';
  };

  const handleDrop = (e: DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    const file = e.dataTransfer.files?.[0];
    if (file) validateAndUpload(file);
  };

  return (
    <div>
      <div
        className={`border border-dashed rounded-lg px-4 py-3 text-center transition-colors cursor-pointer ${
          isDragging
            ? 'border-primary bg-primary-50'
            : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'
        } ${isUploading ? 'opacity-60 pointer-events-none' : ''}`}
        onDrop={handleDrop}
        onDragOver={(e) => { e.preventDefault(); setIsDragging(true); }}
        onDragLeave={() => setIsDragging(false)}
        onClick={() => !isUploading && inputRef.current?.click()}
      >
        <input
          ref={inputRef}
          type="file"
          accept={ALLOWED_UPLOAD_EXTENSIONS}
          onChange={handleFileChange}
          className="hidden"
        />
        <p className="text-xs text-gray-400">
          {isUploading ? (
            <span className="text-primary">Uploading...</span>
          ) : (
            <>
              <span className="text-primary font-medium">Click to upload</span>
              {' '}or drag and drop — up to {MAX_UPLOAD_SIZE_MB}MB
            </>
          )}
        </p>
      </div>
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
    </div>
  );
}

/**
 * Admin/LEW Documents section — grouped by category with thumbnails
 */
export function AdminDocumentsSection({
  files,
  status,
  onFileUpload,
  onFileDownload,
  onFileDelete,
}: Props) {
  const grouped = groupFilesByCategory(files);
  const canUpload = status === 'IN_PROGRESS' || status === 'COMPLETED';

  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">Documents</h2>

      {files.length === 0 && !canUpload ? (
        <p className="text-sm text-gray-500">No documents uploaded.</p>
      ) : (
        <div className="space-y-4">
          {DOCUMENT_CATEGORIES.map((category) => {
            const categoryFiles = grouped[category.key] || [];
            const showUpload = canUpload && category.adminUpload;

            // Hide empty categories when upload is not available
            if (!showUpload && categoryFiles.length === 0) return null;

            return (
              <div
                key={category.key}
                className={`rounded-lg border ${category.borderColor} ${category.bgColor} overflow-hidden`}
              >
                {/* Category header */}
                <div className="px-4 py-2.5 flex items-center gap-2">
                  <span className="text-base">{category.icon}</span>
                  <h3 className={`text-sm font-semibold ${category.headerColor}`}>
                    {category.label}
                  </h3>
                  <span className="text-xs text-gray-400 ml-auto">
                    {categoryFiles.length} file{categoryFiles.length !== 1 ? 's' : ''}
                  </span>
                </div>

                {/* File list */}
                <div className="px-3 pb-3 space-y-1.5">
                  {categoryFiles.map((f) => (
                    <FilePreviewCard
                      key={f.fileSeq}
                      file={f}
                      onDownload={onFileDownload}
                      onDelete={onFileDelete ? (id) => onFileDelete(Number(id)) : undefined}
                    />
                  ))}

                  {categoryFiles.length === 0 && (
                    <p className="text-xs text-gray-400 px-1 py-2">No files uploaded.</p>
                  )}

                  {/* Admin upload zone for licence/report categories */}
                  {showUpload && (
                    <AdminUploadZone
                      category={category}
                      onUpload={onFileUpload}
                    />
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}
