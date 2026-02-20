import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Textarea } from '../../components/ui/Textarea';
import { useToastStore } from '../../stores/toastStore';
import { sldOrderApi } from '../../api/sldOrderApi';

export default function NewSldOrderPage() {
  const navigate = useNavigate();
  const toast = useToastStore();

  const [submitting, setSubmitting] = useState(false);
  const [sketchFile, setSketchFile] = useState<File | null>(null);

  const [formData, setFormData] = useState({
    address: '',
    postalCode: '',
    buildingType: '',
    selectedKva: '' as string,
    applicantNote: '',
  });

  const updateField = (field: string, value: string) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const payload = {
        address: formData.address.trim() || undefined,
        postalCode: formData.postalCode.trim() || undefined,
        buildingType: formData.buildingType.trim() || undefined,
        selectedKva: formData.selectedKva ? Number(formData.selectedKva) : undefined,
        applicantNote: formData.applicantNote.trim() || undefined,
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
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center gap-3">
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
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">New SLD Order</h1>
          <p className="text-sm text-gray-500 mt-0.5">Request a Single Line Diagram drawing</p>
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
              <Input
                label="Building Type"
                placeholder="e.g., Residential, Commercial"
                value={formData.buildingType}
                onChange={(e) => updateField('buildingType', e.target.value)}
              />
            </div>

            {/* kVA */}
            <Input
              label="Capacity (kVA)"
              type="number"
              placeholder="e.g., 45"
              value={formData.selectedKva}
              onChange={(e) => updateField('selectedKva', e.target.value)}
              min={0}
            />

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
