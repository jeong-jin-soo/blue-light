import type { AdminApplication, ApplicationStatus } from '../types';

/**
 * LEW 진입 페이지(/lew/applications/:id)의 1차 CTA·헤더 부제를 status + Phase 1 가드에서 파생.
 *
 * Phase 모델 (사용자 결정, sg-lew-expert 검증):
 *   Phase 1 (PENDING_REVIEW / REVISION_REQUESTED): 신청 검토 + 서류 보강 + kVA 확정
 *   Phase Gate                                   : LEW가 결제 요청 → ADMIN 입금 확인 → LEW 알림
 *   Phase 2 (PAID / IN_PROGRESS)                 : SLD / LOA 발행
 *
 * PR3 + 결제 게이트 완화(2026-06-18): Phase 1 가드(`kvaConfirmed`)를 충족하면
 * CTA가 "Start review" → "Request payment"로 전환된다. SLD·LoA 는 결제 전제가 아니라 병렬/결제후 작업이라 제외.
 * 백엔드 LewReviewService.requestPayment 가드(kVA 확정 + 서류 0건)와 일치.
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
  /** Application.kvaStatus === 'CONFIRMED' 여부. (결제 요청 전제 — 2026-06-18 기준 유일 가드) */
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
      // PR3 + 결제 게이트 완화(2026-06-18): kVA 확정만으로 "Request payment"로 전환.
      // kVA 확정 = 필요 정보 수취 완료 신호 → 문서·LoA·SLD 는 결제 전제가 아니라 병렬/결제후 작업.
      // 가드 정보가 없으면 fallback 으로 startReview.
      const phase1Done = guards != null && guards.kvaConfirmed;
      if (phase1Done) {
        return {
          kind: 'requestPayment',
          label: 'Request payment',
          description:
            'kVA is confirmed. Notify the applicant to pay the licence fee. Document collection and the LoA exchange run in parallel; work begins once both payment and the final LoA are in.',
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
          'Admin will confirm payment shortly. You will be notified when the application is ready for SLD and LOA.',
        targetUrl: null,
        disabled: true,
      };
    case 'PAID':
    case 'IN_PROGRESS':
      return {
        kind: 'continueCertification',
        label: 'Continue certification',
        description:
          'Payment confirmed. Complete SLD and LOA to issue the licence.',
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
      return 'Payment confirmed — proceed with SLD and LOA.';
    case 'IN_PROGRESS':
      return 'Continue certification work.';
    case 'COMPLETED':
      return 'Application completed.';
    case 'EXPIRED':
      return 'Application expired.';
  }
}
