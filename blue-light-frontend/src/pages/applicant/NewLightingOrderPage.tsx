import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Textarea } from '../../components/ui/Textarea';
import { useToastStore } from '../../stores/toastStore';
import { lightingOrderApi } from '../../api/lightingOrderApi';

export default function NewLightingOrderPage() {
  const navigate = useNavigate();
  const toast = useToastStore();

  const [submitting, setSubmitting] = useState(false);

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
      const order = await lightingOrderApi.createLightingOrder(payload);

      toast.success('Lighting Layout order submitted successfully!');
      navigate(`/lighting-orders/${order.lightingOrderSeq}`);
    } catch {
      toast.error('Failed to submit Lighting Layout order. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/lighting-orders')}
          className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
          aria-label="Back to Lighting Layout orders"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          <span>Back</span>
        </button>
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">New Lighting Layout Order</h1>
          <p className="text-sm text-gray-500 mt-0.5">Request a Lighting layout drawing</p>
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
              placeholder="Describe your requirements for the Lighting Layout..."
              value={formData.applicantNote}
              onChange={(e) => updateField('applicantNote', e.target.value)}
              maxLength={2000}
              rows={4}
              hint={`${formData.applicantNote.length}/2000`}
            />


            {/* Submit */}
            <div className="flex justify-end pt-4 border-t border-gray-100">
              <div className="flex gap-3">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => navigate('/lighting-orders')}
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