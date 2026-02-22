import { useRef } from 'react';
import { fullName } from '../../../utils/formatName';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../../components/ui/Modal';
import { ConfirmDialog } from '../../../components/ui/ConfirmDialog';
import { Button } from '../../../components/ui/Button';
import { Input } from '../../../components/ui/Input';
import { Badge } from '../../../components/ui/Badge';
import { Textarea } from '../../../components/ui/Textarea';
import { LoadingSpinner } from '../../../components/ui/LoadingSpinner';
import type { LewSummary } from '../../../types';

// ── Payment Confirmation Modal ──────────────────────────
interface PaymentModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  quoteAmount: number;
  paymentForm: { transactionId: string; paymentMethod: string; receiptFile: File | null };
  setPaymentForm: React.Dispatch<React.SetStateAction<{ transactionId: string; paymentMethod: string; receiptFile: File | null }>>;
  loading: boolean;
}

export function PaymentModal({ isOpen, onClose, onConfirm, quoteAmount, paymentForm, setPaymentForm, loading }: PaymentModalProps) {
  const receiptInputRef = useRef<HTMLInputElement>(null);

  const handleReceiptChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] || null;
    setPaymentForm((prev) => ({ ...prev, receiptFile: file }));
  };

  const removeReceipt = () => {
    setPaymentForm((prev) => ({ ...prev, receiptFile: null }));
    if (receiptInputRef.current) receiptInputRef.current.value = '';
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="sm">
      <ModalHeader title="Confirm Payment" onClose={onClose} />
      <ModalBody>
        <div className="space-y-4">
          <p className="text-sm text-gray-600">
            Confirm that payment of{' '}
            <span className="font-semibold">SGD ${quoteAmount.toLocaleString()}</span>{' '}
            has been received for this application.
          </p>
          <Input
            label="Transaction ID"
            placeholder="e.g., TXN-20250101-001"
            value={paymentForm.transactionId}
            onChange={(e) => setPaymentForm((prev) => ({ ...prev, transactionId: e.target.value }))}
            hint="Optional - enter if available"
          />
          <Input
            label="Payment Method"
            placeholder="e.g., PayNow"
            value={paymentForm.paymentMethod}
            onChange={(e) => setPaymentForm((prev) => ({ ...prev, paymentMethod: e.target.value }))}
          />

          {/* Receipt Upload */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Payment Receipt</label>
            {paymentForm.receiptFile ? (
              <div className="flex items-center gap-2 p-2.5 bg-green-50 border border-green-200 rounded-lg">
                <svg className="w-4 h-4 text-green-600 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
                <span className="text-xs text-green-800 truncate flex-1">{paymentForm.receiptFile.name}</span>
                <button
                  type="button"
                  onClick={removeReceipt}
                  className="text-green-600 hover:text-red-500 transition-colors"
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => receiptInputRef.current?.click()}
                className="w-full border-2 border-dashed border-gray-300 rounded-lg p-3 text-center hover:border-primary-400 hover:bg-primary-50/50 transition-colors"
              >
                <p className="text-xs text-gray-500">Click to attach receipt (PDF, image)</p>
              </button>
            )}
            <input
              ref={receiptInputRef}
              type="file"
              accept="image/*,.pdf"
              className="hidden"
              onChange={handleReceiptChange}
            />
            <p className="text-xs text-gray-400 mt-1">Optional — upload a receipt to share with the applicant</p>
          </div>
        </div>
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" onClick={onConfirm} loading={loading}>Confirm Payment</Button>
      </ModalFooter>
    </Modal>
  );
}

// ── Complete Application Modal ──────────────────────────
interface CompleteModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  completeForm: { licenseNumber: string; licenseExpiryDate: string };
  setCompleteForm: React.Dispatch<React.SetStateAction<{ licenseNumber: string; licenseExpiryDate: string }>>;
  loading: boolean;
}

export function CompleteModal({ isOpen, onClose, onConfirm, completeForm, setCompleteForm, loading }: CompleteModalProps) {
  return (
    <Modal isOpen={isOpen} onClose={onClose} size="sm">
      <ModalHeader title="Complete & Issue Licence" onClose={onClose} />
      <ModalBody>
        <div className="space-y-4">
          <p className="text-sm text-gray-600">Issue the electrical installation licence for this application.</p>
          <Input
            label="Licence Number"
            placeholder="e.g., EIL-2025-00001"
            value={completeForm.licenseNumber}
            onChange={(e) => setCompleteForm((prev) => ({ ...prev, licenseNumber: e.target.value }))}
            required
          />
          <Input
            label="Expiry Date"
            type="date"
            value={completeForm.licenseExpiryDate}
            onChange={(e) => setCompleteForm((prev) => ({ ...prev, licenseExpiryDate: e.target.value }))}
            required
          />
        </div>
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" onClick={onConfirm} loading={loading}>Issue Licence</Button>
      </ModalFooter>
    </Modal>
  );
}

// ── Revision Request Modal ──────────────────────────
interface RevisionModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  revisionComment: string;
  setRevisionComment: (v: string) => void;
  loading: boolean;
}

