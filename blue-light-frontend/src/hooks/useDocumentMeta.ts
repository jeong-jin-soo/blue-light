import { useEffect } from 'react';

/**
 * 페이지별 SEO 메타 설정 (무의존). 라우트 마운트 시 document.title / description /
 * canonical / OG·Twitter 를 갱신한다. Google 은 JS 렌더 후 이 값을 색인한다.
 * (소셜 크롤러용 초기 HTML 메타는 프리렌더가 담당 — index.html 은 홈 기본값)
 */
const SITE = 'https://licensekaki.com';

interface MetaOptions {
  title: string;
  description: string;
  /** canonical/og:url 용 경로. 예: '/services' (홈은 '/') */
  path: string;
}

function upsertMeta(attr: 'name' | 'property', key: string, content: string): void {
  let el = document.head.querySelector<HTMLMetaElement>(`meta[${attr}="${key}"]`);
  if (!el) {
    el = document.createElement('meta');
    el.setAttribute(attr, key);
    document.head.appendChild(el);
  }
  el.setAttribute('content', content);
}

export function useDocumentMeta({ title, description, path }: MetaOptions): void {
  useEffect(() => {
    const url = `${SITE}${path}`;
    document.title = title;
    upsertMeta('name', 'description', description);
    upsertMeta('property', 'og:title', title);
    upsertMeta('property', 'og:description', description);
    upsertMeta('property', 'og:url', url);
    upsertMeta('name', 'twitter:title', title);
    upsertMeta('name', 'twitter:description', description);

    let link = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (!link) {
      link = document.createElement('link');
      link.rel = 'canonical';
      document.head.appendChild(link);
    }
    link.href = url;
  }, [title, description, path]);
}
