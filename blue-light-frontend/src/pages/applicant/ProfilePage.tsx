import { useEffect, useState, useMemo, useRef } from 'react';
import { useAuthStore } from '../../stores/authStore';
import { Card, CardHeader } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import SignaturePad, { type SignaturePadHandle } from '../../components/domain/SignaturePad';
import { useToastStore } from '../../stores/toastStore';
import { useFormGuard } from '../../hooks/useFormGuard';
import userApi from '../../api/userApi';
import type { User } from '../../types';

export default function ProfilePage() {
  const { user: authUser } = useAuthStore();
  const toast = useToastStore();

  const [profile, setProfile] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  // Profile form
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [lewLicenceNo, setLewLicenceNo] = useState('');
  const [lewGrade, setLewGrade] = useState('');
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

  // Signature management
  const signatureRef = useRef<SignaturePadHandle>(null);
  const [signatureDataUrl, setSignatureDataUrl] = useState<string | null>(null);
  const [signatureSaving, setSignatureSaving] = useState(false);
  const [showSignaturePad, setShowSignaturePad] = useState(false);
  const [hasNewSignature, setHasNewSignature] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  useEffect(() => {
    userApi
      .getMyProfile()
      .then((data) => {
        setProfile(data);
        setName(data.name);
        setPhone(data.phone || '');
        setLewLicenceNo(data.lewLicenceNo || '');
        setLewGrade(data.lewGrade || '');
        setCompanyName(data.companyName || '');
        setUen(data.uen || '');
        setDesignation(data.designation || '');
        setCorrespondenceAddress(data.correspondenceAddress || '');
        setCorrespondencePostalCode(data.correspondencePostalCode || '');
        // 서명 이미지 로드
        if (data.hasSignature) {
          userApi.getSignatureDataUrl().then(setSignatureDataUrl);
        }
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
      name !== profile.name ||
      phone !== (profile.phone || '') ||
      companyName !== (profile.companyName || '') ||
      uen !== (profile.uen || '') ||
      designation !== (profile.designation || '') ||
      correspondenceAddress !== (profile.correspondenceAddress || '') ||
      correspondencePostalCode !== (profile.correspondencePostalCode || '')
    );
  }, [profile, name, phone, companyName, uen, designation, correspondenceAddress, correspondencePostalCode]);
  useFormGuard(isProfileDirty);

  const handleProfileSave = async () => {
    const errors: Record<string, string> = {};
    if (!name.trim()) errors.name = 'Name is required';
    setProfileErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setProfileSaving(true);
    try {
      const updated = await userApi.updateProfile({
        name: name.trim(),
        phone: phone.trim() || undefined,
        lewLicenceNo: lewLicenceNo.trim() || undefined,
        lewGrade: lewGrade || undefined,
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

  // ── Signature handlers ──

  const handleSaveSignature = async () => {
    if (!signatureRef.current || signatureRef.current.isEmpty()) {
      toast.error('Please draw your signature first');
      return;
    }

    setSignatureSaving(true);
    try {
      const blob = await signatureRef.current.toBlob();
      if (!blob) {
        toast.error('Failed to capture signature');
        return;
      }
      const updated = await userApi.uploadSignature(blob);
      setProfile(updated);
      // 새 data URL 로드
      const dataUrl = await userApi.getSignatureDataUrl();
      setSignatureDataUrl(dataUrl);
      setShowSignaturePad(false);
      setHasNewSignature(false);
      toast.success('Signature saved successfully');
    } catch {
      toast.error('Failed to save signature');
    } finally {
      setSignatureSaving(false);
    }
  };

  const handleDeleteSignature = async () => {
    setShowDeleteConfirm(false);
    try {
      await userApi.deleteSignature();
      setProfile((prev) => prev ? { ...prev, hasSignature: false } : prev);
      setSignatureDataUrl(null);
      setShowSignaturePad(false);
      toast.success('Signature deleted');
    } catch {
      toast.error('Failed to delete signature');
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading profile..." />
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-2xl">
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">My Profile</h1>
        <p className="text-sm text-gray-500 mt-1">Manage your account information</p>
      </div>

      {/* Account summary */}
      <Card>
        <div className="flex items-center gap-4">
          <div className="w-14 h-14 bg-primary-100 rounded-full flex items-center justify-center text-primary text-xl font-bold">
            {(profile?.name || authUser?.name || '?').charAt(0).toUpperCase()}
          </div>
          <div>
            <h2 className="text-lg font-semibold text-gray-800">{profile?.name || authUser?.name}</h2>
            <p className="text-sm text-gray-500">{profile?.email || authUser?.email}</p>
            <Badge variant={profile?.role === 'ADMIN' ? 'primary' : 'gray'} className="mt-1">
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
          <Input
            label="Full Name"
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              setProfileErrors((prev) => ({ ...prev, name: '' }));
            }}
            error={profileErrors.name}
            required
          />
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
                options={[
                  { value: '', label: 'Select grade' },
                  { value: 'GRADE_7', label: 'Grade 7 (≤ 45 kVA)' },
                  { value: 'GRADE_8', label: 'Grade 8 (≤ 500 kVA)' },
                  { value: 'GRADE_9', label: 'Grade 9 (≤ 400 kV)' },
                ]}
                hint="Grade on your EMA LEW licence"
              />
            </>
          )}

          {/* Business Information */}
          <div className="border-t border-gray-100 pt-4 mt-2">
            <h3 className="text-sm font-semibold text-gray-700 mb-1">Business Information</h3>
            <p className="text-xs text-gray-500 mb-4">Company details required for EMA licence application (Letter of Appointment)</p>
          </div>
          <Input
            label="Company Name"
            value={companyName}
            onChange={(e) => setCompanyName(e.target.value)}
            maxLength={100}
            placeholder="e.g., BLUE LIGHT PTE LTD"
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
          <div className="pt-2">
            <Button onClick={handleProfileSave} loading={profileSaving}>
              Save Changes
            </Button>
          </div>
        </div>
      </Card>

      {/* ───── Signature Management ───── */}
      <Card>
        <CardHeader title="My Signature" description="Your saved signature will be pre-loaded when signing LOA documents" />
        <div className="space-y-4">
          {/* 서명이 있고, 편집 모드가 아닐 때 → 미리보기 */}
          {profile?.hasSignature && signatureDataUrl && !showSignaturePad && (
            <div className="space-y-3">
              <div className="border-2 border-gray-200 rounded-lg overflow-hidden bg-white p-2" style={{ height: '150px' }}>
                <img
                  src={signatureDataUrl}
                  alt="Saved signature"
                  className="w-full h-full object-contain"
                />
              </div>
              <div className="flex gap-3">
                <Button
                  variant="outline"
                  onClick={() => { setShowSignaturePad(true); setHasNewSignature(false); }}
                >
                  Update Signature
                </Button>
                <button
                  type="button"
                  onClick={() => setShowDeleteConfirm(true)}
                  className="px-4 py-2 text-sm font-medium text-red-600 hover:text-red-700 hover:bg-red-50 rounded-lg transition-colors"
                >
                  Delete
                </button>
              </div>
            </div>
          )}

          {/* 서명이 없거나, 편집 모드일 때 → SignaturePad */}
          {(!profile?.hasSignature || showSignaturePad) && (
            <div className="space-y-3">
              {showSignaturePad && profile?.hasSignature && (
                <p className="text-xs text-gray-500">
                  Draw a new signature below to replace your current one.
                </p>
              )}
              {!profile?.hasSignature && !showSignaturePad && (
                <p className="text-xs text-gray-500">
                  No signature saved yet. Draw your signature below to save it for future use.
                </p>
              )}
              <SignaturePad
                ref={signatureRef}
                onSignatureChange={setHasNewSignature}
                disabled={signatureSaving}
              />
              <div className="flex gap-3">
                <Button
                  onClick={handleSaveSignature}
                  loading={signatureSaving}
                  disabled={!hasNewSignature}
                >
                  {profile?.hasSignature ? 'Replace Signature' : 'Save Signature'}
                </Button>
                {showSignaturePad && profile?.hasSignature && (
                  <Button variant="outline" onClick={() => { setShowSignaturePad(false); setHasNewSignature(false); }}>
                    Cancel
                  </Button>
                )}
              </div>
            </div>
          )}
        </div>
      </Card>

      <ConfirmDialog
        isOpen={showDeleteConfirm}
        onClose={() => setShowDeleteConfirm(false)}
        onConfirm={handleDeleteSignature}
        title="Delete Signature"
        message="Are you sure you want to delete your saved signature? You will need to draw a new one when signing LOA documents."
        confirmLabel="Delete"
        variant="danger"
      />

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
    </div>
  );
}
