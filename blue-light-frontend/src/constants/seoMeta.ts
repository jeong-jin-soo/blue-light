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

export const SITE_ORIGIN = 'https://licensekaki.com';
