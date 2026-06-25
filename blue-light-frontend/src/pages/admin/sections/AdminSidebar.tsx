import { Link } from 'react-router-dom';
import { fullName } from '../../../utils/formatName';
import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Badge } from '../../../components/ui/Badge';
import { StepTracker } from '../../../components/domain/StepTracker';
import { InfoField } from '../../../components/common/InfoField';
import { STATUS_STEPS, getStatusStep } from '../../../utils/applicationUtils';
import fileApi from '../../../api/fileApi';
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
  /** 완료 건 재개(reopen) — ADMIN 전용. COMPLETED 상태에서만 노출. */
  onReopenClick?: () => void;
  onAssignLewClick: () => void;
  onUnassignLewClick: () => void;
  /** ★ Concierge 강화 PR-4 — Manual Payment 모달 트리거 (ADMIN/SYSTEM_ADMIN 전용). */
  onManualPaymentClick?: () => void;
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
  onReopenClick,
  onAssignLewClick,
  onUnassignLewClick,
  onManualPaymentClick,
}: Props) {
  return (
    <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
      {/* Status Tracker (desktop only) */}
      <div className="hidden lg:block">
        <Card>
          <h3 className="text-sm font-semibold text-gray-800 mb-4">Progress</h3>
          <StepTracker
            steps={STATUS_STEPS}
            currentStep={getStatusStep(application.status)}
            variant="vertical"
          />
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

          {application.status === 'PENDING_PAYMENT' && isAdmin && (
            <Button variant="outline" fullWidth size="sm" onClick={onPaymentClick} loading={actionLoading}>
              💳 Confirm Payment
            </Button>
          )}

          {application.status === 'PENDING_PAYMENT' && !isAdmin && (
            <div className="bg-gray-50 rounded-lg p-3 border border-gray-200 text-center">
              <p className="text-xs text-gray-500">Waiting for admin to confirm payment.</p>
            </div>
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
            <div className="space-y-2">
              <div className="bg-success-50 rounded-lg p-3 border border-success-200 text-center">
                <span className="text-lg">🎉</span>
                <p className="text-xs text-success-700 mt-1">This application is completed.</p>
              </div>
              {/* 완료 건은 신청자·LEW 파일 수정 잠금. 보정이 필요하면 ADMIN 이 재개(reopen)한다. */}
              {isAdmin && onReopenClick && (
                <Button variant="outline" fullWidth size="sm" onClick={onReopenClick} loading={actionLoading}>
                  🔓 Reopen for editing
                </Button>
              )}
            </div>
          )}


          {/* PR-4 (admin-manual-email-spec §7.3): 신청 컨텍스트 prefill 진입점.
              ADMIN 전용 — 신청자에게 ad-hoc 이메일 발송 시 신청자 + 신청번호가 자동 prefill 된다. */}
          {isAdmin && (
            <Link
              to={`/admin/manual-emails?related=${application.applicationSeq}&recipientType=APPLICANT&recipientUserSeq=${application.userSeq}`}
              className="block"
            >
              <Button variant="ghost" fullWidth size="sm">
                📧 Send manual email
              </Button>
            </Link>
          )}

          {/* ★ Concierge 강화 + 별도 수금 PR-4 — Manual Payment 진입점.
              스펙 D3=C: ADMIN/SYSTEM_ADMIN 은 PENDING_REVIEW 부터 결제 가능 상태에서 호출 가능.
              PAID/IN_PROGRESS/COMPLETED/EXPIRED 는 백엔드 409 차단 — UI 도 노출하지 않는다. */}
          {isAdmin && onManualPaymentClick
            && (application.status === 'PENDING_REVIEW'
              || application.status === 'REVISION_REQUESTED'
              || application.status === 'PENDING_PAYMENT') && (
            <Button
              variant="ghost"
              fullWidth
              size="sm"
              onClick={onManualPaymentClick}
              loading={actionLoading}
            >
              💰 Record manual payment
            </Button>
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
                  <p className="text-sm font-medium text-gray-800">{fullName(application.assignedLewFirstName, application.assignedLewLastName)}</p>
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
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-semibold text-gray-800">Licence Information</h3>
            {application.licenseStatus === 'EXPIRED' && (
              <span className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
                ⏰ Expired
              </span>
            )}
          </div>
          <div className="space-y-3">
            <InfoField label="Licence Number" value={application.licenseNumber} />
            {application.licenseExpiryDate && (
              <InfoField
                label="Expiry Date"
                value={new Date(application.licenseExpiryDate).toLocaleDateString()}
              />
            )}
          </div>
          {/* Licence PDF quick access */}
          {(() => {
            const licencePdf = files.find((f) => f.fileType === 'LICENSE_PDF');
            if (licencePdf) {
              return (
                <div className="mt-3">
                  <Button
                    variant="outline"
                    size="sm"
                    fullWidth
                    onClick={() => fileApi.downloadFile(licencePdf.fileSeq, licencePdf.originalFilename || 'licence.pdf')}
                  >
                    📄 Download Licence PDF
                  </Button>
                </div>
              );
            }
            return (
              <p className="text-xs text-gray-400 mt-3">
                No licence PDF uploaded yet. Upload via Documents section.
              </p>
            );
          })()}
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
