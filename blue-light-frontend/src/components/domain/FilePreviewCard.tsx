import { useState, useEffect } from 'react';
import { Button } from '../ui/Button';
import fileApi from '../../api/fileApi';
import { formatFileSize, getFileExtension, isImageFile, isPdfFile } from '../../utils/applicationUtils';
import type { FileInfo } from '../../types';

interface FilePreviewCardProps {
  file: FileInfo;
  onDownload: (file: FileInfo) => void;
  onDelete?: (fileId: string | number) => void;
}

export function FilePreviewCard({ file, onDownload, onDelete }: FilePreviewCardProps) {
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [loadError, setLoadError] = useState(false);

  const filename = file.originalFilename || `File #${file.fileSeq}`;
  const isImage = isImageFile(filename);
  const isPdf = isPdfFile(filename);
  const ext = getFileExtension(filename).toUpperCase();

  useEffect(() => {
    if (!isImage) return;

    let revoked = false;
    let url = '';

    fileApi.getFilePreviewUrl(file.fileSeq)
      .then((blobUrl) => {
        if (revoked) {
          window.URL.revokeObjectURL(blobUrl);
          return;
        }
        url = blobUrl;
        setPreviewUrl(blobUrl);
      })
      .catch(() => setLoadError(true));

    return () => {
      revoked = true;
      if (url) window.URL.revokeObjectURL(url);
    };
  }, [file.fileSeq, isImage]);

  return (
    <div className="flex items-center gap-3 px-3 py-2.5 bg-white rounded-lg border border-gray-100 hover:border-gray-200 hover:shadow-sm transition-all group">
      {/* Thumbnail */}
      <div className="w-14 h-14 flex-shrink-0 rounded-lg overflow-hidden bg-gray-50 border border-gray-100 flex items-center justify-center">
        {isImage && previewUrl && !loadError ? (
          <img
            src={previewUrl}
            alt={filename}
            className="w-full h-full object-cover"
            onError={() => setLoadError(true)}
          />
        ) : isPdf ? (
          <div className="text-center">
            <svg className="w-6 h-6 text-red-400 mx-auto" fill="currentColor" viewBox="0 0 24 24">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm-1 2l5 5h-5V4zM9.5 14c.3 0 .5.2.5.5v2c0 .3-.2.5-.5.5s-.5-.2-.5-.5v-2c0-.3.2-.5.5-.5zm2.5.5c0-.3.2-.5.5-.5h1c.6 0 1 .4 1 1v1c0 .6-.4 1-1 1h-.5v.5c0 .3-.2.5-.5.5s-.5-.2-.5-.5v-3zm1 1.5h.5v-1H13v1zm-5-1c0-.3.2-.5.5-.5H9c.6 0 1 .4 1 1s-.4 1-1 1h-.5v.5c0 .3-.2.5-.5.5s-.5-.2-.5-.5v-2.5zm1 1H9c0 0 0 0 0 0v-1h-.5v1z" />
            </svg>
            <p className="text-[9px] text-red-400 font-medium mt-0.5">PDF</p>
          </div>
        ) : (
          <div className="text-center">
            <svg className="w-6 h-6 text-gray-300 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
            </svg>
            <p className="text-[9px] text-gray-400 font-medium mt-0.5">{ext || 'FILE'}</p>
          </div>
        )}
      </div>

      {/* File info */}
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-gray-700 truncate" title={filename}>
          {filename}
        </p>
        <div className="flex items-center gap-2 text-xs text-gray-400 mt-0.5">
          {file.fileSize != null && file.fileSize > 0 && (
            <span>{formatFileSize(file.fileSize)}</span>
          )}
          <span>{new Date(file.uploadedAt).toLocaleDateString()}</span>
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center gap-1 flex-shrink-0 opacity-70 group-hover:opacity-100 transition-opacity">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => onDownload(file)}
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
          </svg>
        </Button>
        {onDelete && (
          <button
            onClick={() => onDelete(file.fileSeq)}
            className="text-gray-300 hover:text-red-500 transition-colors p-1.5 rounded-lg hover:bg-red-50"
            type="button"
            aria-label={`Remove ${filename}`}
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}
