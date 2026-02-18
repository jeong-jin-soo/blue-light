#!/usr/bin/env node
/**
 * Applicant 신청 상세 스크린샷 재캡처
 * 기존 15-app-detail-*.png 6장이 잘못 캡처되어 수정
 */
import puppeteer from 'puppeteer';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCREENSHOTS_DIR = path.join(__dirname, 'screenshots');
const BASE_URL = 'http://localhost:5174';
const VIEWPORT = { width: 1280, height: 800 };

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function screenshot(page, name) {
  await sleep(600);
  const filePath = path.join(SCREENSHOTS_DIR, `${name}.png`);
  await page.screenshot({ path: filePath });
  console.log(`  ✓ ${name}.png`);
}

(async () => {
  console.log('🔄 신청 상세 스크린샷 재캡처 시작\n');

  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
    defaultViewport: VIEWPORT,
  });

  const page = await browser.newPage();

  // Applicant 로그인
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle0' });
  await sleep(500);
  await page.type('input[type="email"]', 'autotest@test.com');
  await page.type('input[type="password"]', 'admin1234');
  await page.click('button[type="submit"]');
  await page.waitForNavigation({ waitUntil: 'networkidle0' }).catch(() => {});
  await sleep(1000);
  console.log('✅ Applicant 로그인 완료');

  // 신청 목록에서 첫 번째 신청 ID 가져오기
  await page.goto(`${BASE_URL}/applications`, { waitUntil: 'networkidle0' });
  await sleep(500);

  // 신청 목록에서 URL 링크를 찾거나, 직접 첫 번째 행의 href 확인
  const appId = await page.evaluate(() => {
    // 방법 1: a 링크에서 추출
    const links = document.querySelectorAll('a[href*="/applications/"]');
    for (const link of links) {
      const match = link.href.match(/\/applications\/(\d+)/);
      if (match) return match[1];
    }
    // 방법 2: 테이블 행에서 ID 텍스트 추출
    const tds = document.querySelectorAll('tbody tr td');
    for (const td of tds) {
      const text = td.textContent.trim();
      if (text.startsWith('#')) return text.replace('#', '');
    }
    return null;
  });

  if (!appId) {
    // 대시보드에서 이동할 수도 있으므로, 가장 최근 신청 ID를 API로 확인
    console.log('⚠️ 목록에서 ID 추출 실패. /applications/14 시도...');
  }

  const targetId = appId || '14';
  console.log(`📌 신청 #${targetId} 상세 캡처\n`);

  // 신청 상세 페이지로 직접 이동
  await page.goto(`${BASE_URL}/applications/${targetId}`, { waitUntil: 'networkidle0' });
  await sleep(1000);

  // 현재 URL 확인
  const currentUrl = page.url();
  console.log(`  현재 URL: ${currentUrl}`);

  // 1) 상단 (Application Detail Top - status, sidebar, property header)
  await page.evaluate(() => window.scrollTo(0, 0));
  await screenshot(page, '15-app-detail-top');

  // 2) 정보 섹션 (Property Details, Pricing)
  await page.evaluate(() => window.scrollTo(0, 500));
  await screenshot(page, '15-app-detail-info');

  // 3) LOA 섹션
  await page.evaluate(() => window.scrollTo(0, 1000));
  await screenshot(page, '15-app-detail-loa');

  // 4) 서류 섹션
  await page.evaluate(() => window.scrollTo(0, 1500));
  await screenshot(page, '15-app-detail-docs');

  // 5) 결제 정보 섹션
  await page.evaluate(() => window.scrollTo(0, 2000));
  await screenshot(page, '15-app-detail-payment');

  // 6) 하단 (사이드바 Quick Info, footer)
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await screenshot(page, '15-app-detail-bottom');

  await browser.close();
  console.log('\n✅ 신청 상세 스크린샷 재캡처 완료!');
})();
