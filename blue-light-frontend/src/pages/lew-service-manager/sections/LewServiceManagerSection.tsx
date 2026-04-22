import { useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Textarea } from '../../../components/ui/Textarea';
import { FileUpload } from '../../../components/domain/FileUpload';

interface Props {
  onDeliverableUpload: (file: File, managerNote?: string) => Promise<void>;
}

/**
 * LEW Service Manager Deliverable Section — manual upload only.
 */
export function LewServiceManagerSection({ onDeliverableUpload }: Props) {
  const [managerNote, setManagerNote] = useState('');

  const handleUpload = async (file: File) => {
    await onDeliverableUpload(file, managerNote.trim() || undefined);
    setManagerNote('');
  };

  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">LEW Service Deliverable</h2>
      <div className="space-y-3">
        <Textarea
          label="Manager Note (Optional)"
          rows={2}
          maxLength={2000}
          value={managerNote}
          onChange={(e) => setManagerNote(e.target.value)}
          placeholder="Optional note about the LEW Service deliverable"
          className="resize-none"
        />
        <FileUpload
          onUpload={handleUpload}
          files={[]}
          label="Upload LEW Service Deliverable"
          hint="PDF, JPG, PNG, DWG, DXF, DGN, TIF, GIF, ZIP up to 10MB"
          accept=".pdf,.jpg,.jpeg,.png,.dwg,.dxf,.dgn,.tif,.tiff,.gif,.zip"
          maxSizeMb={10}
        />
      </div>
    </Card>
  );
}
