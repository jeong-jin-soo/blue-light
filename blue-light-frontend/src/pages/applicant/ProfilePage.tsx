import { useEffect, useState, useMemo } from 'react';
import { isValidPaynow, type PaynowType } from '../../constants/paynow';
import { LEW_GRADE_SELECT_OPTIONS } from '../../constants/lewGrade';
import { PaynowField } from '../../components/domain/PaynowField';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { Card, CardHeader } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { PageHeader } from '../../components/ui/PageHeader';
import { useToastStore } from '../../stores/toastStore';
import { useFormGuard } from '../../hooks/useFormGuard';
import userApi from '../../api/userApi';
import type { User } from '../../types';

export default function ProfilePage() {
  const { user: authUser, logout } = useAuthStore();
  const toast = useToastStore();
  const navigate = useNavigate();

  const [profile, setProfile] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  // Profile form
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [phone, setPhone] = useState('');
  const [lewLicenceNo, setLewLicenceNo] = useState('');
  const [lewGrade, setLewGrade] = useState('');
  const [paynowType, setPaynowType] = useState<PaynowType>('MOBILE');
  const [paynowValue, setPaynowValue] = useState('');
  const [companyName, setCompanyName] = useState('');
  const [uen, setUen] = useState('');
  const [designation, setDesignation] = useState('');
  const [correspondenceAddress, setCorrespondenceAddress] = useState('');
  const [correspondencePostalCode, setCorrespondencePostalCode] = useState('');
  const [profileSaving, setProfileSaving] = useState(false);
  const [profileErrors, setProfileErrors] = useState<Record<string, string>>({});

  // Password form
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordErrors, setPasswordErrors] = useState<Record<string, string>>({});

  // PDPA: Data management
  const [exporting, setExporting] = useState(false);
  const [showConsentWithdrawConfirm, setShowConsentWithdrawConfirm] = useState(false);
  const [withdrawingConsent, setWithdrawingConsent] = useState(false);
  const [showAccountDeleteConfirm, setShowAccountDeleteConfirm] = useState(false);
  const [deletingAccount, setDeletingAccount] = useState(false);

  useEffect(() => {
    userApi
      .getMyProfile()
      .then((data) => {
        setProfile(data);
        setFirstName(data.firstName);
        setLastName(data.lastName);
        setPhone(data.phone || '');
        setLewLicenceNo(data.lewLicenceNo || '');
        setLewGrade(data.lewGrade || '');
        if (data.paynowType) setPaynowType(data.paynowType);
        setPaynowValue(data.paynowValue || '');
        setCompanyName(data.companyName || '');
        setUen(data.uen || '');
        setDesignation(data.designation || '');
        setCorrespondenceAddress(data.correspondenceAddress || '');
        setCorrespondencePostalCode(data.correspondencePostalCode || '');
      })
      .catch(() => {
        toast.error('Failed to load profile');
      })
      .finally(() => setLoading(false));
  }, []);

  // Form leave guard — warn when navigating away with unsaved changes
  const isProfileDirty = useMemo(() => {
    if (!profile) return false;
    return (
      firstName !== profile.firstName ||
      lastName !== profile.lastName ||
      phone !== (profile.phone || '') ||
      companyName !== (profile.companyName || '') ||
      uen !== (profile.uen || '') ||
      designation !== (profile.designation || '') ||
      correspondenceAddress !== (profile.correspondenceAddress || '') ||
      correspondencePostalCode !== (profile.correspondencePostalCode || '')
    );
  }, [profile, firstName, lastName, phone, companyName, uen, designation, correspondenceAddress, correspondencePostalCode]);
  useFormGuard(isProfileDirty);

  const isLewUser = profile?.role === 'LEW' || authUser?.role === 'LEW';

  const handleProfileSave = async () => {
    const errors: Record<string, string> = {};
    if (!firstName.trim()) errors.firstName = 'First name is required';
    if (!lastName.trim()) errors.lastName = 'Last name is required';
    if (isLewUser && paynowValue.trim() && !isValidPaynow(paynowType, paynowValue)) {
      errors.paynowValue =
        paynowType === 'MOBILE'
          ? 'Enter an 8-digit Singapore mobile number (starting with 8 or 9).'
          : 'Enter a 10-character company UEN.';
    }
    setProfileErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setProfileSaving(true);
    try {
      const updated = await userApi.updateProfile({
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        phone: phone.trim() || undefined,
        lewLicenceNo: lewLicenceNo.trim() || undefined,
        lewGrade: lewGrade || undefined,
        ...(isLewUser && paynowValue.trim()
          ? { paynowType, paynowValue: paynowValue.trim() }
          : {}),
        companyName: companyName.trim() || undefined,
        uen: uen.trim() || undefined,
        designation: designation.trim() || undefined,
        correspondenceAddress: correspondenceAddress.trim() || undefined,
        correspondencePostalCode: correspondencePostalCode.trim() || undefined,
      });
      setProfile(updated);
      toast.success('Profile updated successfully');
    } catch {
      toast.error('Failed to update profile');
    } finally {
      setProfileSaving(false);
    }
  };

  const handlePasswordChange = async () => {
    const errors: Record<string, string> = {};
    if (!currentPassword) errors.currentPassword = 'Current password is required';
    if (!newPassword) errors.newPassword = 'New password is required';
    if (newPassword.length < 8) errors.newPassword = 'Password must be at least 8 characters';
    if (newPassword.length > 20) errors.newPassword = 'Password must be at most 20 characters';
    if (newPassword !== confirmPassword) errors.confirmPassword = 'Passwords do not match';
    setPasswordErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setPasswordSaving(true);
    try {
      await userApi.changePassword({
        currentPassword,
        newPassword,
      });
      toast.success('Password changed successfully');
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch {
      toast.error('Failed to change password. Please check your current password.');
    } finally {
      setPasswordSaving(false);
    }
  };

  // ── PDPA: Data Export ──
  const handleExportData = async () => {
    setExporting(true);
    try {
      const data = await userApi.exportMyData();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `licensekaki-data-export-${new Date().toISOString().slice(0, 10)}.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      toast.success('Your data has been exported successfully');
    } catch {
      toast.error('Failed to export data');
    } finally {
      setExporting(false);
    }
  };

  // ── PDPA: Consent Withdrawal ──
  const handleWithdrawConsent = async () => {
    setShowConsentWithdrawConfirm(false);
    setWithdrawingConsent(true);
    try {
      await userApi.withdrawPdpaConsent();
      setProfile((prev) => prev ? { ...prev, pdpaConsentAt: undefined } : prev);
      toast.success('PDPA consent has been withdrawn. Some services may be restricted.');
    } catch {
      toast.error('Failed to withdraw consent');
    } finally {
      setWithdrawingConsent(false);
    }
  };

  // ── PDPA: Account Deletion ──
  const handleDeleteAccount = async () => {
    setShowAccountDeleteConfirm(false);
    setDeletingAccount(true);
    try {
      await userApi.deleteMyAccount();
      toast.success('Your account has been deleted');
      logout();
      navigate('/login');
    } catch {
      toast.error('Failed to delete account');
    } finally {
      setDeletingAccount(false);
    }
  };

  // 신청자 전용 섹션 노출 여부 — 비즈니스 정보(LOA/EMA 인쇄용), 서명(LOA 프리로드),
  // PDPA 데이터 관리(고객 셀프서비스)는 APPLICANT 에게만 의미가 있다.
  // ADMIN/LEW/SYSTEM_ADMIN 은 개인 정보 + 비밀번호 변경 + 계정 상세만 노출.
  const isApplicant = (profile?.role || authUser?.role) === 'APPLICANT';

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading profile..." />
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <PageHeader title="My Profile" subtitle="Manage your account information" />

      {/* Account summary */}
      <Card>
        <div className="flex items-center gap-4">
          <div className="w-14 h-14 bg-primary-100 rounded-full flex items-center justify-center text-primary text-xl font-bold">
            {(profile?.firstName || authUser?.firstName || '?').charAt(0).toUpperCase()}
          </div>
          <div>
            <h2 className="text-lg font-semibold text-gray-800">{[profile?.firstName || authUser?.firstName, profile?.lastName || authUser?.lastName].filter(Boolean).join(' ')}</h2>
            <p className="text-sm text-gray-500">{profile?.email || authUser?.email}</p>
            <Badge variant={(profile?.role === 'ADMIN' || profile?.role === 'SYSTEM_ADMIN') ? 'primary' : 'gray'} className="mt-1">
              {profile?.role || authUser?.role}
            </Badge>
          </div>
        </div>
      </Card>

      {/* Personal & Business Information */}
      <Card>
        <CardHeader title="Profile Information" description="Update your personal and business details" />
        <div className="space-y-4">
          {/* Personal Information */}
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="First Name"
              value={firstName}
              onChange={(e) => {
                setFirstName(e.target.value);
                setProfileErrors((prev) => ({ ...prev, firstName: '' }));
              }}
              error={profileErrors.firstName}
              required
            />
            <Input
              label="Last Name"
              value={lastName}
              onChange={(e) => {
                setLastName(e.target.value);
                setProfileErrors((prev) => ({ ...prev, lastName: '' }));
              }}
              error={profileErrors.lastName}
              required
            />
          </div>
          <Input
            label="Email"
            type="email"
            value={profile?.email || authUser?.email || ''}
            disabled
            hint="Email cannot be changed"
          />
          <Input
            label="Phone"
            type="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            placeholder="e.g., +65 9123 4567"
          />
          {(profile?.role === 'LEW' || authUser?.role === 'LEW') && (
            <>
              <Input
                label="LEW Licence Number"
                value={lewLicenceNo}
                onChange={(e) => setLewLicenceNo(e.target.value)}
                maxLength={50}
                placeholder="e.g., LEW-2026-XXXXX"
                hint="Your EMA-issued LEW licence number"
              />
              <Select
                label="LEW Grade"
                value={lewGrade}
                onChange={(e) => setLewGrade(e.target.value)}
                options={[{ value: '', label: 'Select grade' }, ...LEW_GRADE_SELECT_OPTIONS]}
                hint="Grade on your EMA LEW licence"
              />
              <PaynowField
                type={paynowType}
                value={paynowValue}
                onTypeChange={setPaynowType}
                onValueChange={setPaynowValue}
                error={profileErrors.paynowValue}
                hint="The account where you receive payments from the platform"
              />
            </>
          )}

          {/* Business Information (Phase 1 B-2: 수집 목적 고지) — APPLICANT 전용 */}
          {isApplicant && (<>
          <div className="border-t border-gray-100 pt-4 mt-2">
            <div className="flex items-center gap-2 mb-1">
              <h3 className="text-sm font-semibold text-gray-700">Business Information</h3>
              <Badge variant="gray">Optional</Badge>
            </div>
            <p className="text-xs text-gray-500 mb-4">
              Used only for Letter of Appointment (LOA) and EMA licence printing.
            </p>
          </div>
          <Input
            label="Company Name"
            value={companyName}
            onChange={(e) => setCompanyName(e.target.value)}
            maxLength={100}
            placeholder="e.g., LICENSEKAKI PTE LTD"
            hint="This name will be printed on your installation licence"
          />
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Input
              label="UEN (Unique Entity Number)"
              value={uen}
              onChange={(e) => setUen(e.target.value)}
              maxLength={20}
              placeholder="e.g., 202407291M"
              hint="Singapore business registration number"
            />
            <Input
              label="Designation"
              value={designation}
              onChange={(e) => setDesignation(e.target.value)}
              maxLength={50}
              placeholder="e.g., Director, Manager"
              hint="Your position / title"
            />
          </div>
          <Input
            label="Correspondence Address"
            value={correspondenceAddress}
            onChange={(e) => setCorrespondenceAddress(e.target.value)}
            maxLength={255}
            placeholder="e.g., 105 Sims Ave, #07-08, Chancerlodge Complex"
            hint="EMA will send notifications to this address"
          />
          <Input
            label="Correspondence Postal Code"
            value={correspondencePostalCode}
            onChange={(e) => setCorrespondencePostalCode(e.target.value)}
            maxLength={10}
            placeholder="e.g., 387429"
          />
          </>)}
          <div className="pt-2">
            <Button onClick={handleProfileSave} loading={profileSaving}>
              Save Changes
            </Button>
          </div>
        </div>
      </Card>

      {/* Change password */}
      <Card>
        <CardHeader title="Change Password" description="Update your account password" />
        <div className="space-y-4">
          <Input
            label="Current Password"
            type="password"
            value={currentPassword}
            onChange={(e) => {
              setCurrentPassword(e.target.value);
              setPasswordErrors((prev) => ({ ...prev, currentPassword: '' }));
            }}
            placeholder="Enter current password"
            error={passwordErrors.currentPassword}
            required
          />
          <Input
            label="New Password"
            type="password"
            value={newPassword}
            onChange={(e) => {
              setNewPassword(e.target.value);
              setPasswordErrors((prev) => ({ ...prev, newPassword: '' }));
            }}
            placeholder="Enter new password (8-20 characters)"
            error={passwordErrors.newPassword}
            required
            hint="Must be 8-20 characters"
          />
          <Input
            label="Confirm New Password"
            type="password"
            value={confirmPassword}
            onChange={(e) => {
              setConfirmPassword(e.target.value);
              setPasswordErrors((prev) => ({ ...prev, confirmPassword: '' }));
            }}
            placeholder="Re-enter new password"
            error={passwordErrors.confirmPassword}
            required
          />
          <div className="pt-2">
            <Button onClick={handlePasswordChange} loading={passwordSaving}>
              Update Password
            </Button>
          </div>
        </div>
      </Card>

      {/* Account info */}
      <Card>
        <CardHeader title="Account Details" description="Read-only account information" />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
          <div>
            <span className="text-xs text-gray-500 block">Account Created</span>
            <span className="font-medium text-gray-700">
              {profile?.createdAt
                ? new Date(profile.createdAt).toLocaleDateString()
                : '-'}
            </span>
          </div>
          <div>
            <span className="text-xs text-gray-500 block">Last Updated</span>
            <span className="font-medium text-gray-700">
              {profile?.updatedAt
                ? new Date(profile.updatedAt).toLocaleDateString()
                : '-'}
            </span>
          </div>
        </div>
      </Card>

      {/* PDPA: Data Management — APPLICANT 전용 (고객 셀프서비스. 운영 계정은 admin 이 관리) */}
      {isApplicant && (<>
      <Card>
        <CardHeader title="Data Management" description="Your data rights under PDPA (Personal Data Protection Act)" />
        <div className="space-y-4">
          <div className="bg-blue-50 rounded-lg p-4">
            <h4 className="text-sm font-medium text-blue-800 mb-1">Export My Data</h4>
            <p className="text-xs text-blue-600 mb-3">
              Download all your personal data including profile information, application history,
              and chat messages in JSON format.
            </p>
            <Button variant="outline" onClick={handleExportData} loading={exporting}>
              <span className="flex items-center gap-1.5">
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
                </svg>
                Export My Data
              </span>
            </Button>
          </div>

          <div className="border-t border-gray-100 pt-4">
            <h4 className="text-sm font-medium text-amber-700 mb-1">Withdraw PDPA Consent</h4>
            {profile?.pdpaConsentAt ? (
              <>
                <p className="text-xs text-gray-500 mb-1">
                  You consented on{' '}
                  <span className="font-medium">
                    {new Date(profile.pdpaConsentAt).toLocaleDateString()}
                  </span>.
                </p>
                <p className="text-xs text-gray-500 mb-3">
                  Withdrawing consent will restrict consent-based services such as the AI chatbot.
                  Your account and core services (applications, profile) will remain active.
                </p>
                <button
                  type="button"
                  onClick={() => setShowConsentWithdrawConfirm(true)}
                  disabled={withdrawingConsent}
                  className="px-4 py-2 text-sm font-medium text-amber-700 border border-amber-200 hover:bg-amber-50
                             rounded-lg transition-colors disabled:opacity-50"
                >
                  {withdrawingConsent ? 'Withdrawing...' : 'Withdraw Consent'}
                </button>
              </>
            ) : (
              <p className="text-xs text-gray-500">
                PDPA consent has been withdrawn. Consent-based services are restricted.
              </p>
            )}
          </div>

          <div className="border-t border-gray-100 pt-4">
            <h4 className="text-sm font-medium text-red-700 mb-1">Delete My Account</h4>
            <p className="text-xs text-gray-500 mb-3">
              Permanently delete your account and personal data. Your application records will be
              retained for regulatory compliance (minimum 5 years) but will be anonymized.
              This action cannot be undone.
            </p>
            <button
              type="button"
              onClick={() => setShowAccountDeleteConfirm(true)}
              disabled={deletingAccount}
              className="px-4 py-2 text-sm font-medium text-red-600 border border-red-200 hover:bg-red-50
                         rounded-lg transition-colors disabled:opacity-50"
            >
              {deletingAccount ? 'Deleting...' : 'Delete My Account'}
            </button>
          </div>
        </div>
      </Card>

      <ConfirmDialog
        isOpen={showConsentWithdrawConfirm}
        onClose={() => setShowConsentWithdrawConfirm(false)}
        onConfirm={handleWithdrawConsent}
        title="Withdraw PDPA Consent"
        message="Are you sure you want to withdraw your PDPA consent? This will restrict consent-based services such as the AI chatbot. Your account and core services will remain active."
        confirmLabel="Withdraw Consent"
        variant="danger"
      />

      <ConfirmDialog
        isOpen={showAccountDeleteConfirm}
        onClose={() => setShowAccountDeleteConfirm(false)}
        onConfirm={handleDeleteAccount}
        title="Delete Account"
        message="Are you sure you want to permanently delete your account? Your personal data will be anonymized and your login will be disabled. Application records will be retained for regulatory compliance. This action cannot be undone."
        confirmLabel="Delete My Account"
        variant="danger"
      />
      </>)}
    </div>
  );
}
