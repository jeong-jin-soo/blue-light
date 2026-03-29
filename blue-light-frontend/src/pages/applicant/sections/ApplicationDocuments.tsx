import { useState, useEffect, useRef, useCallback, type ChangeEvent, type DragEvent } from 'react';
import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Textarea } from '../../../components/ui/Textarea';
import { FileUpload } from '../../../components/domain/FileUpload';
import { FilePreviewCard } from '../../../components/domain/FilePreviewCard';
import { SamplePreviewModal } from '../../../components/domain/SamplePreviewModal';
import fileApi from '../../../api/fileApi';
import {
  DOCUMENT_CATEGORIES,
  groupFilesByCategory,
  MAX_UPLOAD_SIZE_MB,
  WARN_UPLOAD_SIZE_MB,
  ALLOWED_UPLOAD_EXTENSIONS,
  type DocumentCategory,
} from '../../../utils/applicationUtils';
import type { Application, FileInfo, FileType, SldRequest, SampleFileInfo } from '../../../types';

interface ApplicationDocumentsProps {
  application: Application;
  files: FileInfo[];
  sldRequest: SldRequest | null;
  canUpload: boolean;
  uploadFileType: FileType;
  onUploadFileTypeChange: (type: FileType) => void;
  onFileUpload: (file: File, fileType?: FileType) => Promise<void>;
  onFileDelete: (fileId: string | number) => Promise<void>;
  onFileDownload: (fileInfo: FileInfo) => void;
  // Sketch upload + note handlers
  onSketchUpload?: (file: File) => Promise<void>;
  onSketchDelete?: (fileId: string | number) => Promise<void>;
  onSldRequestUpdate?: (note: string, sketchFileSeq: number | null) => Promise<void>;
  sketchFiles?: FileInfo[];
  savingSldRequest?: boolean;
  // Sample files for guide
  sampleFiles?: SampleFileInfo[];
}

