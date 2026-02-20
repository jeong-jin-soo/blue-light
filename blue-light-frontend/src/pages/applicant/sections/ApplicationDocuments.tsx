import { useState, useEffect } from 'react';
import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Badge } from '../../../components/ui/Badge';
import { Select } from '../../../components/ui/Select';
import { Textarea } from '../../../components/ui/Textarea';
import { FileUpload } from '../../../components/domain/FileUpload';
import fileApi from '../../../api/fileApi';
import { formatFileSize, formatFileType, getFileTypeBadge } from '../../../utils/applicationUtils';
import type { Application, FileInfo, FileType, SldRequest } from '../../../types';

const APPLICANT_FILE_TYPE_OPTIONS = [
  { value: 'DRAWING_SLD', label: 'Single Line Diagram (SLD)' },
  { value: 'OWNER_AUTH_LETTER', label: 'Letter of Appointment' },
  { value: 'SITE_PHOTO', label: 'Main Breaker Box Photo' },
  { value: 'SP_ACCOUNT_DOC', label: 'SP Account Document' },
];

interface ApplicationDocumentsProps {
  application: Application;
  files: FileInfo[];
  sldRequest: SldRequest | null;
  canUpload: boolean;
  uploadFileType: FileType;
  onUploadFileTypeChange: (type: FileType) => void;
  onFileUpload: (file: File) => Promise<void>;
  onFileDelete: (fileId: string | number) => Promise<void>;
  onFileDownload: (fileInfo: FileInfo) => void;
  // Sketch upload + note handlers
  onSketchUpload?: (file: File) => Promise<void>;
  onSketchDelete?: (fileId: string | number) => Promise<void>;
  onSldRequestUpdate?: (note: string, sketchFileSeq: number | null) => Promise<void>;
  sketchFiles?: FileInfo[];
  savingSldRequest?: boolean;
}

export function ApplicationDocuments({
  application,
  files,
  sldRequest,
  canUpload,
  uploadFileType,
  onUploadFileTypeChange,
  onFileUpload,
  onFileDelete,
  onFileDownload,
  onSketchUpload,
  onSketchDelete,
  onSldRequestUpdate,
  sketchFiles = [],
  savingSldRequest = false,
}: ApplicationDocumentsProps) {
  const [noteValue, setNoteValue] = useState(sldRequest?.applicantNote || '');

  // Sync note value when sldRequest changes (e.g. after save)
  useEffect(() => {
    setNoteValue(sldRequest?.applicantNote || '');
  }, [sldRequest?.applicantNote]);

  const handleSaveDetails = async () => {
    if (!onSldRequestUpdate) return;
    const sketchFileSeq = sketchFiles.length > 0 ? sketchFiles[0].fileSeq : null;
    await onSldRequestUpdate(noteValue, sketchFileSeq);
  };

  return (
    <>
      {/* SLD Request Status */}
      {application.sldOption === 'REQUEST_LEW' && sldRequest && (
        <Card>
          <h2 className="text-lg font-semibold text-gray-800 mb-4">SLD Drawing Request</h2>

          {/* REQUESTED — 편집 가능한 폼 */}
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

              {/* Sketch file upload */}
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

              {/* Applicant note */}
              <Textarea
                label="Notes for LEW (Optional)"
                rows={3}
                maxLength={2000}
                value={noteValue}
                onChange={(e) => setNoteValue(e.target.value)}
                placeholder="e.g. 3 circuits: lighting, power, ACMV. Need separate fire alarm circuit."
                className="resize-none"
              />

              {/* Save button + timestamp */}
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

          {/* AI_GENERATING — 읽기 전용 */}
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

      {/* Documents */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Documents</h2>

        {canUpload && (
          <div className="space-y-3 mb-4">
            <Select
              label="Document Type"
              value={uploadFileType}
              onChange={(e) => onUploadFileTypeChange(e.target.value as FileType)}
              options={APPLICANT_FILE_TYPE_OPTIONS}
            />
            <FileUpload
              onUpload={onFileUpload}
              onRemove={onFileDelete}
              files={files.map((f) => ({
                id: f.fileSeq,
                name: f.originalFilename || `File #${f.fileSeq}`,
                size: f.fileSize || 0,
              }))}
              label={APPLICANT_FILE_TYPE_OPTIONS.find((o) => o.value === uploadFileType)?.label || 'Document'}
              hint="PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP up to 10MB. Files for ELISE submission should be under 2MB."
              warnSizeMb={2}
              warnSizeMessage="This file exceeds 2MB and may need to be resized before ELISE submission to EMA."
            />
          </div>
        )}

        {/* File list (read-only view when upload is disabled) */}
        {!canUpload && files.length === 0 && (
          <p className="text-sm text-gray-500">No documents uploaded.</p>
        )}

        {!canUpload && files.length > 0 && (
          <div className="space-y-2">
            {files.map((f) => (
              <div
                key={f.fileSeq}
                className="flex items-center justify-between px-3 py-2 bg-surface-secondary rounded-lg"
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className="text-lg">📄</span>
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-gray-700 truncate">
                      {f.originalFilename || `File #${f.fileSeq}`}
                    </p>
                    <div className="flex items-center gap-2 text-xs text-gray-400">
                      <Badge variant={getFileTypeBadge(f.fileType)} className="text-[10px]">
                        {formatFileType(f.fileType)}
                      </Badge>
                      {f.fileSize != null && f.fileSize > 0 && (
                        <span>{formatFileSize(f.fileSize)}</span>
                      )}
                      <span>{new Date(f.uploadedAt).toLocaleDateString()}</span>
                    </div>
                  </div>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => onFileDownload(f)}
                >
                  Download
                </Button>
              </div>
            ))}
          </div>
        )}

        {/* Licence Documents (admin-uploaded, read-only) */}
        {(() => {
          const adminFiles = files.filter((f) => f.fileType === 'LICENSE_PDF' || f.fileType === 'REPORT_PDF');
          if (!canUpload || adminFiles.length === 0) return null;
          return (
            <div className="mt-4 pt-4 border-t border-gray-200">
              <h3 className="text-sm font-semibold text-gray-700 mb-2">Licence Documents</h3>
              <div className="space-y-2">
                {adminFiles.map((f) => (
                  <div
                    key={f.fileSeq}
                    className="flex items-center justify-between px-3 py-2 bg-green-50 rounded-lg border border-green-100"
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-lg">📋</span>
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-gray-700 truncate">
                          {f.originalFilename || `File #${f.fileSeq}`}
                        </p>
                        <div className="flex items-center gap-2 text-xs text-gray-400">
                          <Badge variant={getFileTypeBadge(f.fileType)} className="text-[10px]">
                            {formatFileType(f.fileType)}
                          </Badge>
                          {f.fileSize != null && f.fileSize > 0 && (
                            <span>{formatFileSize(f.fileSize)}</span>
                          )}
                          <span>{new Date(f.uploadedAt).toLocaleDateString()}</span>
                        </div>
                      </div>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => onFileDownload(f)}
                    >
                      Download
                    </Button>
                  </div>
                ))}
              </div>
            </div>
          );
        })()}
      </Card>
    </>
  );
}
