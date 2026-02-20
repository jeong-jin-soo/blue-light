import { useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Textarea } from '../../../components/ui/Textarea';
import { FileUpload } from '../../../components/domain/FileUpload';
import { SldManagerChatPanel } from './SldManagerChatPanel';

interface Props {
  sldOrderSeq: number;
  onSldUpload: (file: File, managerNote?: string) => Promise<void>;
  onSldUpdated: () => void;
}

type SldTab = 'manual' | 'ai';

/**
 * SLD Manager SLD Section — 2 tabs: Manual Upload | AI Generate
 */
export function SldManagerSldSection({ sldOrderSeq, onSldUpload, onSldUpdated }: Props) {
  const [activeTab, setActiveTab] = useState<SldTab>('manual');
  const [managerNote, setManagerNote] = useState('');

  const handleUpload = async (file: File) => {
    await onSldUpload(file, managerNote.trim() || undefined);
    setManagerNote('');
  };

  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">SLD Drawing</h2>

      {/* Tabs */}
      <div className="flex border-b border-gray-200 mb-4">
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
        </button>
      </div>

      {/* Tab content */}
      {activeTab === 'manual' && (
        <div className="space-y-3">
          <Textarea
            label="Manager Note (Optional)"
            rows={2}
            maxLength={2000}
            value={managerNote}
            onChange={(e) => setManagerNote(e.target.value)}
            placeholder="Optional note about the SLD drawing"
            className="resize-none"
          />
          <FileUpload
            onUpload={handleUpload}
            files={[]}
            label="Upload SLD Drawing"
            hint="PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP up to 10MB"
            accept=".pdf,.jpg,.jpeg,.png,.dwg,.dxf,.dgn,.tif,.tiff,.gif,.zip"
            maxSizeMb={10}
          />
        </div>
      )}

      {activeTab === 'ai' && (
        <SldManagerChatPanel
          sldOrderSeq={sldOrderSeq}
          onSldUpdated={onSldUpdated}
        />
      )}
    </Card>
  );
}
