import { useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Textarea } from '../../../components/ui/Textarea';
import { FileUpload } from '../../../components/domain/FileUpload';
import { SldChatPanel } from './SldChatPanel';
import fileApi from '../../../api/fileApi';
import type { SldRequest } from '../../../types';

interface Props {
  applicationSeq: number;
  sldRequest: SldRequest;
  sldLewNote: string;
  onSldLewNoteChange: (note: string) => void;
  onSldUpload: (file: File) => Promise<void>;
  onSldConfirmClick: () => void;
  onSldUpdated: () => void;
  actionLoading: boolean;
}

type SldTab = 'manual' | 'ai';

/**
 * SLD 도면 요청 관리 섹션
 * - REQUESTED/AI_GENERATING: 탭 인터페이스 (Manual Upload / AI Generate)
 * - UPLOADED/CONFIRMED: 기존 UI 유지
 */
export function AdminSldSection({
  applicationSeq,
  sldRequest,
  sldLewNote,
  onSldLewNoteChange,
  onSldUpload,
  onSldConfirmClick,
  onSldUpdated,
  actionLoading,
}: Props) {
  // AI_GENERATING 상태면 AI 탭 자동 선택
  const [activeTab, setActiveTab] = useState<SldTab>(
    sldRequest.status === 'AI_GENERATING' ? 'ai' : 'manual',
  );

  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">SLD Drawing Request</h2>

      {/* REQUESTED or AI_GENERATING — 탭 인터페이스 */}
      {(sldRequest.status === 'REQUESTED' || sldRequest.status === 'AI_GENERATING') && (
        <div className="space-y-4">
          {/* Applicant info banner */}
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
                {sldRequest.sketchFileSeq && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="mt-2"
                    onClick={() => {
                      if (sldRequest.sketchFileSeq) {
                        fileApi.downloadFile(sldRequest.sketchFileSeq, 'Applicant_Sketch');
                      }
                    }}
                  >
                    Download Applicant Sketch
                  </Button>
                )}
                <p className="text-xs text-blue-500 mt-2">
                  Requested on {new Date(sldRequest.createdAt).toLocaleDateString()}
                </p>
              </div>
            </div>
          </div>

          {/* Tabs */}
          <div className="flex border-b border-gray-200">
            <button
              onClick={() => setActiveTab('manual')}
              className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                activeTab === 'manual'
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              Manual Upload
            </button>
            <button
              onClick={() => setActiveTab('ai')}
              className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                activeTab === 'ai'
                  ? 'border-blue-600 text-blue-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              AI Generate
              {sldRequest.status === 'AI_GENERATING' && (
                <span className="ml-1.5 inline-flex h-2 w-2 rounded-full bg-green-400 animate-pulse" />
              )}
            </button>
          </div>

          {/* Tab content */}
          {activeTab === 'manual' && (
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
          )}

          {activeTab === 'ai' && (
            <SldChatPanel
              applicationSeq={applicationSeq}
              sldRequest={sldRequest}
              onSldUpdated={onSldUpdated}
            />
          )}
        </div>
      )}

      {/* UPLOADED */}
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

      {/* CONFIRMED */}
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
