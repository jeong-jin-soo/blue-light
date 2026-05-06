import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Textarea } from '../../components/ui/Textarea';
import { useToastStore } from '../../stores/toastStore';
import { expiredLicenseOrderApi } from '../../api/expiredLicenseOrderApi';
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

const MAX_DOCS = 10;
const MAX_DOC_SIZE = 20 * 1024 * 1024; // 20MB

function formatSize(bytes: number): string {
  return bytes < 1024 * 1024
    ? `${(bytes / 1024).toFixed(1)} KB`
    : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function NewExpiredLicenseOrderPage() {
  const navigate = useNavigate();
  const toast = useToastStore();

  const [submitting, setSubmitting] = useState(false);
  const [supportingDocs, setSupportingDocs] = useState<File[]>([]);
  const [priceTiers, setPriceTiers] = useState<MasterPrice[]>([]);

  const [formData, setFormData] = useState<FormState>({
    address: '',
    postalCode: '',
    buildingType: '',
    selectedKva: null,
    kvaUnknown: false,
    applicantNote: '',
  });

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

  const handleAddFiles = (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const incoming = Array.from(files);
    const remaining = MAX_DOCS - supportingDocs.length;
    if (remaining <= 0) {
      toast.error(`You can upload a maximum of ${MAX_DOCS} documents.`);
      return;
    }
    const valid: File[] = [];
    for (const file of incoming.slice(0, remaining)) {
      if (file.size > MAX_DOC_SIZE) {
        toast.error(`${file.name} exceeds the 20MB limit.`);
        continue;
      }
      valid.push(file);
    }
    if (incoming.length > remaining) {
      toast.warning(`Only the first ${remaining} file(s) were added (10 max).`);
    }
    setSupportingDocs((prev) => [...prev, ...valid]);
  };

  const handleRemoveFile = (index: number) => {
    setSupportingDocs((prev) => prev.filter((_, i) => i !== index));
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
      const order = await expiredLicenseOrderApi.createExpiredLicenseOrder(payload);

      let failedCount = 0;
      for (const file of supportingDocs) {
        try {
          await expiredLicenseOrderApi.uploadSupportingDocument(order.expiredLicenseOrderSeq, file);
        } catch {
          failedCount += 1;
        }
      }

      if (failedCount > 0) {
        toast.warning(`Order created, but ${failedCount} document(s) failed to upload. You can re-upload them from the order detail page.`);
      } else {
        toast.success('Expired License order submitted successfully!');
      }
      navigate(`/expired-license-orders/${order.expiredLicenseOrderSeq}`);
    } catch {
      toast.error('Failed to submit Expired License order. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/expired-license-orders')}
          className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
          aria-label="Back to Expired License orders"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          <span>Back</span>
        </button>
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">Request an Expired License Service</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            Renew or reinstate an expired electrical installation licence — on-site visit by a LEW
          </p>
        </div>
      </div>

      <form onSubmit={handleSubmit}>
        <Card>
          <div className="space-y-5">
            <Input
              label="Address"
              placeholder="e.g., 123 Orchard Road, #10-01, Singapore"
              value={formData.address}
              onChange={(e) => updateField('address', e.target.value)}
            />

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

            <Textarea
              label="Requirements Note"
              placeholder="Describe your situation (e.g., licence number, expiry date, renewal reason)..."
              value={formData.applicantNote}
              onChange={(e) => updateField('applicantNote', e.target.value)}
              maxLength={2000}
              rows={4}
              hint={`${formData.applicantNote.length}/2000`}
            />

            {/* Share Documents — 다중 파일, 임의 포맷 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                Share Documents
              </label>
              <p className="text-xs text-gray-500 mb-2">
                Upload supporting documents (e.g., expired licence, past SLDs, photos).
                Up to {MAX_DOCS} files, 20MB each — any file type.
              </p>
              <div className="bg-gray-50 rounded-lg p-4 border border-gray-200 space-y-3">
                {supportingDocs.length > 0 && (
                  <ul className="space-y-2">
                    {supportingDocs.map((file, idx) => (
                      <li
                        key={`${file.name}-${idx}`}
                        className="flex items-center justify-between px-3 py-2.5 bg-white rounded-lg border border-gray-200"
                      >
                        <div className="flex items-center gap-2 min-w-0">
                          <span className="text-lg">📄</span>
                          <div className="min-w-0">
                            <p className="text-sm font-medium text-gray-700 truncate">{file.name}</p>
                            <p className="text-xs text-gray-400">{formatSize(file.size)}</p>
                          </div>
                        </div>
                        <button
                          type="button"
                          onClick={() => handleRemoveFile(idx)}
                          className="text-gray-400 hover:text-red-500 transition-colors p-1"
                          aria-label={`Remove ${file.name}`}
                        >
                          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                          </svg>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}

                {supportingDocs.length < MAX_DOCS && (
                  <label className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors">
                    <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                    </svg>
                    <span className="text-sm text-gray-600">
                      Add file{supportingDocs.length > 0 ? 's' : ''} ({supportingDocs.length}/{MAX_DOCS})
                    </span>
                    <input
                      type="file"
                      multiple
                      className="hidden"
                      onChange={(e) => {
                        handleAddFiles(e.target.files);
                        e.target.value = '';
                      }}
                    />
                  </label>
                )}
              </div>
            </div>

            <div className="flex justify-end pt-4 border-t border-gray-100">
              <div className="flex gap-3">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => navigate('/expired-license-orders')}
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
