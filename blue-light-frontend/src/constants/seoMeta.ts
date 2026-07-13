/**
 * 공개 페이지 SEO 메타 (단일 정의원).
 * 런타임: useDocumentMeta 훅이 소비. 빌드: prerender.tsx 가 초기 HTML <head> 에 주입.
 */
export interface RouteMeta {
  path: string;
  title: string;
  description: string;
}

export const SEO_META = {
  home: {
    path: '/',
    title: 'Electrical Installation Licence in Singapore, Made Simple | LicenseKaki',
    description:
      'LicenseKaki helps businesses and homeowners in Singapore apply for, renew and manage electrical installation licences — new licences, renewals, Single Line Diagrams (SLD), LEW services and more. Chat with us on WhatsApp.',
  },
  services: {
    path: '/services',
    title: 'Our Services — Electrical Licensing, SLD & LEW in Singapore | LicenseKaki',
    description:
      'New and renewal electrical installation licences, Single Line Diagrams (SLD), lighting and power layout plans, LEW services, and help for expired licences in Singapore.',
  },
  about: {
    path: '/about',
    title: 'About LicenseKaki — Singapore Electrical Licensing Specialists',
    description:
      'LicenseKaki, by HanVision Holdings, is a Singapore-owned team of experienced professionals and Licensed Electrical Workers (LEWs) helping you navigate electrical installation licensing with confidence.',
  },
} satisfies Record<string, RouteMeta>;

/**
 * 서비스 상세(/services/:slug) 별 SEO 제목·H1·설명 (키워드 최적화).
 * UI 라벨(publicServices)과 분리 — 검색어(영국식 "Licence" + EMA/EI/SI/LEW/SLD)를 겨냥한다.
 * 비과장 원칙: EMA 승인 보장·EMA 사칭 문구 금지 — 신청 대행 서비스 설명까지만.
 */
export interface ServiceSeo {
  title: string;
  h1: string;
  description: string;
}

export const SERVICE_SEO: Record<string, ServiceSeo> = {
  'new-license': {
    title: 'Electrical Installation Licence (EI/SI) Application in Singapore | LicenseKaki',
    h1: 'New Electrical Installation Licence (EI / SI)',
    description:
      'Apply for a new EMA electrical installation licence in Singapore — Electrical Installation (EI) or Supply Installation (SI). We handle the full application and coordinate with your Licensed Electrical Worker (LEW).',
  },
  'renewal-license': {
    title: 'Electrical Installation Licence Renewal in Singapore | LicenseKaki',
    h1: 'Electrical Installation Licence Renewal',
    description:
      'Renew your EMA electrical installation licence in Singapore on time. We track your renewal date, prepare the documents and liaise with EMA so your installation stays compliant.',
  },
  sld: {
    title: 'Single Line Diagram (SLD) for EMA Submission in Singapore | LicenseKaki',
    h1: 'Single Line Diagram (SLD) for EMA',
    description:
      'Professionally drafted and LEW-endorsed Single Line Diagrams (SLD) for your EMA electrical installation licence application in Singapore.',
  },
  'lighting-layout-plan': {
    title: 'Electrical Lighting Layout Plan in Singapore | LicenseKaki',
    h1: 'Electrical Lighting Layout Plan',
    description:
      'Clear electrical lighting layout plans showing every fixture, switch and circuit across your space — for fit-outs, renovations and new installations in Singapore.',
  },
  'power-layout-plan': {
    title: 'Electrical Power Layout Plan in Singapore | LicenseKaki',
    h1: 'Electrical Power Layout Plan',
    description:
      "Detailed electrical power layout plans covering your installation's circuits and load distribution in Singapore — suitable for all project types.",
  },
  'expired-license': {
    title: 'Expired Electrical Licence — Recovery & Reinstatement in Singapore | LicenseKaki',
    h1: 'Expired Electrical Installation Licence Recovery',
    description:
      'Electrical installation licence lapsed? We help you get compliant again and reinstate your EI/SI licence with EMA in Singapore.',
  },
  'lew-services': {
    title: 'Licensed Electrical Worker (LEW) Services in Singapore | LicenseKaki',
    h1: 'Licensed Electrical Worker (LEW) Services',
    description:
      'Get matched with a Licensed Electrical Worker (LEW) in Singapore for testing, inspection and certification — so your installation is signed off correctly.',
  },
};

export const SITE_ORIGIN = 'https://licensekaki.com';
