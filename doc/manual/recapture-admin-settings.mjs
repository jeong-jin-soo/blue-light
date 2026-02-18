#!/usr/bin/env node
/**
 * 관리자 시스템 설정 스크린샷 재캡처
 * 원본 스크립트에서 /admin/settings 로 잘못 이동하여 404 발생
 * 실제 경로: /admin/prices
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
  await sleep(500);
  const filePath = path.join(SCREENSHOTS_DIR, `${name}.png`);
  await page.screenshot({ path: filePath });
  console.log(`  ✓ ${name}.png`);
}

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setViewport(VIEWPORT);

  // Admin 로그인
  console.log('Logging in as admin...');
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle0' });
  await sleep(500);
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('input');
    inputs.forEach(i => { i.value = ''; i.dispatchEvent(new Event('input', {bubbles:true})); });
  });
  await sleep(200);
  await page.type('input[type="email"]', 'admin@bluelight.sg');
  await page.type('input[type="password"]', 'admin1234');
  await page.click('button[type="submit"]');
  await page.waitForNavigation({ waitUntil: 'networkidle0' }).catch(() => {});
  await sleep(1000);

  // 시스템 설정 페이지 (올바른 경로: /admin/prices)
  console.log('Navigating to /admin/prices (System Settings)...');
  await page.goto(`${BASE_URL}/admin/prices`, { waitUntil: 'networkidle0' });
  await sleep(1000);

  // 상단: 이메일 인증, 서비스 수수료
  await page.evaluate(() => window.scrollTo(0, 0));
  await sleep(300);
  await screenshot(page, '24-admin-settings-top');

  // 중간: 결제 정보
  await page.evaluate(() => window.scrollTo(0, 600));
  await sleep(300);
  await screenshot(page, '24-admin-settings-mid');

  // 하단: 가격 등급 관리 — Price Tiers 헤더가 보이는 위치에서 캡처
  await page.evaluate(() => {
    const heading = [...document.querySelectorAll('h2, h3, p')].find(el => el.textContent.includes('Price Tiers'));
    if (heading) {
      heading.scrollIntoView({ block: 'start' });
      window.scrollBy(0, -20);
    } else {
      window.scrollTo(0, 1000);
    }
  });
  await sleep(500);
  await screenshot(page, '24-admin-settings-bottom');

  console.log('Done! 3 screenshots recaptured.');
  await browser.close();
})();
