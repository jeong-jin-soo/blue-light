import { useEffect, useState } from 'react';
import { Card } from '../../components/ui/Card';
import { Input } from '../../components/ui/Input';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { DataTable, type Column } from '../../components/data/DataTable';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../components/ui/Modal';
import { useToastStore } from '../../stores/toastStore';
import adminApi from '../../api/adminApi';
import type { AdminPriceResponse, UpdatePriceRequest } from '../../types';

export default function AdminPriceManagementPage() {
  const toast = useToastStore();
  const [prices, setPrices] = useState<AdminPriceResponse[]>([]);
  const [loading, setLoading] = useState(true);

  // Edit modal state
  const [editingPrice, setEditingPrice] = useState<AdminPriceResponse | null>(null);
  const [editForm, setEditForm] = useState<UpdatePriceRequest>({
    price: 0,
    description: '',
    kvaMin: 0,
    kvaMax: 0,
    isActive: true,
  });
  const [saving, setSaving] = useState(false);

  // Service fee state
  const [serviceFee, setServiceFee] = useState('');
  const [originalServiceFee, setOriginalServiceFee] = useState('');
  const [savingFee, setSavingFee] = useState(false);

  const loadPrices = () => {
    setLoading(true);
    adminApi
      .getPrices()
      .then(setPrices)
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load prices');
      })
      .finally(() => setLoading(false));
  };

  const loadSettings = () => {
    adminApi
      .getSettings()
      .then((settings) => {
        const fee = settings['service_fee'] || '0';
        setServiceFee(fee);
        setOriginalServiceFee(fee);
      })
      .catch((err: { message?: string }) => {
        toast.error(err.message || 'Failed to load settings');
      });
  };

  useEffect(() => {
    loadPrices();
    loadSettings();
  }, []);

  const openEditModal = (price: AdminPriceResponse) => {
    setEditingPrice(price);
    setEditForm({
      price: price.price,
      description: price.description || '',
      kvaMin: price.kvaMin,
      kvaMax: price.kvaMax,
      isActive: price.isActive,
    });
  };

  const handleSavePrice = async () => {
    if (!editingPrice) return;

    // Validation
    if (editForm.price < 0) {
      toast.error('Price must be non-negative');
      return;
    }
    if (editForm.kvaMin && editForm.kvaMax && editForm.kvaMin > editForm.kvaMax) {
      toast.error('kVA min cannot be greater than kVA max');
      return;
    }

    setSaving(true);
    try {
      await adminApi.updatePrice(editingPrice.masterPriceSeq, editForm);
      toast.success('Price tier updated successfully');
      setEditingPrice(null);
      loadPrices();
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to update price';
      toast.error(message);
    } finally {
      setSaving(false);
    }
  };

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

  const formatCurrency = (amount: number) => {
    return new Intl.NumberFormat('en-SG', {
      style: 'currency',
      currency: 'SGD',
    }).format(amount);
  };

  const columns: Column<AdminPriceResponse>[] = [
    {
      key: 'masterPriceSeq',
      header: '#',
      width: '50px',
      render: (price) => (
        <span className="font-mono text-xs text-gray-500">#{price.masterPriceSeq}</span>
      ),
    },
    {
      key: 'description',
      header: 'Description',
      render: (price) => (
        <span className="text-gray-800 font-medium">{price.description || '-'}</span>
      ),
    },
    {
      key: 'kvaMin',
      header: 'kVA Range',
      render: (price) => (
        <span className="font-mono text-sm text-gray-700">
          {price.kvaMin.toLocaleString()} – {price.kvaMax.toLocaleString()} kVA
        </span>
      ),
    },
    {
      key: 'price',
      header: 'Price (SGD)',
      render: (price) => (
        <span className="font-semibold text-gray-800">{formatCurrency(price.price)}</span>
      ),
    },
    {
      key: 'isActive' as keyof AdminPriceResponse,
      header: 'Status',
      render: (price) => (
        <Badge variant={price.isActive ? 'success' : 'gray'}>
          {price.isActive ? 'Active' : 'Inactive'}
        </Badge>
      ),
    },
    {
      key: 'updatedAt',
      header: 'Updated',
      render: (price) => (
        <span className="text-gray-500 text-xs">
          {price.updatedAt ? new Date(price.updatedAt).toLocaleDateString() : '-'}
        </span>
      ),
    },
    {
      key: 'actions' as keyof AdminPriceResponse,
      header: '',
      width: '80px',
      render: (price) => (
        <Button variant="outline" size="sm" onClick={() => openEditModal(price)}>
          Edit
        </Button>
      ),
    },
  ];

  const serviceFeeChanged = serviceFee !== originalServiceFee;

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">Price Management</h1>
        <p className="text-sm text-gray-500 mt-1">
          Manage kVA price tiers and service fee
        </p>
      </div>

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

      {/* Price Tiers Table */}
      <DataTable
        columns={columns}
        data={prices}
        loading={loading}
        keyExtractor={(price) => price.masterPriceSeq}
        emptyIcon="💰"
        emptyTitle="No price tiers found"
        emptyDescription="Price tiers will be listed here."
        mobileCardRender={(price) => (
          <div className="p-4 space-y-2">
            <div className="flex items-center justify-between">
              <span className="font-medium text-gray-800">{price.description || `Tier #${price.masterPriceSeq}`}</span>
              <Badge variant={price.isActive ? 'success' : 'gray'}>
                {price.isActive ? 'Active' : 'Inactive'}
              </Badge>
            </div>
            <div className="text-sm text-gray-600">
              <span className="font-mono">{price.kvaMin.toLocaleString()} – {price.kvaMax.toLocaleString()} kVA</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="font-semibold text-lg text-gray-800">{formatCurrency(price.price)}</span>
              <Button variant="outline" size="sm" onClick={() => openEditModal(price)}>
                Edit
              </Button>
            </div>
          </div>
        )}
      />

      {/* Summary */}
      {!loading && prices.length > 0 && (
        <div className="flex items-center justify-between text-sm text-gray-500 px-1">
          <span>{prices.length} price tiers</span>
          <div className="flex gap-4">
            <span>Active: {prices.filter((p) => p.isActive).length}</span>
            <span>Inactive: {prices.filter((p) => !p.isActive).length}</span>
          </div>
        </div>
      )}

      {/* Edit Price Modal */}
      <Modal isOpen={!!editingPrice} onClose={() => setEditingPrice(null)} size="md">
        <ModalHeader title="Edit Price Tier" onClose={() => setEditingPrice(null)} />
        <ModalBody>
          <div className="space-y-4">
            <Input
              label="Description"
              value={editForm.description || ''}
              onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
              placeholder="e.g., Up to 45 kVA"
            />

            <div className="grid grid-cols-2 gap-4">
              <Input
                label="kVA Min"
                type="number"
                min="1"
                value={editForm.kvaMin?.toString() || ''}
                onChange={(e) =>
                  setEditForm({ ...editForm, kvaMin: parseInt(e.target.value) || 0 })
                }
              />
              <Input
                label="kVA Max"
                type="number"
                min="1"
                value={editForm.kvaMax?.toString() || ''}
                onChange={(e) =>
                  setEditForm({ ...editForm, kvaMax: parseInt(e.target.value) || 0 })
                }
              />
            </div>

            <Input
              label="Price (SGD)"
              type="number"
              min="0"
              step="0.01"
              value={editForm.price?.toString() || ''}
              onChange={(e) =>
                setEditForm({ ...editForm, price: parseFloat(e.target.value) || 0 })
              }
              required
            />

            <div className="flex items-center gap-3">
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={editForm.isActive ?? true}
                  onChange={(e) => setEditForm({ ...editForm, isActive: e.target.checked })}
                  className="sr-only peer"
                />
                <div className="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-primary/30 rounded-full peer peer-checked:after:translate-x-full rtl:peer-checked:after:-translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:start-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-primary" />
              </label>
              <span className="text-sm text-gray-700">
                {editForm.isActive ? 'Active' : 'Inactive'}
              </span>
            </div>

            {!editForm.isActive && (
              <p className="text-xs text-warning-600 bg-warning-50 p-2 rounded">
                Inactive price tiers will not appear in the applicant's kVA selection.
              </p>
            )}
          </div>
        </ModalBody>
        <ModalFooter>
          <Button variant="outline" onClick={() => setEditingPrice(null)}>
            Cancel
          </Button>
          <Button onClick={handleSavePrice} loading={saving}>
            Save Changes
          </Button>
        </ModalFooter>
      </Modal>
    </div>
  );
}
