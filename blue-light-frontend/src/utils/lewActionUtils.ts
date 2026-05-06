import type { AdminApplication, ApplicationStatus } from '../types';

/**
 * LEW 진입 페이지(/lew/applications/:id)의 1차 CTA·헤더 부제를 status + Phase 1 가드에서 파생.
 *
 * Phase 모델 (사용자 결정, sg-lew-expert 검증):
 *   Phase 1 (PENDING_REVIEW / REVISION_REQUESTED): 신청 검토 + 서류 보강 + kVA 확정
 *   Phase Gate                                   : LEW가 결제 요청 → ADMIN 입금 확인 → LEW 알림
 *   Phase 2 (PAID / IN_PROGRESS)                 : SLD / LOA / CoF 발행
 *
 * PR3 변경: Phase 1 가드(`pendingDocCount===0 && kvaConfirmed`)를 충족하면 CTA가
 * "Start review" → "Request payment"로 전환된다. SLD 가드는 결제 후 수행되므로 제외.
 */

export type LewPrimaryActionKind =
  | 'startReview'
  | 'requestPayment'
  | 'awaitingPayment'
  | 'continueCertification'
  | 'completed'
  | 'expired';

export type LewPrimaryAction = {
  kind: LewPrimaryActionKind;
  label: string;
  description: string;
  /** null이면 비활성 CTA(클릭 불가) 또는 in-page 액션(navigate 대신 onClick 핸들러 사용). */
  targetUrl: string | null;
  /** true면 비활성 표시. */
  disabled: boolean;
};

/**
 * PR3 추가: Phase 1 종료 가드 정보.
 * 호출자가 사전에 fetch하여 전달 (DocumentRequest, kvaStatus, sldOption/sldStatus).
 */
export type LewPrimaryActionGuards = {
  /** REQUESTED/UPLOADED 상태인 DocumentRequest 개수. 0이면 미해결 없음. */
  pendingDocCount: number;
  /** Application.kvaStatus === 'CONFIRMED' 여부. */
  kvaConfirmed: boolean;
  /** sldOption === 'REQUEST_LEW' 인지 여부 (Phase 2에서만 의미 있음). */
  sldRequired?: boolean;
  /** SLD 가 CONFIRMED 또는 sldRequired=false 일 때 true. */
  sldReady?: boolean;
};

export function deriveLewPrimaryAction(
  application: AdminApplication,
  guards?: LewPrimaryActionGuards,
): LewPrimaryAction {
  const reviewUrl = `/lew/applications/${application.applicationSeq}/review`;

  switch (application.status) {
    case 'PENDING_REVIEW':
    case 'REVISION_REQUESTED': {
      // PR3: Phase 1 종료 시 (서류 0건 + kVA 확정) "Request payment"로 전환.
      // 가드 정보가 없으면 (호환성 fallback) 기존 startReview 유지.
      const phase1Done =
        guards != null && guards.pendingDocCount === 0 && guards.kvaConfirmed;
      if (phase1Done) {
        return {
          kind: 'requestPayment',
          label: 'Request payment',
          description:
            'Phase 1 review is complete. Notify the applicant to pay the licence fee. SLD, LOA, and Certificate of Fitness will be completed after payment.',
          // request-payment는 in-page 액션(POST + 페이지 새로고침). navigate 대신 onClick 핸들러로 처리.
          targetUrl: null,
          disabled: false,
        };
      }
      return {
        kind: 'startReview',
        label: 'Start review',
        description:
          'Review the applicant submission, request any missing documents, and confirm the kVA capacity.',
        targetUrl: reviewUrl,
        disabled: false,
      };
    }
    case 'PENDING_PAYMENT':
      return {
        kind: 'awaitingPayment',
        label: 'Awaiting payment',
        description:
          'Admin will confirm payment shortly. You will be notified when the application is ready for SLD, LOA, and Certificate of Fitness.',
        targetUrl: null,
        disabled: true,
      };
    case 'PAID':
    case 'IN_PROGRESS':
      return {
        kind: 'continueCertification',
        label: 'Continue certification',
        description:
          'Payment confirmed. Complete SLD, LOA, and the Certificate of Fitness to issue the licence.',
        targetUrl: reviewUrl,
        disabled: false,
      };
    case 'COMPLETED':
      return {
        kind: 'completed',
        label: 'Application completed',
        description: 'The licence has been issued. This page is read-only.',
        targetUrl: reviewUrl,
        disabled: true,
      };
    case 'EXPIRED':
      return {
        kind: 'expired',
        label: 'Application expired',
        description: 'No further action is available for expired applications.',
        targetUrl: null,
        disabled: true,
      };
  }
}

export function deriveLewHeaderSubtitle(status: ApplicationStatus): string {
  switch (status) {
    case 'PENDING_REVIEW':
      return 'Verify documents and confirm kVA before requesting payment.';
    case 'REVISION_REQUESTED':
      return 'Continue review after the applicant resubmits.';
    case 'PENDING_PAYMENT':
      return 'Awaiting payment confirmation by admin.';
    case 'PAID':
      return 'Payment confirmed — proceed with SLD, LOA, and Certificate of Fitness.';
    case 'IN_PROGRESS':
      return 'Continue certification work.';
    case 'COMPLETED':
      return 'Application completed.';
    case 'EXPIRED':
      return 'Application expired.';
  }
}
