import { useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Textarea } from '../../../components/ui/Textarea';
import { FileUpload } from '../../../components/domain/FileUpload';

interface Props {
  onDeliverableUpload: (file: File, managerNote?: string) => Promise<void>;
}

/**
 * LEW Service — Visit Report upload section (manual only).
 * LEW가 현장 방문 후 작성한 보고서(PDF 권장) 또는 첨부(사진·측정 시트 ZIP)를 업로드한다.
 * 업로드가 끝나면 주문은 SLD_UPLOADED 상태로 전이되며 신청자가 검토·확정한다.
 */
export function LewServiceManagerSection({ onDeliverableUpload }: Props) {
  const [managerNote, setManagerNote] = useState('');

  const handleUpload = async (file: File) => {
    await onDeliverableUpload(file, managerNote.trim() || undefined);
    setManagerNote('');
  };

  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">Submit Visit Report</h2>
      <div className="space-y-3">
        <Textarea
          label="LEW Note (Optional)"
          rows={2}
          maxLength={2000}
          value={managerNote}
          onChange={(e) => setManagerNote(e.target.value)}
          placeholder="Optional note on what was done, measurements, or follow-up needed"
          className="resize-none"
        />
        <FileUpload
          onUpload={handleUpload}
          files={[]}
          label="Upload Visit Report"
          hint="PDF (preferred) for the report, or a ZIP bundle of photos/measurement sheets — up to 10MB"
          accept=".pdf,.jpg,.jpeg,.png,.tif,.tiff,.gif,.zip"
          maxSizeMb={10}
        />
      </div>
    </Card>
  );
}
