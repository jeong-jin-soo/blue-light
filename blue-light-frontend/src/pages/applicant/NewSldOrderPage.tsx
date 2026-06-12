import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Textarea } from '../../components/ui/Textarea';
import { PageHeader } from '../../components/ui/PageHeader';
import { useToastStore } from '../../stores/toastStore';
import { sldOrderApi } from '../../api/sldOrderApi';
import priceApi from '../../api/priceApi';
import { BUILDING_TYPES, KVA_UNKNOWN_SENTINEL, KVA_UNKNOWN_PLACEHOLDER } from '../../constants/orderFormOptions';
import type { MasterPrice } from '../../types';

interface FormState {
  address: string;
  postalCode: string;
  buildingType: string;
  selectedKva: number | null;
  kvaUnknown: boolean;
  ampere: string;
  applicantNote: string;
  endorsementRequested: boolean;
  /** endorsement 선택 시 추가 비용 발생을 확인했는지 여부 */
  endorsementFeeAcknowledged: boolean;
}

export default function NewSldOrderPage() {
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
    ampere: '',
    applicantNote: '',
    endorsementRequested: true,
    endorsementFeeAcknowledged: false,
  });

  // kVA 옵션 tier 목록만 로드 (가격은 표시하지 않음 — SLD 주문은 별도 견적)
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
    if (formData.endorsementRequested && !formData.endorsementFeeAcknowledged) {
      toast.error('LEW endorsement 추가 비용 발생에 대한 확인이 필요합니다.');
      return;
    }
    setSubmitting(true);
    try {
      const payload = {
        address: formData.address.trim() || undefined,
        postalCode: formData.postalCode.trim() || undefined,
        buildingType: formData.buildingType || undefined,
        selectedKva: formData.kvaUnknown
          ? KVA_UNKNOWN_PLACEHOLDER
          : (formData.selectedKva ?? undefined),
        ampere: formData.ampere.trim() || undefined,
        applicantNote: formData.applicantNote.trim() || undefined,
        endorsementRequested: formData.endorsementRequested,
      };
      const order = await sldOrderApi.createSldOrder(payload);

      // Upload sketch file if attached
      if (sketchFile) {
        try {
          await sldOrderApi.uploadSketchFile(order.sldOrderSeq, sketchFile, 'SKETCH_SLD');
        } catch {
          toast.warning('SLD order created, but sketch upload failed. You can upload it later.');
          navigate(`/sld-orders/${order.sldOrderSeq}`);
          return;
        }
      }

      toast.success('SLD order submitted successfully!');
      navigate(`/sld-orders/${order.sldOrderSeq}`);
    } catch {
      toast.error('Failed to submit SLD order. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="max-w-7xl mx-auto space-y-6">
      {/* Back navigation */}
      <button
        onClick={() => navigate('/sld-orders')}
        className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
        aria-label="Back to SLD orders"
      >
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
        </svg>
        <span>Back</span>
      </button>

      <PageHeader
        title="New SLD Order"
        subtitle="Request a Single Line Diagram drawing"
      />

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

            {/* Ampere (optional, free-form text) */}
            <Input
              label="Ampere (optional)"
              value={formData.ampere}
              onChange={(e) => updateField('ampere', e.target.value)}
              placeholder="e.g., 63A DP / TPN"
              maxLength={30}
            />

            {/* LEW Endorsement Option */}
            {(() => {
              const matched = formData.selectedKva
                ? priceTiers.find(
                    (t) =>
                      formData.selectedKva! >= t.kvaMin &&
                      formData.selectedKva! <= t.kvaMax,
                  )
                : null;
              const sldHint = matched?.sldPrice ?? null;
              const endorseHint = matched?.endorsementPrice ?? null;
              return (
                <div className="rounded-lg border border-gray-200 bg-gray-50 p-4">
                  <label className="flex items-start gap-3 cursor-pointer">
                    <input
                      type="checkbox"
                      className="mt-0.5 w-4 h-4 text-primary-600 border-gray-300 rounded focus:ring-primary-500"
                      checked={formData.endorsementRequested}
                      onChange={(e) => {
                        updateField('endorsementRequested', e.target.checked);
                        if (!e.target.checked) {
                          updateField('endorsementFeeAcknowledged', false);
                        }
                      }}
                    />
                    <div className="flex-1">
                      <div className="text-sm font-medium text-gray-800">
                        Include LEW endorsement (인증 도장)
                      </div>
                      <p className="text-xs text-gray-500 mt-0.5">
                        SP Group 제출 시 필요한 LEW 인증 도장을 SLD 도면에 함께 받습니다. 도면만
                        필요하면 체크를 해제하세요.
                      </p>
                      {(sldHint != null || endorseHint != null) && (
                        <p className="text-xs text-gray-500 mt-1.5">
                          <span className="font-medium text-gray-600">Indicative price</span>: SLD ${' '}
                          {sldHint != null ? Number(sldHint).toFixed(2) : '—'}
                          {formData.endorsementRequested && endorseHint != null && (
                            <> + Endorsement ${Number(endorseHint).toFixed(2)}</>
                          )}{' '}
                          <span className="text-gray-400">(subject to manager quote)</span>
                        </p>
                      )}
                    </div>
                  </label>

                  {/* 추가 비용 발생 확인 — endorsement 선택 시에만 노출 */}
                  {formData.endorsementRequested && (
                    <div className="mt-3 pt-3 border-t border-gray-200">
                      <label className="flex items-start gap-3 cursor-pointer">
                        <input
                          type="checkbox"
                          className="mt-0.5 w-4 h-4 text-warning-600 border-gray-300 rounded focus:ring-warning-500"
                          checked={formData.endorsementFeeAcknowledged}
                          onChange={(e) =>
                            updateField('endorsementFeeAcknowledged', e.target.checked)
                          }
                          required
                        />
                        <div className="flex-1">
                          <div className="text-sm font-medium text-warning-800">
                            추가 비용 발생 확인 <span className="text-error-600">*</span>
                          </div>
                          <p className="text-xs text-warning-700 mt-0.5">
                            LEW 인증 도장(endorsement)을 추가하면 SLD 도면 비용에 더해
                            {endorseHint != null && (
                              <> 약 ${Number(endorseHint).toFixed(2)}의</>
                            )}{' '}
                            추가 비용이 발생합니다. 최종 금액은 매니저 견적으로 확정됩니다.
                            이에 동의합니다.
                          </p>
                        </div>
                      </label>
                    </div>
                  )}
                </div>
              );
            })()}

            {/* Applicant Note */}
            <Textarea
              label="Requirements Note"
              placeholder="Describe your requirements for the SLD drawing..."
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
                  onClick={() => navigate('/sld-orders')}
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  loading={submitting}
                  disabled={formData.endorsementRequested && !formData.endorsementFeeAcknowledged}
                >
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
