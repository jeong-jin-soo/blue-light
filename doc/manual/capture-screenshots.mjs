#!/usr/bin/env node
/**
 * LicenseKaki 매뉴얼 스크린샷 자동 캡처 스크립트
 * Usage: node capture-screenshots.mjs
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

async function screenshot(page, name, opts = {}) {
  await sleep(500); // wait for animations
  const filePath = path.join(SCREENSHOTS_DIR, `${name}.png`);
  if (opts.fullPage) {
    await page.screenshot({ path: filePath, fullPage: true });
  } else {
    await page.screenshot({ path: filePath });
  }
  console.log(`  ✓ ${name}.png`);
}

async function login(page, email, password) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle0' });
  await sleep(500);
  // Clear and type email
  const emailInput = await page.$('input[type="email"]');
  if (!emailInput) {
    // Might already be logged in, try logout first
    await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle0' });
    await sleep(500);
  }
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('input');
    inputs.forEach(i => { i.value = ''; i.dispatchEvent(new Event('input', {bubbles:true})); });
  });
  await sleep(200);
  await page.type('input[type="email"]', email);
  await page.type('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForNavigation({ waitUntil: 'networkidle0' }).catch(() => {});
  await sleep(800);
}

async function logout(page) {
  // Clear localStorage token and navigate to login
  await page.evaluate(() => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    localStorage.clear();
  });
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle0' });
  await sleep(500);
}

(async () => {
  console.log('🚀 LicenseKaki 매뉴얼 스크린샷 캡처 시작\n');

  const browser = await puppeteer.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
    defaultViewport: VIEWPORT,
  });

  const page = await browser.newPage();

  // ===== 1. 공통 화면 (비로그인) =====
  console.log('📌 1. 공통 화면 (비로그인)');

  // 01. 로그인 페이지
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle0' });
  await screenshot(page, '01-login');

  // 02. 회원가입 페이지 (Applicant)
  await page.goto(`${BASE_URL}/signup`, { waitUntil: 'networkidle0' });
  await screenshot(page, '02-signup-top');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await screenshot(page, '02-signup-bottom');

  // 03. LEW 회원가입
  await page.goto(`${BASE_URL}/signup`, { waitUntil: 'networkidle0' });
  // Click LEW tab if exists
  const lewTab = await page.evaluate(() => {
    const tabs = document.querySelectorAll('button, [role="tab"]');
    for (const t of tabs) {
      if (t.textContent.includes('LEW') || t.textContent.includes('Licensed')) {
        t.click();
        return true;
      }
    }
    return false;
  });
  if (lewTab) {
    await sleep(500);
    await screenshot(page, '03-signup-lew');
  }

  // 04. 비밀번호 찾기
  await page.goto(`${BASE_URL}/forgot-password`, { waitUntil: 'networkidle0' });
  await screenshot(page, '04-forgot-password');

  // 05. 면책조항 / 개인정보
  await page.goto(`${BASE_URL}/disclaimer`, { waitUntil: 'networkidle0' });
  await screenshot(page, '05-disclaimer');
  await page.goto(`${BASE_URL}/privacy`, { waitUntil: 'networkidle0' });
  await screenshot(page, '05-privacy');

  // ===== 2. Applicant 화면 =====
  console.log('\n📌 2. Applicant 화면');
  await login(page, 'autotest@test.com', 'admin1234');

  // 10. 대시보드
  await page.goto(`${BASE_URL}/dashboard`, { waitUntil: 'networkidle0' });
  await screenshot(page, '10-applicant-dashboard');

  // 11. 신청 목록
  await page.goto(`${BASE_URL}/applications`, { waitUntil: 'networkidle0' });
  await screenshot(page, '11-application-list');

  // 12. 새 신청 - Before You Begin
  await page.goto(`${BASE_URL}/applications/new`, { waitUntil: 'networkidle0' });
  await screenshot(page, '12-new-app-guide');

  // 13. 새 신청 - Step 1 시작 (Continue 클릭)
  const continueBtn = await page.evaluate(() => {
    const buttons = document.querySelectorAll('button');
    for (const b of buttons) {
      if (b.textContent.includes('Continue') || b.textContent.includes('Begin') || b.textContent.includes('Start')) {
        b.click();
        return true;
      }
    }
    return false;
  });
  await sleep(800);
  await screenshot(page, '13-new-app-step1');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await screenshot(page, '13-new-app-step1-bottom');

  // 14. 신청 상세 — 목록에서 첫 번째 신청 ID를 찾아 직접 이동
  await page.goto(`${BASE_URL}/applications`, { waitUntil: 'networkidle0' });
  await sleep(500);
  const appId = await page.evaluate(() => {
    // a 링크에서 /applications/:id 추출
    const links = document.querySelectorAll('a[href*="/applications/"]');
    for (const link of links) {
      const match = link.href.match(/\/applications\/(\d+)/);
      if (match) return match[1];
    }
    return null;
  });
  const targetAppId = appId || '14';
  await page.goto(`${BASE_URL}/applications/${targetAppId}`, { waitUntil: 'networkidle0' });
  await sleep(1000);
  await screenshot(page, '15-app-detail-top');

  // Scroll to see more sections
  await page.evaluate(() => window.scrollTo(0, 500));
  await sleep(300);
  await screenshot(page, '15-app-detail-info');

  await page.evaluate(() => window.scrollTo(0, 1000));
  await sleep(300);
  await screenshot(page, '15-app-detail-loa');

  await page.evaluate(() => window.scrollTo(0, 1500));
  await sleep(300);
  await screenshot(page, '15-app-detail-docs');

  await page.evaluate(() => window.scrollTo(0, 2000));
  await sleep(300);
  await screenshot(page, '15-app-detail-payment');

  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await screenshot(page, '15-app-detail-bottom');

  // 16. 프로필
  await page.goto(`${BASE_URL}/profile`, { waitUntil: 'networkidle0' });
  await screenshot(page, '16-profile-top');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await screenshot(page, '16-profile-bottom');

  // 17. AI 챗봇
  // Click chat bubble
  const chatBubble = await page.evaluate(() => {
    const btn = document.querySelector('button[aria-label*="chat"], button[aria-label*="Chat"]');
    if (btn) { btn.click(); return true; }
    return false;
  });
  await sleep(500);
  await screenshot(page, '17-chatbot-open');

  // Close chat
  const closeChat = await page.evaluate(() => {
    const btn = document.querySelector('button[aria-label*="Close chat"]');
    if (btn) { btn.click(); return true; }
    return false;
  });
  await sleep(300);
  await screenshot(page, '17-chatbot-bubble');

  await logout(page);

  // ===== 3. Admin 화면 =====
  console.log('\n📌 3. Admin 화면');
  await login(page, 'admin@licensekaki.sg', 'admin1234');

  // 20. 관리자 대시보드
  await page.goto(`${BASE_URL}/admin/dashboard`, { waitUntil: 'networkidle0' });
  await screenshot(page, '20-admin-dashboard');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await screenshot(page, '20-admin-dashboard-bottom');

  // 21. 신청 관리
  await page.goto(`${BASE_URL}/admin/applications`, { waitUntil: 'networkidle0' });
  await screenshot(page, '21-admin-applications');

  // 22. 신청 상세 (첫 번째)
  const adminFirstApp = await page.evaluate(() => {
    const rows = document.querySelectorAll('tbody tr');
    if (rows.length > 0) { rows[0].click(); return true; }
    return false;
  });
  await sleep(800);
  await screenshot(page, '22-admin-app-detail-top');

  await page.evaluate(() => window.scrollTo(0, 500));
  await sleep(300);
  await screenshot(page, '22-admin-app-detail-info');

  await page.evaluate(() => window.scrollTo(0, 1000));
  await sleep(300);
  await screenshot(page, '22-admin-app-detail-loa');

  await page.evaluate(() => window.scrollTo(0, 1500));
  await sleep(300);
  await screenshot(page, '22-admin-app-detail-docs');

  await page.evaluate(() => window.scrollTo(0, 2000));
  await sleep(300);
  await screenshot(page, '22-admin-app-detail-payment');

  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await screenshot(page, '22-admin-app-detail-bottom');

  // 23. 사용자 관리
  await page.goto(`${BASE_URL}/admin/users`, { waitUntil: 'networkidle0' });
  await screenshot(page, '23-admin-users');

  // 24. 시스템 설정
  await page.goto(`${BASE_URL}/admin/prices`, { waitUntil: 'networkidle0' });
  await screenshot(page, '24-admin-settings-top');
  await page.evaluate(() => window.scrollTo(0, 600));
  await sleep(300);
  await screenshot(page, '24-admin-settings-mid');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await screenshot(page, '24-admin-settings-bottom');

  await logout(page);

  // ===== 4. LEW 화면 =====
  console.log('\n📌 4. LEW 화면');
  await login(page, 'lew@licensekaki.sg', 'admin1234');

  // 30. LEW 대시보드
  await page.goto(`${BASE_URL}/admin/dashboard`, { waitUntil: 'networkidle0' });
  await screenshot(page, '30-lew-dashboard');

  // 31. LEW 신청 목록
  await page.goto(`${BASE_URL}/admin/applications`, { waitUntil: 'networkidle0' });
  await screenshot(page, '31-lew-applications');

  // 32. LEW 신청 상세
  const lewFirstApp = await page.evaluate(() => {
    const rows = document.querySelectorAll('tbody tr');
    if (rows.length > 0) { rows[0].click(); return true; }
    return false;
  });
  await sleep(800);
  await screenshot(page, '32-lew-app-detail');

  await logout(page);

  await browser.close();

  console.log('\n✅ 스크린샷 캡처 완료!');
  console.log(`📁 저장 경로: ${SCREENSHOTS_DIR}`);
})();
