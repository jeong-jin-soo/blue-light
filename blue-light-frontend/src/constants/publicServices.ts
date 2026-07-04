import {
  FileText,
  RefreshCw,
  DraftingCompass,
  Lightbulb,
  Plug,
  History,
  type LucideIcon,
} from 'lucide-react';

// 랜딩(Page 1)·서비스 상세(Page 2) 공용 콘텐츠.
// 카피 원본: doc/Project requester/ 미팅 산출물 "LicenseKaki — Landing Page Content (Draft v1)"
// (Lighting Layout 문구는 PDF 판본 채택 — EMA 요건 충족 표현은 규제기관 오인 소지로 배제)
export interface PublicService {
  /** Page 2 앵커 (예: /services#new-license) */
  slug: string;
  icon: LucideIcon;
  label: string;
  /** Page 1 카드 설명 */
  cardDesc: string;
  /** Page 2 섹션 도입 문단 ("We help you:" / "We provide:" 로 끝남) */
  intro: string;
  bullets: string[];
  whoNeedsThis: string;
}

export const PUBLIC_SERVICES: PublicService[] = [
  {
    slug: 'new-license',
    icon: FileText,
    label: 'New License',
    cardDesc:
      'Applying for a new Electrical Installation (EI) or Supply Installation (SI) license? We handle the full EMA submission for you.',
    intro:
      'Setting up a new electrical or supply installation in Singapore requires an EMA license before it can be used. We help you:',
    bullets: [
      'Determine whether you need an EI or SI license',
      'Prepare and submit your application via ELISE',
      'Coordinate with your Licensed Electrical Worker (LEW)',
      'Track your application until approval',
    ],
    whoNeedsThis:
      'New commercial/industrial premises, non-domestic installations, or installations exceeding 45kVA.',
  },
  {
    slug: 'renewal-license',
    icon: RefreshCw,
    label: 'Renewal License',
    cardDesc:
      "Don't let your license lapse. We manage timely renewals so your installation stays compliant.",
    intro:
      'Your EMA license needs to be renewed periodically to stay valid. We help you:',
    bullets: [
      'Track your renewal due dates so you never miss one',
      'Prepare and submit renewal documents',
      'Liaise with EMA on your behalf',
    ],
    whoNeedsThis: 'Existing license holders approaching their renewal date.',
  },
  {
    slug: 'sld',
    icon: DraftingCompass,
    label: 'SLD (Single Line Diagram)',
    cardDesc:
      'Professionally drafted and endorsed Single Line Diagrams required for your EMA application.',
    intro:
      "An SLD is a simplified diagram showing your electrical system's main components and how they connect — a required document for most EMA applications. We provide:",
    bullets: [
      'Professional SLD drafting',
      'LEW endorsement',
      'Revisions to match site conditions',
    ],
    whoNeedsThis:
      'Anyone applying for a new license or making changes to an existing installation.',
  },
  {
    slug: 'lighting-layout-plan',
    icon: Lightbulb,
    label: 'Lighting Layout Plan',
    cardDesc:
      'Clear lighting layout plans showing the placement of every fixture, switch, and circuit across your space. Suitable for use across all types of projects.',
    intro:
      'A lighting layout plan shows the placement of lighting points, switches, and circuits across your premises. We provide:',
    bullets: [
      'Clear, standalone lighting layout plans tailored to your space',
      'Coordination with your overall electrical design',
    ],
    whoNeedsThis:
      'New fit-outs, renovations, or installations requiring updated lighting documentation.',
  },
  {
    slug: 'power-layout-plan',
    icon: Plug,
    label: 'Power Layout Plan',
    cardDesc:
      "Detailed power layout plans covering your installation's circuits and load distribution — suitable for all types of projects.",
    intro:
      'A power layout plan documents power points, circuits, and load distribution across your installation. We provide:',
    bullets: [
      'Accurate as-built or proposed power layouts',
      'Load calculations where required',
    ],
    whoNeedsThis:
      'New installations, renovations, or upgrades to electrical capacity.',
  },
  {
    slug: 'expired-license',
    icon: History,
    label: 'Expired License',
    cardDesc:
      'License already lapsed? We help you get compliant again and reinstated with EMA.',
    intro:
      'If your license has lapsed, your installation may no longer be compliant. We help you:',
    bullets: [
      'Assess the current status of your installation',
      'Prepare the documents needed to reinstate your license',
      'Resubmit to EMA and get you compliant again',
    ],
    whoNeedsThis:
      'Anyone whose EI/SI license has expired and needs to regularize it.',
  },
];

/** 서비스 문의용 WhatsApp 프리필 메시지 (미팅 문서 문구) */
export const whatsappServiceMessage = (serviceLabel: string): string =>
  `Hi LicenseKaki, I'd like to ask about ${serviceLabel}.`;

/** 일반 문의(서비스 미지정) 프리필 메시지 */
export const WHATSAPP_GENERIC_MESSAGE =
  "Hi LicenseKaki, I'd like to ask about your services.";
