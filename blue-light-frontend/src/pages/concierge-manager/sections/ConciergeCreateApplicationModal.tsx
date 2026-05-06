/**
 * ConciergeCreateApplicationModal
 * - Kaki Concierge v1.5 Phase 1 PR#5 Stage B
 * - CONTACTING 상태의 Concierge 요청에서 Manager가 대리 Application을 생성하는 모달.
 * - Phase 1 MVP: NEW/RENEWAL 선택 가능. RENEWAL의 original licence 필드는 Phase 2로 연기.
 */

import { useState } from 'react';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../../components/ui/Modal';
import { Button } from '../../../components/ui/Button';
import { Input } from '../../../components/ui/Input';
import { Select } from '../../../components/ui/Select';
import conciergeManagerApi, {
  type CreateApplicationPayload,
  type CreateOnBehalfResponse,
} from '../../../api/conciergeManagerApi';
import type { ApiError } from '../../../types';

interface Props {
  conciergeRequestSeq: number;
  submitterName: string;
  isOpen: boolean;
  onClose: () => void;
  onCreated: (resp: CreateOnBehalfResponse) => void;
}

/** axiosClient interceptor가 정규화한 에러(spread된 객체) + AxiosError 양쪽 호환 */
interface NormalizedHttpError {
  response?: { status?: number; data?: ApiError };
  code?: string;
  message?: string;
}

const BUILDING_TYPE_OPTIONS = [
  { value: 'RESIDENTIAL', label: 'Residential' },
  { value: 'COMMERCIAL', label: 'Commercial' },
  { value: 'INDUSTRIAL', label: 'Industrial' },
  { value: 'OTHER', label: 'Other' },
];

// master_prices가 55 kVA부터 시작 (45는 UNKNOWN placeholder 전용).
// 실제 신청 가능한 kVA tier만 노출.
const KVA_OPTIONS = [55, 63, 80, 100, 150, 200, 300, 400, 500].map((k) => ({
  value: String(k),
  label: `${k} kVA`,
}));

const APPLICANT_TYPE_OPTIONS = [
  { value: 'INDIVIDUAL', label: 'Individual' },
  { value: 'CORPORATE', label: 'Corporate' },
];

const APPLICATION_TYPE_OPTIONS = [
  { value: 'NEW', label: 'New licence' },
  { value: 'RENEWAL', label: 'Renewal' },
];

const SLD_OPTION_OPTIONS = [
  { value: 'SELF_UPLOAD', label: 'Self upload' },
  { value: 'REQUEST_LEW', label: 'Request LEW' },
];

