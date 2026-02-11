import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Badge } from '../../../components/ui/Badge';
import { Select } from '../../../components/ui/Select';
import { FileUpload } from '../../../components/domain/FileUpload';
import { formatFileSize, formatFileType, getFileTypeBadge } from '../../../utils/applicationUtils';
import type { FileInfo, FileType } from '../../../types';

const FILE_TYPE_OPTIONS = [
  { value: 'LICENSE_PDF', label: 'Licence PDF' },
  { value: 'REPORT_PDF', label: 'Report PDF' },
  { value: 'OWNER_AUTH_LETTER', label: "Owner's Auth Letter" },
];

interface Props {
  files: FileInfo[];
  status: string;
  uploadFileType: FileType;
  onUploadFileTypeChange: (type: FileType) => void;
  onFileUpload: (file: File) => Promise<void>;
  onFileDownload: (file: FileInfo) => void;
}

/**
 * 문서 관리 섹션
 */
export function AdminDocumentsSection({
  files,
  status,
  uploadFileType,
  onUploadFileTypeChange,
  onFileUpload,
  onFileDownload,
}: Props) {
  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">Documents</h2>

      {/* Admin file upload */}
      {(status === 'IN_PROGRESS' || status === 'COMPLETED') && (
        <div className="mb-4 space-y-3">
          <div className="w-48">
            <Select
              label="File Type"
              value={uploadFileType}
              onChange={(e) => onUploadFileTypeChange(e.target.value as FileType)}
              options={FILE_TYPE_OPTIONS}
            />
          </div>
          <FileUpload
            onUpload={onFileUpload}
            files={[]}
            label={uploadFileType === 'LICENSE_PDF' ? 'Upload Licence Document' : 'Upload Report Document'}
            hint="PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP up to 10MB"
          />
        </div>
      )}

      {files.length === 0 ? (
        <p className="text-sm text-gray-500">No documents uploaded.</p>
      ) : (
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
                    <Badge
                      variant={getFileTypeBadge(f.fileType)}
                      className="text-[10px]"
                    >
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
    </Card>
  );
}
