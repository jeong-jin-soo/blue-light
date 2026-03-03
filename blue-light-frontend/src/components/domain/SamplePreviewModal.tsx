import { useState, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { LoadingSpinner } from '../ui/LoadingSpinner';
import sampleFileApi from '../../api/sampleFileApi';
import { useToastStore } from '../../stores/toastStore';
import type { SampleFileInfo } from '../../types';

interface SamplePreviewModalProps {
  isOpen: boolean;
  onClose: () => void;
  categoryKey: string | null;
  sampleFiles: SampleFileInfo[];
}

export function SamplePreviewModal({
  isOpen,
  onClose,
  categoryKey,
  sampleFiles,
}: SamplePreviewModalProps) {
  const toast = useToastStore();
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [filename, setFilename] = useState('');

  useEffect(() => {
    if (!isOpen || !categoryKey) {
      setBlobUrl(null);
      return;
    }

    const sample = sampleFiles.find((f) => f.categoryKey === categoryKey);
    if (!sample) {
      toast.info('No sample file available for this category yet.');
      onClose();
      return;
    }

    setFilename(sample.originalFilename);
    setLoading(true);

    let revoked = false;
    sampleFileApi
      .getSampleFilePreviewUrl(categoryKey)
      .then((url) => {
        if (!revoked) setBlobUrl(url);
        else URL.revokeObjectURL(url);
      })
      .catch(() => {
        toast.error('Failed to load sample file.');
        onClose();
      })
      .finally(() => setLoading(false));

    return () => {
      revoked = true;
    };
  }, [isOpen, categoryKey]);

  // Revoke blob URL on close
  useEffect(() => {
    if (!isOpen && blobUrl) {
      URL.revokeObjectURL(blobUrl);
      setBlobUrl(null);
    }
  }, [isOpen]);

  // Escape key
  useEffect(() => {
    if (!isOpen) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [isOpen, onClose]);

  // Scroll lock
  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = 'hidden';
      return () => { document.body.style.overflow = ''; };
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const isImage = /\.(png|jpe?g|gif|webp|bmp|heic|heif|tiff?)$/i.test(filename);
  const isPdf = /\.pdf$/i.test(filename);

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/60" />

      {/* Modal */}
      <div className="relative bg-white rounded-xl shadow-xl w-full max-w-4xl max-h-[90vh] flex flex-col animate-in">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200 flex-shrink-0">
          <div className="flex items-center gap-2 min-w-0">
            <svg className="w-5 h-5 text-blue-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
            </svg>
            <h3 className="text-base font-semibold text-gray-800 truncate">
              Sample: {filename || 'Loading...'}
            </h3>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            {blobUrl && (
              <a
                href={blobUrl}
                download={filename}
                className="text-xs text-gray-500 hover:text-gray-700 flex items-center gap-1 px-2 py-1 rounded hover:bg-gray-100 transition-colors"
                title="Download file"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
                Download
              </a>
            )}
            <button
              onClick={onClose}
              className="p-1 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
              aria-label="Close"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-auto p-4 flex items-center justify-center bg-gray-50 min-h-[300px]">
          {loading ? (
            <LoadingSpinner size="lg" label="Loading sample file..." />
          ) : blobUrl ? (
            isImage ? (
              <img
                src={blobUrl}
                alt={filename}
                className="max-w-full max-h-[70vh] object-contain rounded shadow-sm"
              />
            ) : isPdf ? (
              <iframe
                src={blobUrl}
                title={filename}
                className="w-full h-[70vh] rounded border border-gray-200"
              />
            ) : (
              <div className="text-center text-gray-500 space-y-3">
                <svg className="w-16 h-16 mx-auto text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
                </svg>
                <p className="text-sm">This file type cannot be previewed in the browser.</p>
                <a
                  href={blobUrl}
                  download={filename}
                  className="inline-flex items-center gap-1 text-sm text-blue-600 hover:text-blue-800 hover:underline"
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                  </svg>
                  Download to view
                </a>
              </div>
            )
          ) : null}
        </div>
      </div>
    </div>,
    document.body,
  );
}