export function ConciergeCreateApplicationModal({
  conciergeRequestSeq,
  submitterName,
  isOpen,
  onClose,
  onCreated,
}: Props) {
  const [address, setAddress] = useState('');
  const [postalCode, setPostalCode] = useState('');
  const [buildingType, setBuildingType] = useState('COMMERCIAL');
  const [selectedKva, setSelectedKva] = useState<number>(63);
  const [applicantType, setApplicantType] = useState<'INDIVIDUAL' | 'CORPORATE'>('INDIVIDUAL');
  const [applicationType, setApplicationType] = useState<'NEW' | 'RENEWAL'>('NEW');
  const [spAccountNo, setSpAccountNo] = useState('');
  const [sldOption, setSldOption] = useState<'SELF_UPLOAD' | 'REQUEST_LEW'>('SELF_UPLOAD');

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit =
    address.trim().length > 0 &&
    postalCode.trim().length > 0 &&
    selectedKva > 0 &&
    !submitting;

  const handleSubmit = async () => {
    setError(null);
    setSubmitting(true);
    try {
      const payload: CreateApplicationPayload = {
        address: address.trim(),
        postalCode: postalCode.trim(),
        buildingType,
        selectedKva,
        applicantType,
        applicationType,
        spAccountNo: spAccountNo.trim() || undefined,
        sldOption,
      };
      const resp = await conciergeManagerApi.createApplicationOnBehalf(
        conciergeRequestSeq,
        payload
      );
      onCreated(resp);
      handleClose();
    } catch (err) {
      const e = err as NormalizedHttpError;
      const code = e.code ?? e.response?.data?.code;
      const data = e.response?.data;
      let msg = 'Failed to create application';

      if (code === 'INVALID_STATE_FOR_APPLICATION') {
        msg =
          data?.message ??
          'This concierge request is not in the right state for application creation.';
      } else if (code === 'CONCIERGE_NOT_ASSIGNED') {
        msg = 'This concierge request is not assigned to you.';
      } else if (code === 'RENEWAL_REF_REQUIRED' || code === 'INVALID_RENEWAL_PERIOD') {
        msg =
          'Renewal applications require additional fields. Please use "New licence" for now, or have the applicant complete the renewal directly.';
      } else if (data?.message) {
        msg = data.message;
      } else if (e.message) {
        msg = e.message;
      }
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const handleClose = () => {
    if (submitting) return;
    // 폼 초기화 없이 닫기만 (재열면 입력 유지됨). 재사용성과 단순함 중 후자 선택: 초기화.
    setAddress('');
    setPostalCode('');
    setBuildingType('COMMERCIAL');
    setSelectedKva(45);
    setApplicantType('INDIVIDUAL');
    setApplicationType('NEW');
    setSpAccountNo('');
    setSldOption('SELF_UPLOAD');
    setError(null);
    onClose();
  };

  return (
    <Modal isOpen={isOpen} onClose={handleClose} size="lg">
      <ModalHeader
        title={`Create application on behalf of ${submitterName}`}
        onClose={handleClose}
      />
      <ModalBody>
        <p className="text-xs text-gray-500 mb-4">
          Create a licence application on behalf of the applicant using details from your
          consultation. Additional fields can be updated later by the applicant.
        </p>

        <div className="space-y-4">
          <Input
            label="Installation address"
            name="address"
            required
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            maxLength={255}
            disabled={submitting}
            autoComplete="off"
          />

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <Input
              label="Postal code"
              name="postalCode"
              required
              value={postalCode}
              onChange={(e) => setPostalCode(e.target.value)}
              maxLength={10}
              disabled={submitting}
            />
            <Select
              label="Building type"
              name="buildingType"
              value={buildingType}
              onChange={(e) => setBuildingType(e.target.value)}
              options={BUILDING_TYPE_OPTIONS}
              disabled={submitting}
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <Select
              label="kVA"
              name="selectedKva"
              required
              value={String(selectedKva)}
              onChange={(e) => setSelectedKva(Number(e.target.value))}
              options={KVA_OPTIONS}
              disabled={submitting}
            />
            <Select
              label="Applicant type"
              name="applicantType"
              required
              value={applicantType}
              onChange={(e) =>
                setApplicantType(e.target.value as 'INDIVIDUAL' | 'CORPORATE')
              }
              options={APPLICANT_TYPE_OPTIONS}
              disabled={submitting}
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <Select
              label="Application type"
              name="applicationType"
              value={applicationType}
              onChange={(e) =>
                setApplicationType(e.target.value as 'NEW' | 'RENEWAL')
              }
              options={APPLICATION_TYPE_OPTIONS}
              disabled={submitting}
              hint={
                applicationType === 'RENEWAL'
                  ? 'Renewal requires the original licence number — the applicant may need to complete this directly.'
                  : undefined
              }
            />
            <Select
              label="SLD option"
              name="sldOption"
              value={sldOption}
              onChange={(e) =>
                setSldOption(e.target.value as 'SELF_UPLOAD' | 'REQUEST_LEW')
              }
              options={SLD_OPTION_OPTIONS}
              disabled={submitting}
            />
          </div>

          <Input
            label="SP account (optional)"
            name="spAccountNo"
            value={spAccountNo}
            onChange={(e) => setSpAccountNo(e.target.value)}
            maxLength={30}
            disabled={submitting}
            autoComplete="off"
          />

          {error && (
            <div
              role="alert"
              className="p-3 rounded-md bg-error-50 border border-error-200 text-sm text-error-700"
            >
              {error}
            </div>
          )}
        </div>
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" size="sm" onClick={handleClose} disabled={submitting}>
          Cancel
        </Button>
        <Button
          variant="concierge"
          size="sm"
          onClick={handleSubmit}
          disabled={!canSubmit}
          loading={submitting}
        >
          Create application
        </Button>
      </ModalFooter>
    </Modal>
  );
}

export default ConciergeCreateApplicationModal;
