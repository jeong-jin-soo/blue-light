import { useState } from 'react';
import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { useToastStore } from '../../../stores/toastStore';
import { expiredLicenseManagerApi } from '../../../api/expiredLicenseManagerApi';

interface Props {
  orderId: number;
  onCheckedIn: () => void;
}

/**
 * OnSiteChecklistCard — PR 3.
 * <p>VISIT_SCHEDULED + checkInAt==null 상태에서 노출. "Check In Now" 버튼.
 */
export function OnSiteChecklistCard({ orderId, onCheckedIn }: Props) {
  const toast = useToastStore();
  const [loading, setLoading] = useState(false);

  const handleCheckIn = async () => {
    setLoading(true);
    try {
      await expiredLicenseManagerApi.checkIn(orderId);
      toast.success('Checked in');
      onCheckedIn();
    } catch {
      toast.error('Failed to check in');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card>
      <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
        <div className="flex items-start gap-3">
          <span className="text-2xl" aria-hidden>&#128205;</span>
          <div className="flex-1">
            <p className="text-sm font-medium text-blue-900">Arrived on site?</p>
            <p className="text-xs text-blue-700 mt-1">
              Check in to start the visit. Your arrival time will be recorded and the applicant
              will see a "LEW is on site" banner.
            </p>
            <div className="mt-3">
              <Button variant="primary" size="sm" onClick={handleCheckIn} loading={loading}>
                Check In Now
              </Button>
            </div>
          </div>
        </div>
      </div>
    </Card>
  );
}
