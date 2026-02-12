import { useEffect, useState, useCallback } from 'react';
import { Card } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { useToastStore } from '../../stores/toastStore';
import adminApi from '../../api/adminApi';
import type { AdminPriceResponse, BatchUpdatePricesRequest } from '../../types';

// ── Editable Tier 타입 ──────────────────────────────

interface TierErrors {
  description?: string;
  kvaMin?: string;
  kvaMax?: string;
  price?: string;
}

interface EditableTier {
  tempId: string;
  masterPriceSeq: number | null;
  description: string;
  kvaMin: string;
  kvaMax: string;
  price: string;
  isActive: boolean;
  errors: TierErrors;
}

function toEditableTier(price: AdminPriceResponse): EditableTier {
  return {
    tempId: crypto.randomUUID(),
    masterPriceSeq: price.masterPriceSeq,
    description: price.description || '',
    kvaMin: String(price.kvaMin),
    kvaMax: String(price.kvaMax),
    price: String(price.price),
    isActive: price.isActive,
    errors: {},
  };
}

function createEmptyTier(): EditableTier {
  return {
    tempId: crypto.randomUUID(),
    masterPriceSeq: null,
    description: '',
    kvaMin: '',
    kvaMax: '',
    price: '',
    isActive: true,
    errors: {},
  };
}

// ── 메인 컴포넌트 ──────────────────────────────