export function RevisionModal({ isOpen, onClose, onConfirm, revisionComment, setRevisionComment, loading }: RevisionModalProps) {
  return (
    <Modal isOpen={isOpen} onClose={onClose} size="sm">
      <ModalHeader title="Request Revision" onClose={onClose} />
      <ModalBody>
        <div className="space-y-4">
          <p className="text-sm text-gray-600">Enter a comment describing what the applicant needs to revise.</p>
          <Textarea
            label="Review Comment"
            required
            rows={4}
            maxLength={2000}
            value={revisionComment}
            onChange={(e) => setRevisionComment(e.target.value)}
            placeholder="e.g., Please provide the correct postal code and update the building type."
            hint={`${revisionComment.length}/2000`}
            className="resize-none"
          />
        </div>
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" onClick={onConfirm} loading={loading} disabled={!revisionComment.trim()}>Request Revision</Button>
      </ModalFooter>
    </Modal>
  );
}

// ── Assign LEW Modal ──────────────────────────
interface AssignLewModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  lewsLoading: boolean;
  availableLews: LewSummary[];
  selectedLewSeq: number | null;
  setSelectedLewSeq: (seq: number | null) => void;
  applicationKva?: number;
  loading: boolean;
}

export function AssignLewModal({
  isOpen, onClose, onConfirm, lewsLoading, availableLews, selectedLewSeq, setSelectedLewSeq, applicationKva, loading,
}: AssignLewModalProps) {
  return (
    <Modal isOpen={isOpen} onClose={onClose} size="sm">
      <ModalHeader title="Assign LEW" onClose={onClose} />
      <ModalBody>
        {lewsLoading ? (
          <div className="flex items-center justify-center py-8">
            <LoadingSpinner size="md" label="Loading LEWs..." />
          </div>
        ) : availableLews.length === 0 ? (
          <div className="text-center py-6">
            <p className="text-sm text-gray-500">
              No eligible LEWs available for this application ({applicationKva} kVA).
            </p>
            <p className="text-xs text-gray-400 mt-1">
              All approved LEWs either lack a grade or have insufficient capacity.
            </p>
          </div>
        ) : (
          <div className="space-y-2">
            <div className="bg-blue-50 rounded-lg p-2.5 border border-blue-100 mb-3">
              <p className="text-xs text-blue-700">
                Showing LEWs eligible for <span className="font-semibold">{applicationKva} kVA</span> capacity.
              </p>
            </div>
            <p className="text-sm text-gray-600 mb-3">Select a LEW to assign to this application:</p>
            {availableLews.map((lew) => (
              <button
                key={lew.userSeq}
                type="button"
                onClick={() => setSelectedLewSeq(lew.userSeq)}
                className={`w-full flex items-center gap-3 p-3 rounded-lg border-2 transition-all text-left ${
                  selectedLewSeq === lew.userSeq
                    ? 'border-primary bg-primary/5'
                    : 'border-gray-200 hover:border-gray-300'
                }`}
              >
                <div className="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center flex-shrink-0">
                  <span className="text-sm">⚡</span>
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-gray-800">{fullName(lew.firstName, lew.lastName)}</p>
                  <p className="text-xs text-gray-500 truncate">{lew.email}</p>
                  <div className="flex items-center gap-2 mt-0.5">
                    {lew.lewLicenceNo && (
                      <span className="text-xs text-primary-600 font-mono">{lew.lewLicenceNo}</span>
                    )}
                    {lew.lewGrade && (
                      <Badge variant="info" className="text-[10px]">
                        {lew.lewGrade.replace('GRADE_', 'G')} (≤{lew.maxKva === 9999 ? '400kV' : `${lew.maxKva}kVA`})
                      </Badge>
                    )}
                  </div>
                </div>
                {selectedLewSeq === lew.userSeq && (
                  <span className="text-primary text-lg flex-shrink-0">✓</span>
                )}
              </button>
            ))}
          </div>
        )}
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" onClick={onConfirm} loading={loading} disabled={!selectedLewSeq || lewsLoading}>Assign</Button>
      </ModalFooter>
    </Modal>
  );
}

// ── Re-export ConfirmDialog wrappers ──────────────────────────
interface ConfirmProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  loading?: boolean;
}

export function ApproveConfirmDialog({ isOpen, onClose, onConfirm, loading }: ConfirmProps) {
  return (
    <ConfirmDialog
      isOpen={isOpen}
      onClose={onClose}
      onConfirm={onConfirm}
      title="Approve Application"
      message="Approve this application and request payment from the applicant? The status will change to PENDING_PAYMENT."
      confirmLabel="Approve"
      loading={loading}
    />
  );
}

export function ProcessingConfirmDialog({ isOpen, onClose, onConfirm }: ConfirmProps) {
  return (
    <ConfirmDialog
      isOpen={isOpen}
      onClose={onClose}
      onConfirm={onConfirm}
      title="Start Processing"
      message="Start processing this application? Status will change to IN_PROGRESS."
      confirmLabel="Start Processing"
    />
  );
}

export function UnassignLewConfirmDialog({ isOpen, onClose, onConfirm, loading }: ConfirmProps) {
  return (
    <ConfirmDialog
      isOpen={isOpen}
      onClose={onClose}
      onConfirm={onConfirm}
      title="Remove LEW Assignment"
      message="Remove the assigned LEW from this application? The application will become unassigned."
      confirmLabel="Remove"
      loading={loading}
    />
  );
}

export function SldConfirmDialog({ isOpen, onClose, onConfirm, loading }: ConfirmProps) {
  return (
    <ConfirmDialog
      isOpen={isOpen}
      onClose={onClose}
      onConfirm={onConfirm}
      title="Confirm SLD"
      message="Confirm that the uploaded SLD drawing is complete and acceptable? This action cannot be undone."
      confirmLabel="Confirm"
      loading={loading}
    />
  );
}
