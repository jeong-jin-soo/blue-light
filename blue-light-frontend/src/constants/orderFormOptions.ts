/**
 * 주문/신청 폼에서 공용으로 쓰는 드롭다운 옵션과 상수.
 * NewApplicationPage와 New{Sld,Lighting,PowerSocket,LewService}OrderPage가
 * 동일한 Building Type과 kVA 선택 UI를 공유한다.
 */

export const BUILDING_TYPES = [
  { value: '', label: 'Select building type' },
  { value: 'Residential', label: 'Residential' },
  { value: 'Commercial', label: 'Commercial' },
  { value: 'Industrial', label: 'Industrial' },
  { value: 'Hotel', label: 'Hotel' },
  { value: 'Healthcare', label: 'Healthcare' },
  { value: 'Education', label: 'Education' },
  { value: 'Government', label: 'Government' },
  { value: 'Mixed Use', label: 'Mixed Use' },
  { value: 'Other', label: 'Other' },
];

/**
 * Phase 5 — kVA "I don't know" 센티넬. 서버는 45로 치환/확정한다.
 */
export const KVA_UNKNOWN_SENTINEL = '__UNKNOWN__';

/**
 * kvaUnknown 선택 시 서버에 전달할 placeholder 값.
 */
export const KVA_UNKNOWN_PLACEHOLDER = 45;