export default function AdminPriceManagementPage() {
  const toast = useToastStore();
  const [loading, setLoading] = useState(true);

  // 가격 티어 상태
  const [editableTiers, setEditableTiers] = useState<EditableTier[]>([]);
  const [originalPrices, setOriginalPrices] = useState<AdminPriceResponse[]>([]);
  const [batchSaving, setBatchSaving] = useState(false);
  const [crossTierErrors, setCrossTierErrors] = useState<string[]>([]);
  const [deleteConfirmTier, setDeleteConfirmTier] = useState<EditableTier | null>(null);

  // Service fee state
  const [serviceFee, setServiceFee] = useState('');
  const [originalServiceFee, setOriginalServiceFee] = useState('');
  const [savingFee, setSavingFee] = useState(false);

  // Email verification toggle state
  const [emailVerificationEnabled, setEmailVerificationEnabled] = useState(false);
  const [originalEmailVerification, setOriginalEmailVerification] = useState(false);
  const [savingEmailVerification, setSavingEmailVerification] = useState(false);

  // Payment info state
  const [paymentPaynowUen, setPaymentPaynowUen] = useState('');
  const [paymentPaynowName, setPaymentPaynowName] = useState('');
  const [paymentBankName, setPaymentBankName] = useState('');
  const [paymentBankAccount, setPaymentBankAccount] = useState('');
  const [paymentBankAccountName, setPaymentBankAccountName] = useState('');
  const [originalPaymentInfo, setOriginalPaymentInfo] = useState<Record<string, string>>({});
  const [savingPayment, setSavingPayment] = useState(false);

  // ── 데이터 로드 ──────────────────────────────

  const initializeTiers = useCallback((prices: AdminPriceResponse[]) => {
    setOriginalPrices(prices);
    setEditableTiers(prices.map(toEditableTier));
    setCrossTierErrors([]);
  }, []);

  const loadPrices = useCallback(() => {
    setLoading(true);
    adminApi
      .getPrices()
      .then((prices) => {
        initializeTiers(prices);
      })
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load prices');
      })
      .finally(() => setLoading(false));
  }, [initializeTiers, toast]);

  const loadSettings = useCallback(() => {
    adminApi
      .getSettings()
      .then((settings) => {
        const fee = settings['service_fee'] || '0';
        setServiceFee(fee);
        setOriginalServiceFee(fee);

        const emailVerif = settings['email_verification_enabled'] === 'true';
        setEmailVerificationEnabled(emailVerif);
        setOriginalEmailVerification(emailVerif);

        const pInfo: Record<string, string> = {
          payment_paynow_uen: settings['payment_paynow_uen'] || '',
          payment_paynow_name: settings['payment_paynow_name'] || '',
          payment_bank_name: settings['payment_bank_name'] || '',
          payment_bank_account: settings['payment_bank_account'] || '',
          payment_bank_account_name: settings['payment_bank_account_name'] || '',
        };
        setPaymentPaynowUen(pInfo.payment_paynow_uen);
        setPaymentPaynowName(pInfo.payment_paynow_name);
        setPaymentBankName(pInfo.payment_bank_name);
        setPaymentBankAccount(pInfo.payment_bank_account);
        setPaymentBankAccountName(pInfo.payment_bank_account_name);
        setOriginalPaymentInfo(pInfo);
      })
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load settings');
      });
  }, [toast]);

  useEffect(() => {
    loadPrices();
    loadSettings();
  }, []);

  // ── 가격 티어 인라인 편집 핸들러 ──────────────────────────────

  const updateTier = (tempId: string, field: keyof EditableTier, value: string | boolean) => {
    setEditableTiers((prev) =>
      prev.map((t) =>
        t.tempId === tempId ? { ...t, [field]: value, errors: { ...t.errors, [field]: undefined } } : t
      )
    );
  };

  const handleAddTier = () => {
    setEditableTiers((prev) => [...prev, createEmptyTier()]);
  };

  const handleDeleteTier = (tier: EditableTier) => {
    if (tier.masterPriceSeq !== null) {
      // 기존 티어: 확인 다이얼로그
      setDeleteConfirmTier(tier);
    } else {
      // 신규 티어: 즉시 제거
      setEditableTiers((prev) => prev.filter((t) => t.tempId !== tier.tempId));
    }
  };

  const confirmDeleteTier = () => {
    if (!deleteConfirmTier) return;
    setEditableTiers((prev) => prev.filter((t) => t.tempId !== deleteConfirmTier.tempId));
    setDeleteConfirmTier(null);
  };

  const handleDiscardChanges = () => {
    initializeTiers(originalPrices);
  };

  // ── 변경 감지 ──────────────────────────────

  const hasUnsavedPriceChanges = (() => {
    if (editableTiers.length !== originalPrices.length) return true;
    return editableTiers.some((tier, idx) => {
      const orig = originalPrices[idx];
      if (!orig) return true;
      return (
        tier.masterPriceSeq !== orig.masterPriceSeq ||
        tier.description !== (orig.description || '') ||
        tier.kvaMin !== String(orig.kvaMin) ||
        tier.kvaMax !== String(orig.kvaMax) ||
        tier.price !== String(orig.price) ||
        tier.isActive !== orig.isActive
      );
    });
  })();

  // ── 클라이언트 사이드 검증 ──────────────────────────────

  const validateTiers = (): boolean => {
    let isValid = true;
    const newTiers = editableTiers.map((t) => ({ ...t, errors: {} as TierErrors }));
    const newCrossErrors: string[] = [];

    // 개별 검증
    newTiers.forEach((tier) => {
      const kvaMin = parseInt(tier.kvaMin);
      const kvaMax = parseInt(tier.kvaMax);
      const price = parseFloat(tier.price);

      if (!tier.kvaMin || isNaN(kvaMin) || kvaMin < 1) {
        tier.errors.kvaMin = 'Required (min: 1)';
        isValid = false;
      }
      if (!tier.kvaMax || isNaN(kvaMax) || kvaMax < 1) {
        tier.errors.kvaMax = 'Required (min: 1)';
        isValid = false;
      }
      if (!isNaN(kvaMin) && !isNaN(kvaMax) && kvaMin > kvaMax) {
        tier.errors.kvaMax = 'Must be >= kVA Min';
        isValid = false;
      }
      if (tier.price === '' || isNaN(price) || price < 0) {
        tier.errors.price = 'Required (min: 0)';
        isValid = false;
      }
    });

    // 교차 검증 (kvaMin 정렬)
    const validTiers = newTiers
      .filter((t) => !isNaN(parseInt(t.kvaMin)) && !isNaN(parseInt(t.kvaMax)))
      .sort((a, b) => parseInt(a.kvaMin) - parseInt(b.kvaMin));

    for (let i = 0; i < validTiers.length - 1; i++) {
      const curr = validTiers[i];
      const next = validTiers[i + 1];
      const currMax = parseInt(curr.kvaMax);
      const nextMin = parseInt(next.kvaMin);
      const currDesc = curr.description || `${curr.kvaMin}-${curr.kvaMax} kVA`;
      const nextDesc = next.description || `${next.kvaMin}-${next.kvaMax} kVA`;

      if (currMax >= nextMin) {
        newCrossErrors.push(
          `Overlap: "${currDesc}" (max: ${currMax}) overlaps with "${nextDesc}" (min: ${nextMin})`
        );
        isValid = false;
      } else if (currMax + 1 !== nextMin) {
        newCrossErrors.push(
          `Gap: "${currDesc}" (max: ${currMax}) and "${nextDesc}" (min: ${nextMin}) — expected min to be ${currMax + 1}`
        );
        isValid = false;
      }
    }

    setEditableTiers(newTiers);
    setCrossTierErrors(newCrossErrors);
    return isValid;
  };

  // ── 일괄 저장 ──────────────────────────────

  const handleBatchSave = async () => {
    if (!validateTiers()) return;

    setBatchSaving(true);
    try {
      const request: BatchUpdatePricesRequest = {
        tiers: editableTiers.map((t) => ({
          masterPriceSeq: t.masterPriceSeq,
          description: t.description,
          kvaMin: parseInt(t.kvaMin),
          kvaMax: parseInt(t.kvaMax),
          price: parseFloat(t.price),
          isActive: t.isActive,
        })),
      };
      const updated = await adminApi.batchUpdatePrices(request);
      initializeTiers(updated);
      toast.success('Price tiers saved successfully');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to save price tiers';
      toast.error(message);
    } finally {
      setBatchSaving(false);
    }
  };

  // ── Settings 핸들러 ──────────────────────────────

  const handleSaveServiceFee = async () => {
    const feeValue = parseFloat(serviceFee);
    if (isNaN(feeValue) || feeValue < 0) {
      toast.error('Service fee must be a non-negative number');
      return;
    }

    setSavingFee(true);
    try {
      await adminApi.updateSettings({ service_fee: serviceFee });
      setOriginalServiceFee(serviceFee);
      toast.success('Service fee updated successfully');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to update service fee';
      toast.error(message);
    } finally {
      setSavingFee(false);
    }
  };

  const handleSaveEmailVerification = async () => {
    setSavingEmailVerification(true);
    try {
      await adminApi.updateSettings({
        email_verification_enabled: emailVerificationEnabled ? 'true' : 'false',
      });
      setOriginalEmailVerification(emailVerificationEnabled);
      toast.success(
        emailVerificationEnabled
          ? 'Email verification enabled. New users must verify their email.'
          : 'Email verification disabled. New users are auto-verified.'
      );
    } catch (err: unknown) {
      const message =
        (err as { message?: string })?.message || 'Failed to update email verification setting';
      toast.error(message);
    } finally {
      setSavingEmailVerification(false);
    }
  };

  const emailVerificationChanged = emailVerificationEnabled !== originalEmailVerification;

  const paymentInfoChanged =
    paymentPaynowUen !== originalPaymentInfo.payment_paynow_uen ||
    paymentPaynowName !== originalPaymentInfo.payment_paynow_name ||
    paymentBankName !== originalPaymentInfo.payment_bank_name ||
    paymentBankAccount !== originalPaymentInfo.payment_bank_account ||
    paymentBankAccountName !== originalPaymentInfo.payment_bank_account_name;

  const handleSavePaymentInfo = async () => {
    setSavingPayment(true);
    try {
      const data: Record<string, string> = {
        payment_paynow_uen: paymentPaynowUen,
        payment_paynow_name: paymentPaynowName,
        payment_bank_name: paymentBankName,
        payment_bank_account: paymentBankAccount,
        payment_bank_account_name: paymentBankAccountName,
      };
      await adminApi.updateSettings(data);
      setOriginalPaymentInfo(data);
      toast.success('Payment information updated successfully');
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to update payment info';
      toast.error(message);
    } finally {
      setSavingPayment(false);
    }
  };

  const serviceFeeChanged = serviceFee !== originalServiceFee;

  // ── 렌더링 ──────────────────────────────

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">System Settings</h1>
        <p className="text-sm text-gray-500 mt-1">
          Manage email verification, pricing, service fees, and payment information
        </p>
      </div>

      {/* Email Verification Toggle Card */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-1">Email Verification</h2>
        <p className="text-xs text-gray-500 mb-4">
          When enabled, new users must verify their email address before accessing the platform.
          Disable this for local development or testing.
        </p>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <label className="relative inline-flex items-center cursor-pointer">
              <input
                type="checkbox"
                checked={emailVerificationEnabled}
                onChange={(e) => setEmailVerificationEnabled(e.target.checked)}
                className="sr-only peer"
              />
              <div className="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-primary/30 rounded-full peer peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:start-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary" />
            </label>
            <span className="text-sm text-gray-700">
              {emailVerificationEnabled ? 'Enabled' : 'Disabled'}
            </span>
          </div>
          <div className="flex items-center gap-3">
            <Button
              onClick={handleSaveEmailVerification}
              loading={savingEmailVerification}
              disabled={!emailVerificationChanged}
              size="sm"
            >
              Save
            </Button>
            {emailVerificationChanged && (
              <span className="text-xs text-warning-600">Unsaved changes</span>
            )}
          </div>
        </div>

        {!emailVerificationEnabled && (
          <p className="text-xs text-amber-600 bg-amber-50 p-2 rounded mt-3">
            Email verification is currently disabled. New users can sign up without verifying their
            email.
          </p>
        )}
      </Card>

      {/* Service Fee Card */}
      <Card>
        <div className="flex flex-col sm:flex-row sm:items-end gap-4">
          <div className="flex-1 max-w-xs">
            <Input
              label="Service Fee (SGD)"
              type="number"
              min="0"
              step="0.01"
              value={serviceFee}
              onChange={(e) => setServiceFee(e.target.value)}
              placeholder="0.00"
            />
          </div>
          <div className="flex items-center gap-3">
            <Button
              onClick={handleSaveServiceFee}
              loading={savingFee}
              disabled={!serviceFeeChanged}
              size="sm"
            >
              Save Fee
            </Button>
            {serviceFeeChanged && (
              <span className="text-xs text-warning-600">Unsaved changes</span>
            )}
          </div>
        </div>
        <p className="text-xs text-gray-500 mt-2">
          This fee is added to the kVA tier price for every application quote.
        </p>
      </Card>

      {/* Payment Information Card */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-1">Payment Information</h2>
        <p className="text-xs text-gray-500 mb-4">
          These details are displayed to applicants when making payment. Update to reflect your
          actual receiving accounts.
        </p>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* PayNow Section */}
          <div>
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <span className="w-6 h-6 bg-primary-100 rounded flex items-center justify-center text-xs font-bold text-primary-700">
                P
              </span>
              PayNow
            </h3>
            <div className="space-y-3">
              <Input
                label="UEN Number"
                value={paymentPaynowUen}
                onChange={(e) => setPaymentPaynowUen(e.target.value)}
                placeholder="e.g., 202401234A"
              />
              <Input
                label="Recipient Name"
                value={paymentPaynowName}
                onChange={(e) => setPaymentPaynowName(e.target.value)}
                placeholder="e.g., Blue Light Pte Ltd"
              />
            </div>
          </div>

          {/* Bank Transfer Section */}
          <div>
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center gap-2">
              <span className="w-6 h-6 bg-primary-100 rounded flex items-center justify-center text-xs font-bold text-primary-700">
                B
              </span>
              Bank Transfer
            </h3>
            <div className="space-y-3">
              <Input
                label="Bank Name"
                value={paymentBankName}
                onChange={(e) => setPaymentBankName(e.target.value)}
                placeholder="e.g., DBS Bank"
              />
              <Input
                label="Account Number"
                value={paymentBankAccount}
                onChange={(e) => setPaymentBankAccount(e.target.value)}
                placeholder="e.g., 012-345678-9"
              />
              <Input
                label="Account Holder Name"
                value={paymentBankAccountName}
                onChange={(e) => setPaymentBankAccountName(e.target.value)}
                placeholder="e.g., Blue Light Pte Ltd"
              />
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3 mt-4 pt-4 border-t border-gray-100">
          <Button
            onClick={handleSavePaymentInfo}
            loading={savingPayment}
            disabled={!paymentInfoChanged}
            size="sm"
          >
            Save Payment Info
          </Button>
          {paymentInfoChanged && (
            <span className="text-xs text-warning-600">Unsaved changes</span>
          )}
        </div>
      </Card>

      {/* ── Price Tiers (인라인 편집) ────────────────────── */}
      <Card>
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-4">
          <div>
            <h2 className="text-lg font-semibold text-gray-800">Price Tiers</h2>
            <p className="text-xs text-gray-500 mt-0.5">
              Manage kVA capacity-based pricing. Add, edit, or remove tiers and save all changes at once.
            </p>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            {hasUnsavedPriceChanges && (
              <span className="text-xs text-warning-600 mr-1">Unsaved changes</span>
            )}
            {hasUnsavedPriceChanges && (
              <Button variant="outline" size="sm" onClick={handleDiscardChanges}>
                Discard
              </Button>
            )}
            <Button
              size="sm"
              onClick={handleBatchSave}
              loading={batchSaving}
              disabled={!hasUnsavedPriceChanges}
            >
              Save All
            </Button>
          </div>
        </div>

        {/* 교차 검증 에러 배너 */}
        {crossTierErrors.length > 0 && (
          <div className="mb-4 p-3 bg-error-50 border border-error-200 rounded-lg">
            <p className="text-sm font-medium text-error-700 mb-1">Validation Errors</p>
            <ul className="text-xs text-error-600 space-y-0.5">
              {crossTierErrors.map((err, i) => (
                <li key={i}>• {err}</li>
              ))}
            </ul>
          </div>
        )}

        {/* 로딩 스켈레톤 */}
        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-14 bg-gray-100 rounded-lg animate-pulse" />
            ))}
          </div>
        ) : (
          <>
            {/* 데스크톱 테이블 헤더 */}
            <div className="hidden md:grid grid-cols-[1fr_90px_90px_110px_70px_44px] gap-2 px-3 py-2 text-xs font-medium text-gray-500 uppercase tracking-wider border-b border-gray-200">
              <span>Description</span>
              <span>kVA Min</span>
              <span>kVA Max</span>
              <span>Price (SGD)</span>
              <span>Active</span>
              <span />
            </div>

            {/* 가격 티어 행들 */}
            {editableTiers.length === 0 ? (
              <div className="py-12 text-center text-gray-400">
                <p className="text-3xl mb-2">💰</p>
                <p className="text-sm font-medium">No price tiers</p>
                <p className="text-xs">Click "Add Tier" to create a new price tier.</p>
              </div>
            ) : (
              <div className="divide-y divide-gray-100">
                {editableTiers.map((tier, index) => (
                  <div key={tier.tempId}>
                    {/* 데스크톱 레이아웃 */}
                    <div className="hidden md:grid grid-cols-[1fr_90px_90px_110px_70px_44px] gap-2 px-3 py-2.5 items-start">
                      <Input
                        value={tier.description}
                        onChange={(e) => updateTier(tier.tempId, 'description', e.target.value)}
                        placeholder={`Tier ${index + 1}`}
                        error={tier.errors.description}
                      />
                      <Input
                        type="number"
                        min="1"
                        value={tier.kvaMin}
                        onChange={(e) => updateTier(tier.tempId, 'kvaMin', e.target.value)}
                        placeholder="Min"
                        error={tier.errors.kvaMin}
                      />
                      <Input
                        type="number"
                        min="1"
                        value={tier.kvaMax}
                        onChange={(e) => updateTier(tier.tempId, 'kvaMax', e.target.value)}
                        placeholder="Max"
                        error={tier.errors.kvaMax}
                      />
                      <Input
                        type="number"
                        min="0"
                        step="0.01"
                        value={tier.price}
                        onChange={(e) => updateTier(tier.tempId, 'price', e.target.value)}
                        placeholder="0.00"
                        error={tier.errors.price}
                      />
                      <div className="flex items-center justify-center pt-2.5">
                        <label className="relative inline-flex items-center cursor-pointer">
                          <input
                            type="checkbox"
                            checked={tier.isActive}
                            onChange={(e) => updateTier(tier.tempId, 'isActive', e.target.checked)}
                            className="sr-only peer"
                          />
                          <div className="w-9 h-5 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-primary/30 rounded-full peer peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:start-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary" />
                        </label>
                      </div>
                      <div className="flex items-center justify-center pt-1.5">
                        <button
                          type="button"
                          onClick={() => handleDeleteTier(tier)}
                          className="p-1.5 text-gray-400 hover:text-error-500 hover:bg-error-50 rounded-md transition-colors"
                          title="Remove tier"
                        >
                          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                          </svg>
                        </button>
                      </div>
                    </div>

                    {/* 모바일 레이아웃 */}
                    <div className="md:hidden p-3 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-medium text-gray-500">
                          Tier {index + 1}
                          {tier.masterPriceSeq === null && (
                            <span className="ml-1.5 text-primary-600">(New)</span>
                          )}
                        </span>
                        <div className="flex items-center gap-2">
                          <label className="relative inline-flex items-center cursor-pointer">
                            <input
                              type="checkbox"
                              checked={tier.isActive}
                              onChange={(e) => updateTier(tier.tempId, 'isActive', e.target.checked)}
                              className="sr-only peer"
                            />
                            <div className="w-9 h-5 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-primary/30 rounded-full peer peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:start-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-primary" />
                          </label>
                          <button
                            type="button"
                            onClick={() => handleDeleteTier(tier)}
                            className="p-1.5 text-gray-400 hover:text-error-500 hover:bg-error-50 rounded-md transition-colors"
                            title="Remove tier"
                          >
                            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                            </svg>
                          </button>
                        </div>
                      </div>
                      <Input
                        label="Description"
                        value={tier.description}
                        onChange={(e) => updateTier(tier.tempId, 'description', e.target.value)}
                        placeholder={`Tier ${index + 1}`}
                        error={tier.errors.description}
                      />
                      <div className="grid grid-cols-2 gap-3">
                        <Input
                          label="kVA Min"
                          type="number"
                          min="1"
                          value={tier.kvaMin}
                          onChange={(e) => updateTier(tier.tempId, 'kvaMin', e.target.value)}
                          placeholder="Min"
                          error={tier.errors.kvaMin}
                        />
                        <Input
                          label="kVA Max"
                          type="number"
                          min="1"
                          value={tier.kvaMax}
                          onChange={(e) => updateTier(tier.tempId, 'kvaMax', e.target.value)}
                          placeholder="Max"
                          error={tier.errors.kvaMax}
                        />
                      </div>
                      <Input
                        label="Price (SGD)"
                        type="number"
                        min="0"
                        step="0.01"
                        value={tier.price}
                        onChange={(e) => updateTier(tier.tempId, 'price', e.target.value)}
                        placeholder="0.00"
                        error={tier.errors.price}
                      />
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* Add Tier 버튼 */}
            <div className="px-3 py-3 border-t border-gray-100">
              <button
                type="button"
                onClick={handleAddTier}
                className="flex items-center gap-1.5 text-sm text-primary-600 hover:text-primary-700 font-medium transition-colors"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                </svg>
                Add Tier
              </button>
            </div>

            {/* 요약 */}
            {editableTiers.length > 0 && (
              <div className="flex items-center justify-between text-xs text-gray-400 px-3 pt-2 pb-1">
                <span>{editableTiers.length} tier{editableTiers.length !== 1 ? 's' : ''}</span>
                <span>
                  Active: {editableTiers.filter((t) => t.isActive).length} / Inactive:{' '}
                  {editableTiers.filter((t) => !t.isActive).length}
                </span>
              </div>
            )}
          </>
        )}
      </Card>

      {/* 삭제 확인 다이얼로그 */}
      <ConfirmDialog
        isOpen={!!deleteConfirmTier}
        onClose={() => setDeleteConfirmTier(null)}
        onConfirm={confirmDeleteTier}
        title="Remove Price Tier"
        message={`Are you sure you want to remove "${deleteConfirmTier?.description || `Tier (${deleteConfirmTier?.kvaMin}-${deleteConfirmTier?.kvaMax} kVA)`}"? This change will take effect when you click "Save All".`}
        confirmLabel="Remove"
        variant="danger"
      />
    </div>
  );
}
