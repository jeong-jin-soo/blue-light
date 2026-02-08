import { useEffect, useState } from 'react';
import { useAuthStore } from '../../stores/authStore';
import { Card, CardHeader } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
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
  const [profileSaving, setProfileSaving] = useState(false);
  const [profileErrors, setProfileErrors] = useState<Record<string, string>>({});

  // Password form
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordErrors, setPasswordErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    userApi
      .getMyProfile()
      .then((data) => {
        setProfile(data);
        setName(data.name);
        setPhone(data.phone || '');
      })
      .catch(() => {
        toast.error('Failed to load profile');
      })
      .finally(() => setLoading(false));
  }, []);

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
        <h1 className="text-2xl font-bold text-gray-800">My Profile</h1>
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

      {/* Personal information */}
      <Card>
        <CardHeader title="Personal Information" description="Update your name and contact details" />
        <div className="space-y-4">
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
    </div>
  );
}
