import { useRef, useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Textarea } from '../../../components/ui/Textarea';
import { useToastStore } from '../../../stores/toastStore';
import { lewServiceManagerApi } from '../../../api/lewServiceManagerApi';
import type { LewServiceOrder } from '../../../types';

const MAX_PHOTOS = 10;
const MAX_FILE_SIZE = 10 * 1024 * 1024;

interface Props {
  orderId: number;
  order: LewServiceOrder;
  onCompleted: () => void;
}

/**
 * VisitCompletionForm — PR 3.
 * <p>사진 다건 업로드 + 보고서 단건 업로드 + 메모 + Check-Out 버튼.
 * Manager 가 VISIT_SCHEDULED + checkInAt 있는 상태에서 보여진다.
 */
export function VisitCompletionForm({ orderId, order, onCompleted }: Props) {
  const toast = useToastStore();
  const photoInputRef = useRef<HTMLInputElement | null>(null);
  const reportInputRef = useRef<HTMLInputElement | null>(null);

  const [photos, setPhotos] = useState<File[]>([]);
  const [captions, setCaptions] = useState<string[]>([]);
  const [reportFile, setReportFile] = useState<File | null>(null);
  const [managerNote, setManagerNote] = useState('');
  const [uploadingPhotos, setUploadingPhotos] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const existingPhotoCount = order.visitPhotos?.length ?? 0;

  const handlePhotoSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files ?? []);
    // 기존 + 신규 staged 합산
    const totalAfter = existingPhotoCount + photos.length + selected.length;
    if (totalAfter > MAX_PHOTOS) {
      toast.error(`You can upload up to ${MAX_PHOTOS} photos (currently ${existingPhotoCount + photos.length})`);
      return;
    }
    for (const f of selected) {
      if (f.size > MAX_FILE_SIZE) {
        toast.error(`"${f.name}" exceeds the 10MB limit`);
        return;
      }
    }
    setPhotos([...photos, ...selected]);
    setCaptions([...captions, ...selected.map(() => '')]);
    if (photoInputRef.current) photoInputRef.current.value = '';
  };

  const removePhoto = (idx: number) => {
    setPhotos(photos.filter((_, i) => i !== idx));
    setCaptions(captions.filter((_, i) => i !== idx));
  };

  const updateCaption = (idx: number, value: string) => {
    const next = [...captions];
    next[idx] = value;
    setCaptions(next);
  };

  const handleReportSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (f.size > MAX_FILE_SIZE) {
      toast.error(`"${f.name}" exceeds the 10MB limit`);
      if (reportInputRef.current) reportInputRef.current.value = '';
      return;
    }
    setReportFile(f);
  };

  const uploadPhotos = async () => {
    if (photos.length === 0) return;
    setUploadingPhotos(true);
    try {
      await lewServiceManagerApi.uploadVisitPhotos(orderId, photos, captions);
      toast.success(`${photos.length} photo(s) uploaded`);
      setPhotos([]);
      setCaptions([]);
      onCompleted();
    } catch {
      toast.error('Failed to upload photos');
    } finally {
      setUploadingPhotos(false);
    }
  };

  const handleCheckOut = async () => {
    if (!reportFile) {
      toast.error('Please select the visit report PDF');
      return;
    }
    setSubmitting(true);
    try {
      // 1) 업로드된 미제출 사진이 있으면 먼저 업로드
      if (photos.length > 0) {
        await lewServiceManagerApi.uploadVisitPhotos(orderId, photos, captions);
        setPhotos([]);
        setCaptions([]);
      }
      // 2) 방문 보고서 업로드 → fileSeq 확보
      const uploaded = await lewServiceManagerApi.uploadFile(
        orderId,
        reportFile,
        'LEW_SERVICE_VISIT_REPORT',
      );
      // 3) Check-out
      await lewServiceManagerApi.checkOut(orderId, {
        visitReportFileSeq: (uploaded as { fileSeq: number }).fileSeq,
        managerNote: managerNote.trim() || undefined,
      });
      toast.success('Checked out. Visit report submitted.');
      setReportFile(null);
      setManagerNote('');
      if (reportInputRef.current) reportInputRef.current.value = '';
      onCompleted();
    } catch {
      toast.error('Failed to submit visit report');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-1">Submit Visit Report</h2>
      <p className="text-sm text-gray-500 mb-4">
        Upload site photos, the visit report PDF, then check out.
      </p>

      {/* Photos */}
      <div className="space-y-3 mb-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-medium text-gray-700">Site Photos</p>
            <p className="text-xs text-gray-500">
              Max {MAX_PHOTOS} total ({existingPhotoCount} already uploaded)
            </p>
          </div>
          <div className="flex gap-2">
            <input
              ref={photoInputRef}
              type="file"
              multiple
              accept="image/*"
              className="hidden"
              onChange={handlePhotoSelect}
            />
            <Button
              variant="outline"
              size="sm"
              onClick={() => photoInputRef.current?.click()}
              disabled={existingPhotoCount + photos.length >= MAX_PHOTOS}
            >
              + Add Photos
            </Button>
            {photos.length > 0 && (
              <Button
                variant="primary"
                size="sm"
                onClick={uploadPhotos}
                loading={uploadingPhotos}
              >
                Upload {photos.length}
              </Button>
            )}
          </div>
        </div>

        {photos.length > 0 && (
          <div className="space-y-2">
            {photos.map((f, idx) => (
              <div
                key={`${f.name}-${idx}`}
                className="flex items-center gap-2 p-2 bg-gray-50 rounded border border-gray-200"
              >
                <span className="text-2xl" aria-hidden>&#128247;</span>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-700 truncate">{f.name}</p>
                  <input
                    type="text"
                    placeholder="Optional caption"
                    value={captions[idx] ?? ''}
                    onChange={(e) => updateCaption(idx, e.target.value)}
                    className="mt-1 w-full text-xs px-2 py-1 border border-gray-200 rounded"
                  />
                </div>
                <button
                  type="button"
                  onClick={() => removePhoto(idx)}
                  className="text-xs text-red-600 hover:text-red-800 px-2"
                  aria-label={`Remove ${f.name}`}
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Report */}
      <div className="space-y-2 mb-4">
        <p className="text-sm font-medium text-gray-700">Visit Report (PDF)</p>
        <input
          ref={reportInputRef}
          type="file"
          accept=".pdf"
          onChange={handleReportSelect}
          className="block w-full text-sm text-gray-600 file:mr-3 file:py-2 file:px-3 file:rounded file:border-0 file:text-sm file:font-medium file:bg-primary-50 file:text-primary-700 hover:file:bg-primary-100"
        />
        {reportFile && (
          <p className="text-xs text-gray-500">
            Selected: <span className="font-medium">{reportFile.name}</span>
          </p>
        )}
      </div>

      {/* Note */}
      <Textarea
        label="LEW Note (Optional)"
        rows={2}
        maxLength={2000}
        value={managerNote}
        onChange={(e) => setManagerNote(e.target.value)}
        placeholder="Summary of work done, measurements, or follow-up needed"
        className="resize-none"
      />

      <div className="mt-4 flex justify-end">
        <Button
          variant="primary"
          onClick={handleCheckOut}
          loading={submitting}
          disabled={!reportFile}
        >
          Check Out &amp; Submit Report
        </Button>
      </div>
    </Card>
  );
}
