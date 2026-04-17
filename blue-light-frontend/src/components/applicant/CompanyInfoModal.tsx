import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../ui/Modal';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { isValidSingaporeUen } from '../../utils/validation';
import type { CompanyInfo } from '../../types';

interface CompanyInfoModalProps {
  isOpen: boolean;
  submitting?: boolean;
  submitError?: string | null;
  onConfirm: (info: CompanyInfo) => void;
  onCancel: () => void;
}

/**
 * Phase 2 PR#3 — 법인 신청 JIT 회사정보 모달.
 *
 * 블로커 반영:
 * - B-1: helper text "Used only for Letter of Authorisation..." + Privacy Policy 링크
 * - B-2: persistToProfile 체크박스 + "If unchecked..." 설명
 * - UEN 클라이언트 검증 (싱가포르 형식)
 */
export function CompanyInfoModal({
  isOpen,
  submitting = false,
  submitError = null,
  onConfirm,
  onCancel,
}: CompanyInfoModalProps) {
  const [companyName, setCompanyName] = useState('');
  const [uen, setUen] = useState('');
  const [designation, setDesignation] = useState('');
  const [persistToProfile, setPersistToProfile] = useState(true);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const validate = (): boolean => {
    const next: Record<string, string> = {};
    if (!companyName.trim()) next.companyName = 'Company name is required';
    if (!uen.trim()) {
      next.uen = 'UEN is required';
    } else if (!isValidSingaporeUen(uen)) {
      next.uen = 'Invalid UEN format (e.g. 201812345A)';
    }
    if (!designation.trim()) next.designation = 'Designation is required';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const handleConfirm = () => {
    if (submitting) return;
    if (!validate()) return;
    onConfirm({
      companyName: companyName.trim(),
      uen: uen.trim().toUpperCase(),
      designation: designation.trim(),
      persistToProfile,
    });
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={submitting ? () => { /* 제출 중 ESC/overlay 무시 */ } : onCancel}
      size="md"
      closeOnEscape={!submitting}
      closeOnOverlay={!submitting}
      ariaLabelledBy="co-modal-title"
    >
      <ModalHeader onClose={submitting ? undefined : onCancel}>
        <div className="flex items-center gap-2">
          <span className="text-xl" aria-hidden>🏢</span>
          <h3 id="co-modal-title" className="text-lg font-semibold text-gray-800">
            Company details needed
          </h3>
        </div>
      </ModalHeader>

      <ModalBody>
        <p className="text-sm text-gray-600 mb-4">
          We need a few company details to submit your corporate application.
        </p>

        {/* B-1: 수집 목적 고지 (PDPA §20) */}
        <div className="bg-info-50 border border-info-200 text-info-800 rounded-md p-3 text-xs mb-4">
          Used only for Letter of Authorisation and EMA licence printing.{' '}
          <Link
            to="/privacy"
            target="_blank"
            rel="noopener noreferrer"
            className="underline font-medium"
          >
            See Privacy Policy
          </Link>
          .
        </div>

        <div className="space-y-4">
          <Input
            name="companyName"
            label="Company Name"
            value={companyName}
            onChange={(e) => {
              setCompanyName(e.target.value);
              if (errors.companyName) setErrors((p) => ({ ...p, companyName: '' }));
            }}
            error={errors.companyName}
            required
            autoFocus
            disabled={submitting}
            placeholder="e.g. Acme Pte Ltd"
            maxLength={100}
          />

          <Input
            name="uen"
            label="UEN (Unique Entity Number)"
            value={uen}
            onChange={(e) => {
              setUen(e.target.value);
              if (errors.uen) setErrors((p) => ({ ...p, uen: '' }));
            }}
            error={errors.uen}
            required
            disabled={submitting}
            placeholder="e.g. 201812345A"
            hint="Format: 9–10 chars, e.g. 201812345A"
            maxLength={20}
          />

          <Input
            name="designation"
            label="Your Designation"
            value={designation}
            onChange={(e) => {
              setDesignation(e.target.value);
              if (errors.designation) setErrors((p) => ({ ...p, designation: '' }));
            }}
            error={errors.designation}
            required
            disabled={submitting}
            placeholder="e.g. Director"
            maxLength={50}
          />

          {/* B-2: persistToProfile — 기본 true + 미체크 시 동작 명시 */}
          <label className="flex items-start gap-2 text-sm text-gray-700 bg-gray-50 rounded-md p-3 cursor-pointer">
            <input
              type="checkbox"
              checked={persistToProfile}
              onChange={(e) => setPersistToProfile(e.target.checked)}
              disabled={submitting}
              className="mt-0.5 accent-primary"
            />
            <span>
              <span className="font-medium">Save to my profile</span>
              <span className="block text-xs text-gray-500 mt-0.5">
                Auto-fills your next application.
              </span>
              <span className="block text-xs text-gray-500 mt-1">
                If unchecked, this company info applies only to this application;
                your profile stays unchanged.
              </span>
            </span>
          </label>
        </div>

        {submitError && (
          <div
            role="alert"
            className="mt-4 text-sm text-error-700 bg-error-50 border border-error-200 rounded-md p-3"
          >
            {submitError}
          </div>
        )}
      </ModalBody>

      <ModalFooter>
        <Button variant="outline" onClick={onCancel} disabled={submitting}>
          Cancel
        </Button>
        <Button onClick={handleConfirm} loading={submitting}>
          Save &amp; Submit
        </Button>
      </ModalFooter>
    </Modal>
  );
}

export default CompanyInfoModal;
