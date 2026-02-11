import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Badge } from '../../../components/ui/Badge';
import { StepTracker } from '../../../components/domain/StepTracker';
import { InfoField } from '../../../components/common/InfoField';
import { STATUS_STEPS, getStatusStep } from '../../../utils/applicationUtils';
import type { AdminApplication, FileInfo, Payment } from '../../../types';

interface Props {
  application: AdminApplication;
  files: FileInfo[];
  payments: Payment[];
  isAdmin: boolean;
  actionLoading: boolean;
  onRevisionClick: () => void;
  onApproveClick: () => void;
  onPaymentClick: () => void;
  onProcessingClick: () => void;
  onCompleteClick: () => void;
  onAssignLewClick: () => void;
  onUnassignLewClick: () => void;
}

/**
 * 사이드바 섹션
 * - 진행 트래커, 관리자 액션, LEW 배정, 면허 정보, Quick Info
 */
export function AdminSidebar({
  application,
  files,
  payments,
  isAdmin,
  actionLoading,
  onRevisionClick,
  onApproveClick,
  onPaymentClick,
  onProcessingClick,
  onCompleteClick,
  onAssignLewClick,
  onUnassignLewClick,
}: Props) {
  return (
    <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
      {/* Status Tracker (desktop only) */}
      <div className="hidden lg:block">
        <Card>
          <h3 className="text-sm font-semibold text-gray-800 mb-4">Progress</h3>
          {application.status === 'EXPIRED' ? (
            <div className="text-center py-4">
              <span className="text-3xl">⏰</span>
              <p className="text-sm font-medium text-gray-700 mt-2">Application Expired</p>
            </div>
          ) : (
            <StepTracker
              steps={STATUS_STEPS}
              currentStep={getStatusStep(application.status)}
              variant="vertical"
            />
          )}
        </Card>
      </div>

      {/* Admin Actions */}
      <Card>
        <h3 className="text-sm font-semibold text-gray-800 mb-4">Admin Actions</h3>
        <div className="space-y-2">
          {application.status === 'PENDING_REVIEW' && (
            <>
              <Button variant="outline" fullWidth size="sm" onClick={onRevisionClick} loading={actionLoading}>
                📝 Request Revision
              </Button>
              <Button variant="primary" fullWidth size="sm" onClick={onApproveClick} loading={actionLoading}>
                ✅ Approve & Request Payment
              </Button>
            </>
          )}

          {application.status === 'REVISION_REQUESTED' && (
            <div className="bg-warning-50 rounded-lg p-3 border border-warning-200 text-center">
              <span className="text-lg">⏳</span>
              <p className="text-xs text-warning-700 mt-1">
                Waiting for applicant to revise and resubmit.
              </p>
            </div>
          )}

          {application.status === 'PENDING_PAYMENT' && (
            <Button variant="outline" fullWidth size="sm" onClick={onPaymentClick} loading={actionLoading}>
              💳 Confirm Payment
            </Button>
          )}

          {application.status === 'PAID' && (
            <Button variant="outline" fullWidth size="sm" onClick={onProcessingClick} loading={actionLoading}>
              🔄 Start Processing
            </Button>
          )}

          {application.status === 'IN_PROGRESS' && (
            <Button variant="primary" fullWidth size="sm" onClick={onCompleteClick} loading={actionLoading}>
              ✅ Complete & Issue Licence
            </Button>
          )}

          {application.status === 'COMPLETED' && (
            <div className="bg-success-50 rounded-lg p-3 border border-success-200 text-center">
              <span className="text-lg">🎉</span>
              <p className="text-xs text-success-700 mt-1">This application is completed.</p>
            </div>
          )}

          {application.status === 'EXPIRED' && (
            <div className="bg-gray-50 rounded-lg p-3 border border-gray-200 text-center">
              <p className="text-xs text-gray-500">No actions available for expired applications.</p>
            </div>
          )}
        </div>
      </Card>

      {/* Assigned LEW (ADMIN only) */}
      {isAdmin && (
        <Card>
          <h3 className="text-sm font-semibold text-gray-800 mb-3">Assigned LEW</h3>
          {application.assignedLewSeq ? (
            <div className="space-y-3">
              <div className="flex items-center gap-3 p-3 bg-primary-50 rounded-lg border border-primary-100">
                <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
                  <span className="text-sm">⚡</span>
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium text-gray-800">{application.assignedLewName}</p>
                  <p className="text-xs text-gray-500 truncate">{application.assignedLewEmail}</p>
                  {application.assignedLewLicenceNo && (
                    <p className="text-xs text-primary-600 font-mono mt-0.5">{application.assignedLewLicenceNo}</p>
                  )}
                  {application.assignedLewGrade && (
                    <Badge variant="info" className="mt-1 text-[10px]">
                      {application.assignedLewGrade.replace('GRADE_', 'G')} (≤{application.assignedLewMaxKva === 9999 ? '400kV' : `${application.assignedLewMaxKva}kVA`})
                    </Badge>
                  )}
                </div>
              </div>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" fullWidth onClick={onAssignLewClick}>
                  Change
                </Button>
                <Button variant="ghost" size="sm" fullWidth onClick={onUnassignLewClick}>
                  Remove
                </Button>
              </div>
            </div>
          ) : (
            <div className="text-center py-2">
              <p className="text-sm text-gray-500 mb-3">No LEW assigned</p>
              <Button variant="outline" size="sm" fullWidth onClick={onAssignLewClick}>
                ⚡ Assign LEW
              </Button>
            </div>
          )}
        </Card>
      )}

      {/* Licence Info */}
      {application.status === 'COMPLETED' && application.licenseNumber && (
        <Card>
          <h3 className="text-sm font-semibold text-gray-800 mb-4">Licence Information</h3>
          <div className="space-y-3">
            <InfoField label="Licence Number" value={application.licenseNumber} />
            {application.licenseExpiryDate && (
              <InfoField
                label="Expiry Date"
                value={new Date(application.licenseExpiryDate).toLocaleDateString()}
              />
            )}
          </div>
        </Card>
      )}

      {/* Quick Info */}
      <Card>
        <h3 className="text-sm font-semibold text-gray-800 mb-3">Quick Info</h3>
        <div className="space-y-2 text-sm">
          <div className="flex justify-between">
            <span className="text-gray-500">Application ID</span>
            <span className="font-medium text-gray-700">#{application.applicationSeq}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">Submitted</span>
            <span className="font-medium text-gray-700">
              {new Date(application.createdAt).toLocaleDateString()}
            </span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">Last Updated</span>
            <span className="font-medium text-gray-700">
              {new Date(application.updatedAt).toLocaleDateString()}
            </span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">Documents</span>
            <span className="font-medium text-gray-700">{files.length} file(s)</span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">Payments</span>
            <span className="font-medium text-gray-700">{payments.length} record(s)</span>
          </div>
        </div>
      </Card>
    </div>
  );
}
