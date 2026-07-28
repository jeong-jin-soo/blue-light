/**
 * 공개 라우트별 정적 HTML(head) 생성 — vite build 후 실행.
 *
 * 왜 필요한가:
 *   SPA 는 모든 경로에 동일한 index.html 을 반환한다. index.html 의 canonical 이
 *   홈("/")으로 고정돼 있어, Google 이 JS 를 렌더하기 전 단계에서 /services·/about·
 *   /services/:slug 를 전부 "홈의 중복"으로 판정했다.
 *   (Search Console: "적절한 표준 태그가 포함된 대체 페이지" → 색인 제외)
 *
 * 무엇을 하는가:
 *   dist/index.html 을 템플릿으로, 라우트별 title/description/canonical/OG/Twitter 를
 *   치환한 dist/<route>/index.html 을 생성한다. nginx 의 `try_files $uri $uri/ /index.html`
 *   이 디렉터리 index 를 먼저 집으므로, 크롤러는 첫 응답부터 올바른 canonical 을 받는다.
 *   (vite-prerender-plugin 은 빌드 프로세스 hang → CI 배포 차단 이력이 있어 사용하지 않는다.
 *    본문 렌더는 기존대로 클라이언트가 담당하고, 여기서는 head 만 정적화한다.)
 *
 * SSOT: 메타 문구는 src/constants/seoMeta.ts 를 그대로 소비한다(하드코딩 금지).
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DIST = join(ROOT, 'dist');

const { SEO_META, SERVICE_SEO, SITE_ORIGIN } = await import(
  join(ROOT, 'src/constants/seoMeta.ts')
);

/**
 * 생성 대상 라우트 — 홈(/)은 dist/index.html 이 이미 정답이라 제외.
 * out: 파일을 쓸 경로 / canonical: 선언할 정규 URL 경로(별칭은 원본을 가리킨다).
 */
const ROUTES = [
  ...Object.values(SEO_META)
    .filter((meta) => meta.path !== SEO_META.home.path)
    .map((meta) => ({ out: meta.path, canonical: meta.path, title: meta.title, description: meta.description })),
  // /privacy-policy 는 /privacy 와 같은 페이지 — 별칭이므로 canonical 만 원본으로 돌린다.
  {
    out: '/privacy-policy',
    canonical: SEO_META.privacy.path,
    title: SEO_META.privacy.title,
    description: SEO_META.privacy.description,
  },
  ...Object.entries(SERVICE_SEO).map(([slug, seo]) => ({
    out: `/services/${slug}`,
    canonical: `/services/${slug}`,
    title: seo.title,
    description: seo.description,
  })),
];

/** HTML 속성값 이스케이프 — 메타 문구에 따옴표/앰퍼샌드가 들어와도 안전하게. */
function esc(value) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/** 정확히 1건만 치환되어야 한다 — 템플릿이 바뀌어 매칭이 깨지면 빌드를 실패시킨다. */
function replaceOnce(html, pattern, replacement, label) {
  const matches = html.match(pattern);
  if (!matches || matches.length !== 1) {
    throw new Error(
      `[prerender-head] index.html 템플릿에서 "${label}" 를 1건 찾지 못했습니다 (${matches?.length ?? 0}건). ` +
        'index.html 수정 후 이 스크립트의 패턴도 함께 갱신하세요.',
    );
  }
  return html.replace(pattern, replacement);
}

const template = readFileSync(join(DIST, 'index.html'), 'utf8');

for (const route of ROUTES) {
  const url = `${SITE_ORIGIN}${route.canonical}`;
  const title = esc(route.title);
  const description = esc(route.description);

  let html = template;
  html = replaceOnce(html, /<title>[\s\S]*?<\/title>/, `<title>${title}</title>`, '<title>');
  html = replaceOnce(
    html,
    /<meta name="description" content="[^"]*" \/>/,
    `<meta name="description" content="${description}" />`,
    'meta[name=description]',
  );
  html = replaceOnce(
    html,
    /<link rel="canonical" href="[^"]*" \/>/,
    `<link rel="canonical" href="${url}" />`,
    'link[rel=canonical]',
  );
  html = replaceOnce(
    html,
    /<meta property="og:title" content="[^"]*" \/>/,
    `<meta property="og:title" content="${title}" />`,
    'meta[property=og:title]',
  );
  html = replaceOnce(
    html,
    /<meta property="og:description" content="[^"]*" \/>/,
    `<meta property="og:description" content="${description}" />`,
    'meta[property=og:description]',
  );
  html = replaceOnce(
    html,
    /<meta property="og:url" content="[^"]*" \/>/,
    `<meta property="og:url" content="${url}" />`,
    'meta[property=og:url]',
  );
  html = replaceOnce(
    html,
    /<meta name="twitter:title" content="[^"]*" \/>/,
    `<meta name="twitter:title" content="${title}" />`,
    'meta[name=twitter:title]',
  );
  html = replaceOnce(
    html,
    /<meta name="twitter:description" content="[^"]*" \/>/,
    `<meta name="twitter:description" content="${description}" />`,
    'meta[name=twitter:description]',
  );

  const outDir = join(DIST, route.out);
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, 'index.html'), html, 'utf8');
  console.log(`[prerender-head] ${route.out}/index.html → canonical ${route.canonical}`);
}

console.log(`[prerender-head] ${ROUTES.length}개 라우트 생성 완료`);
