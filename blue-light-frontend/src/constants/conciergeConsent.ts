/**
 * Concierge 신청 폼 5종 동의 항목 상수.
 * - 필수 4종: PDPA / Terms / Signup / Delegation
 * - 선택 1종: Marketing
 * - TermsVersion은 백엔드 TermsVersion.CURRENT와 동기화 유지.
 */

import type { ConsentItem } from '../components/consent/ConsentChecklist';

export const CONCIERGE_CONSENT_ITEMS: ConsentItem[] = [
  {
    key: 'pdpa',
    required: true,
    label:
      'I consent to LicenseKaki collecting, using, and disclosing my personal data as described in the PDPA Notice.',
    termsUrl: '/terms/pdpa',
  },
  {
    key: 'terms',
    required: true,
    label: 'I agree to the LicenseKaki Terms of Service.',
    termsUrl: '/terms/service',
  },
  {
    key: 'signup',
    required: true,
    label:
      'I consent to the automatic creation of a LicenseKaki account with my email.',
  },
  {
    key: 'delegation',
    required: true,
    label:
      "I authorise LicenseKaki's Concierge team to manage the licensing process on my behalf, except for actions that require my personal signature.",
  },
  {
    key: 'marketing',
    required: false,
    label: 'I agree to receive marketing emails from LicenseKaki.',
    helpText: 'Optional — you can unsubscribe anytime.',
  },
];

/** 백엔드 `TermsVersion.CURRENT` 와 동기화 (2026-04-19). */
export const CONCIERGE_TERMS_VERSION = '2026-04-19';