/** Compact inline upload area per category */
function CategoryUploadZone({
  category,
  onUpload,
}: {
  category: DocumentCategory;
  onUpload: (file: File, fileType: FileType) => Promise<void>;
}) {
  const [isDragging, setIsDragging] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [error, setError] = useState('');
  const [warning, setWarning] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const fileType = category.fileTypes[0]; // primary file type for upload

  const validateAndUpload = useCallback(
    async (file: File) => {
      setError('');
      setWarning('');

      if (file.size > MAX_UPLOAD_SIZE_MB * 1024 * 1024) {
        setError(`File size must be less than ${MAX_UPLOAD_SIZE_MB}MB`);
        return;
      }
      if (file.size > WARN_UPLOAD_SIZE_MB * 1024 * 1024) {
        setWarning('This file exceeds 2MB and may need to be resized before ELISE submission to EMA.');
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
      {warning && !error && <p className="mt-1 text-xs text-amber-600">⚠️ {warning}</p>}
    </div>
  );
}

export function ApplicationDocuments({
  application,
  files,
  sldRequest,
  canUpload,
  onFileUpload,
  onFileDelete,
  onFileDownload,
  onSketchUpload,
  onSketchDelete,
  onSldRequestUpdate,
  sketchFiles = [],
  savingSldRequest = false,
  sampleFiles = [],
}: ApplicationDocumentsProps) {
  const [noteValue, setNoteValue] = useState(sldRequest?.applicantNote || '');
  const [samplePreviewKey, setSamplePreviewKey] = useState<string | null>(null);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setNoteValue(sldRequest?.applicantNote || '');
  }, [sldRequest?.applicantNote]);

  const handleSaveDetails = async () => {
    if (!onSldRequestUpdate) return;
    const sketchFileSeq = sketchFiles.length > 0 ? sketchFiles[0].fileSeq : null;
    await onSldRequestUpdate(noteValue, sketchFileSeq);
  };

  const handleViewSample = (categoryKey: string) => {
    const hasSamples = sampleFiles.some((f) => f.categoryKey === categoryKey);
    if (!hasSamples) {
      return; // no-op, button will be hidden anyway
    }
    setSamplePreviewKey(categoryKey);
  };

  const getSampleCount = (categoryKey: string): number =>
    sampleFiles.filter((f) => f.categoryKey === categoryKey).length;

  const grouped = groupFilesByCategory(files);

  // Determine which categories to show
  const applicantCategories = DOCUMENT_CATEGORIES.filter((c) => {
    if (!c.applicantUpload) return false;
    // NEW applications: LOA is auto-generated by LEW, no upload needed
    if (c.key === 'loa' && application.applicationType === 'NEW') return false;
    return true;
  });
  const licenceCategory = DOCUMENT_CATEGORIES.find((c) => c.key === 'licence');
  const licenceFiles = grouped['licence'] || [];

  return (
    <>
      {/* SLD Request Status */}
      {application.sldOption === 'REQUEST_LEW' && sldRequest && (
        <Card>
          <h2 className="text-lg font-semibold text-gray-800 mb-4">SLD Drawing Request</h2>

          {/* REQUESTED */}
          {sldRequest.status === 'REQUESTED' && (
            <div className="space-y-4">
              <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <span className="text-lg">🔧</span>
                  <div>
                    <p className="text-sm font-medium text-blue-800">SLD Drawing Request Sent</p>
                    <p className="text-xs text-blue-700 mt-1">
                      Upload a sketch and add notes to help the LEW prepare your SLD drawing.
                    </p>
                  </div>
                </div>
              </div>

              {onSketchUpload && (
                <div>
                  <FileUpload
                    onUpload={onSketchUpload}
                    onRemove={onSketchDelete}
                    files={sketchFiles.map((f) => ({
                      id: f.fileSeq,
                      name: f.originalFilename || `Sketch #${f.fileSeq}`,
                      size: f.fileSize || 0,
                    }))}
                    label="Sketch / Reference Drawing"
                    hint="Upload a sketch or reference drawing for the LEW. PDF, JPG, PNG, DWG, DXF up to 10MB."
                  />
                </div>
              )}

              <Textarea
                label="Notes for LEW (Optional)"
                rows={3}
                maxLength={2000}
                value={noteValue}
                onChange={(e) => setNoteValue(e.target.value)}
                placeholder="e.g. 3 circuits: lighting, power, ACMV. Need separate fire alarm circuit."
                className="resize-none"
              />

              <div className="flex items-center justify-between">
                <Button
                  variant="primary"
                  size="sm"
                  onClick={handleSaveDetails}
                  loading={savingSldRequest}
                  disabled={savingSldRequest}
                >
                  Save Details
                </Button>
                <p className="text-xs text-blue-500">
                  Requested on {new Date(sldRequest.createdAt).toLocaleDateString()}
                </p>
              </div>
            </div>
          )}

          {/* AI_GENERATING */}
          {sldRequest.status === 'AI_GENERATING' && (
            <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
              <div className="flex items-start gap-3">
                <span className="text-lg">🤖</span>
                <div>
                  <p className="text-sm font-medium text-blue-800">SLD Being Generated</p>
                  <p className="text-xs text-blue-700 mt-1">
                    The AI is generating your SLD drawing. Please wait.
                  </p>
                  {sldRequest.applicantNote && (
                    <div className="mt-2 bg-white rounded p-2 border border-blue-100">
                      <p className="text-xs text-gray-500">Your note:</p>
                      <p className="text-sm text-gray-700">{sldRequest.applicantNote}</p>
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* UPLOADED */}
          {sldRequest.status === 'UPLOADED' && (
            <div className="bg-green-50 border border-green-200 rounded-lg p-4">
              <div className="flex items-start gap-3">
                <span className="text-lg">✅</span>
                <div>
                  <p className="text-sm font-medium text-green-800">SLD Has Been Uploaded</p>
                  <p className="text-xs text-green-700 mt-1">
                    The LEW has uploaded the SLD drawing. It is pending confirmation.
                  </p>
                  {sldRequest.lewNote && (
                    <div className="mt-2 bg-white rounded p-2 border border-green-100">
                      <p className="text-xs text-gray-500">LEW note:</p>
                      <p className="text-sm text-gray-700">{sldRequest.lewNote}</p>
                    </div>
                  )}
                  {sldRequest.uploadedFileSeq && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="mt-2"
                      onClick={() => {
                        if (sldRequest.uploadedFileSeq) {
                          fileApi.downloadFile(sldRequest.uploadedFileSeq, 'SLD_Drawing');
                        }
                      }}
                    >
                      Download SLD
                    </Button>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* CONFIRMED */}
          {sldRequest.status === 'CONFIRMED' && (
            <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
              <div className="flex items-start gap-3">
                <span className="text-lg">📋</span>
                <div>
                  <p className="text-sm font-medium text-gray-700">SLD Confirmed</p>
                  <p className="text-xs text-gray-500 mt-1">
                    The SLD drawing has been confirmed and is included in your application.
                  </p>
                </div>
              </div>
            </div>
          )}
        </Card>
      )}

      {/* Documents — Grouped by Category */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Documents</h2>

        <div className="space-y-4">
          {/* Applicant upload categories */}
          {applicantCategories.map((category) => {
            const categoryFiles = grouped[category.key] || [];
            // Hide empty categories when upload is not allowed
            if (!canUpload && categoryFiles.length === 0) return null;

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
                  {getSampleCount(category.key) > 0 && (
                    <button
                      type="button"
                      onClick={() => handleViewSample(category.key)}
                      className="text-xs text-blue-600 hover:text-blue-800 flex items-center gap-1 ml-1 hover:underline"
                      title="View sample files uploaded by admin"
                    >
                      <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                      </svg>
                      {getSampleCount(category.key) === 1 ? 'View Sample' : `View Samples (${getSampleCount(category.key)})`}
                    </button>
                  )}
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
                      onDelete={canUpload ? (id) => onFileDelete(id) : undefined}
                    />
                  ))}

                  {categoryFiles.length === 0 && !canUpload && (
                    <p className="text-xs text-gray-400 px-1 py-2">No files uploaded.</p>
                  )}

                  {/* Compact upload zone */}
                  {canUpload && (
                    <CategoryUploadZone
                      category={category}
                      onUpload={onFileUpload}
                    />
                  )}
                </div>
              </div>
            );
          })}

          {/* Licence Documents (admin-uploaded, read-only) */}
          {licenceCategory && licenceFiles.length > 0 && (
            <div className={`rounded-lg border ${licenceCategory.borderColor} ${licenceCategory.bgColor} overflow-hidden`}>
              <div className="px-4 py-2.5 flex items-center gap-2">
                <span className="text-base">{licenceCategory.icon}</span>
                <h3 className={`text-sm font-semibold ${licenceCategory.headerColor}`}>
                  {licenceCategory.label}
                </h3>
                <span className="text-xs text-gray-400 ml-auto">
                  {licenceFiles.length} file{licenceFiles.length !== 1 ? 's' : ''}
                </span>
              </div>
              <div className="px-3 pb-3 space-y-1.5">
                {licenceFiles.map((f) => (
                  <FilePreviewCard
                    key={f.fileSeq}
                    file={f}
                    onDownload={onFileDownload}
                  />
                ))}
              </div>
            </div>
          )}

          {/* Fallback when absolutely no files */}
          {!canUpload && files.length === 0 && (
            <p className="text-sm text-gray-500">No documents uploaded.</p>
          )}
        </div>
      </Card>
      {/* Sample File Preview Modal */}
      <SamplePreviewModal
        isOpen={samplePreviewKey !== null}
        onClose={() => setSamplePreviewKey(null)}
        categoryKey={samplePreviewKey}
        sampleFiles={sampleFiles}
      />
    </>
  );
}
