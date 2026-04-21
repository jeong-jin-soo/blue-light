import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Textarea } from '../../components/ui/Textarea';
import { useToastStore } from '../../stores/toastStore';
import { lewServiceOrderApi } from '../../api/lewServiceOrderApi';
import priceApi from '../../api/priceApi';
import { BUILDING_TYPES, KVA_UNKNOWN_SENTINEL, KVA_UNKNOWN_PLACEHOLDER } from '../../constants/orderFormOptions';
import type { MasterPrice } from '../../types';

interface FormState {
  address: string;
  postalCode: string;
  buildingType: string;
  selectedKva: number | null;
  kvaUnknown: boolean;
  applicantNote: string;
}

export default function NewLewServiceOrderPage() {
  const navigate = useNavigate();
  const toast = useToastStore();

  const [submitting, setSubmitting] = useState(false);
  const [sketchFile, setSketchFile] = useState<File | null>(null);
  const [priceTiers, setPriceTiers] = useState<MasterPrice[]>([]);

  const [formData, setFormData] = useState<FormState>({
    address: '',
    postalCode: '',
    buildingType: '',
    selectedKva: null,
    kvaUnknown: false,
    applicantNote: '',
  });

  // kVA 옵션을 위해 가격 tier 목록만 로드 (가격 자체는 표시하지 않음)
  useEffect(() => {
    priceApi.getPrices()
      .then((tiers) => setPriceTiers(tiers.filter((t) => t.isActive)))
      .catch(() => { /* non-critical */ });
  }, []);

  const updateField = <K extends keyof FormState>(field: K, value: FormState[K]) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  const handleKvaChange = (v: string) => {
    if (v === KVA_UNKNOWN_SENTINEL) {
      setFormData((prev) => ({ ...prev, kvaUnknown: true, selectedKva: null }));
    } else {
      setFormData((prev) => ({
        ...prev,
        kvaUnknown: false,
        selectedKva: v ? Number(v) : null,
      }));
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const payload = {
        address: formData.address.trim() || undefined,
        postalCode: formData.postalCode.trim() || undefined,
        buildingType: formData.buildingType || undefined,
        selectedKva: formData.kvaUnknown
          ? KVA_UNKNOWN_PLACEHOLDER
          : (formData.selectedKva ?? undefined),
        applicantNote: formData.applicantNote.trim() || undefined,
      };
      const order = await lewServiceOrderApi.createLewServiceOrder(payload);

      if (sketchFile) {
        try {
          await lewServiceOrderApi.uploadSketchFile(order.lewServiceOrderSeq, sketchFile, 'SKETCH_LEW_SERVICE');
        } catch {
          toast.warning('LEW Service order created, but sketch upload failed. You can upload it later.');
          navigate(`/lew-service-orders/${order.lewServiceOrderSeq}`);
          return;
        }
      }

      toast.success('LEW Service order submitted successfully!');
      navigate(`/lew-service-orders/${order.lewServiceOrderSeq}`);
    } catch {
      toast.error('Failed to submit LEW Service order. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/lew-service-orders')}
          className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
          aria-label="Back to LEW Service orders"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          <span>Back</span>
        </button>
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">Request a LEW Service</h1>
          <p className="text-sm text-gray-500 mt-0.5">Submit a request for on-site electrical work</p>
        </div>
      </div>

      <form onSubmit={handleSubmit}>
        <Card>
          <div className="space-y-5">
            {/* Address */}
            <Input
              label="Address"
              placeholder="e.g., 123 Orchard Road, #10-01, Singapore"
              value={formData.address}
              onChange={(e) => updateField('address', e.target.value)}
            />

            {/* Postal Code & Building Type */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input
                label="Postal Code"
                placeholder="e.g., 238888"
                value={formData.postalCode}
                onChange={(e) => updateField('postalCode', e.target.value)}
              />
              <Select
                label="Building Type"
                value={formData.buildingType}
                onChange={(e) => updateField('buildingType', e.target.value)}
                options={BUILDING_TYPES}
              />
            </div>

            {/* kVA — 가격표 없이 tier 선택 */}
            <Select
              label="Electric Box (kVA)"
              value={
                formData.kvaUnknown
                  ? KVA_UNKNOWN_SENTINEL
                  : (formData.selectedKva ? String(formData.selectedKva) : '')
              }
              onChange={(e) => handleKvaChange(e.target.value)}
              options={[
                { value: '', label: 'Select kVA capacity' },
                { value: KVA_UNKNOWN_SENTINEL, label: "I don't know — let the team confirm later" },
                { value: '__DIVIDER__', label: '────────────────────', disabled: true },
                ...priceTiers.map((tier) => ({
                  value: String(tier.kvaMin),
                  label: tier.description || `${tier.kvaMin}–${tier.kvaMax} kVA`,
                })),
              ]}
            />

            {/* Applicant Note */}
            <Textarea
              label="Requirements Note"
              placeholder="Describe your requirements for the LEW Service..."
              value={formData.applicantNote}
              onChange={(e) => updateField('applicantNote', e.target.value)}
              maxLength={2000}
              rows={4}
              hint={`${formData.applicantNote.length}/2000`}
            />

            {/* Sketch File */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Sketch File
              </label>
              <p className="text-xs text-gray-500 mb-2">
                Upload a sketch or reference drawing. Accepted: images, PDF, DWG.
              </p>
              <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                {sketchFile ? (
                  <div className="flex items-center justify-between px-3 py-2.5 bg-white rounded-lg border border-gray-200">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-lg">📄</span>
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-gray-700 truncate">{sketchFile.name}</p>
                        <p className="text-xs text-gray-400">
                          {sketchFile.size < 1024 * 1024
                            ? `${(sketchFile.size / 1024).toFixed(1)} KB`
                            : `${(sketchFile.size / (1024 * 1024)).toFixed(1)} MB`}
                        </p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => setSketchFile(null)}
                      className="text-gray-400 hover:text-red-500 transition-colors p-1"
                      aria-label="Remove sketch file"
                    >
                      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                ) : (
                  <label className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors">
                    <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                    </svg>
                    <span className="text-sm text-gray-600">Choose sketch file</span>
                    <input
                      type="file"
                      accept=".pdf,.jpg,.jpeg,.png,.dwg,.dxf,.dgn,.tif,.tiff,.gif,.zip"
                      className="hidden"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) {
                          if (file.size > 10 * 1024 * 1024) {
                            toast.error('File size must be less than 10MB');
                            return;
                          }
                          setSketchFile(file);
                        }
                        e.target.value = '';
                      }}
                    />
                  </label>
                )}
              </div>
            </div>

            {/* Submit */}
            <div className="flex justify-end pt-4 border-t border-gray-100">
              <div className="flex gap-3">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => navigate('/lew-service-orders')}
                >
                  Cancel
                </Button>
                <Button type="submit" loading={submitting}>
                  Submit Request
                </Button>
              </div>
            </div>
          </div>
        </Card>
      </form>
    </div>
  );
}
