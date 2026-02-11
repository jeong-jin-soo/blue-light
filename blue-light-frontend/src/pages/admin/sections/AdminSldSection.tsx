import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Textarea } from '../../../components/ui/Textarea';
import { FileUpload } from '../../../components/domain/FileUpload';
import fileApi from '../../../api/fileApi';
import type { SldRequest } from '../../../types';

interface Props {
  sldRequest: SldRequest;
  sldLewNote: string;
  onSldLewNoteChange: (note: string) => void;
  onSldUpload: (file: File) => Promise<void>;
  onSldConfirmClick: () => void;
  actionLoading: boolean;
}

/**
 * SLD 도면 요청 관리 섹션
 */
export function AdminSldSection({
  sldRequest,
  sldLewNote,
  onSldLewNoteChange,
  onSldUpload,
  onSldConfirmClick,
  actionLoading,
}: Props) {
  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">SLD Drawing Request</h2>

      {sldRequest.status === 'REQUESTED' && (
        <div className="space-y-4">
          <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
            <div className="flex items-start gap-3">
              <span className="text-lg">🔧</span>
              <div>
                <p className="text-sm font-medium text-blue-800">SLD Drawing Requested by Applicant</p>
                <p className="text-xs text-blue-700 mt-1">
                  The applicant has requested a LEW to prepare the SLD drawing.
                </p>
                {sldRequest.applicantNote && (
                  <div className="mt-2 bg-white rounded p-2 border border-blue-100">
                    <p className="text-xs text-gray-500">Applicant note:</p>
                    <p className="text-sm text-gray-700">{sldRequest.applicantNote}</p>
                  </div>
                )}
                <p className="text-xs text-blue-500 mt-2">
                  Requested on {new Date(sldRequest.createdAt).toLocaleDateString()}
                </p>
              </div>
            </div>
          </div>

          {/* LEW SLD Upload Area */}
          <div className="border border-gray-200 rounded-lg p-4 space-y-3">
            <p className="text-sm font-medium text-gray-700">Upload SLD Drawing</p>
            <Textarea
              label="LEW Note (Optional)"
              rows={2}
              maxLength={2000}
              value={sldLewNote}
              onChange={(e) => onSldLewNoteChange(e.target.value)}
              placeholder="Optional note about the SLD drawing"
              className="resize-none"
            />
            <FileUpload
              onUpload={onSldUpload}
              files={[]}
              label="Upload SLD Drawing"
              hint="PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP up to 10MB"
            />
          </div>
        </div>
      )}

      {sldRequest.status === 'UPLOADED' && (
        <div className="space-y-4">
          <div className="bg-green-50 border border-green-200 rounded-lg p-4">
            <div className="flex items-start gap-3">
              <span className="text-lg">✅</span>
              <div className="flex-1">
                <p className="text-sm font-medium text-green-800">SLD Uploaded</p>
                <p className="text-xs text-green-700 mt-1">
                  The SLD drawing has been uploaded. Click "Confirm SLD" to finalize.
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
          <Button
            variant="primary"
            size="sm"
            onClick={onSldConfirmClick}
            loading={actionLoading}
          >
            Confirm SLD
          </Button>
        </div>
      )}

      {sldRequest.status === 'CONFIRMED' && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
          <div className="flex items-start gap-3">
            <span className="text-lg">📋</span>
            <div>
              <p className="text-sm font-medium text-gray-700">SLD Confirmed</p>
              <p className="text-xs text-gray-500 mt-1">
                The SLD drawing has been confirmed and is included in this application.
              </p>
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
    </Card>
  );
}
