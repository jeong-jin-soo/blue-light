import { expect, type APIRequestContext, type Page } from '@playwright/test';

export const API = 'http://localhost:8090/api';

/** 시드 계정 (로컬 data.sql 기준) */
export const SEED = {
  admin: { email: 'admin@licensekaki.sg', password: 'admin1234' },
  lew: { email: 'lew@licensekaki.sg', password: 'admin1234' },
  sysadmin: { email: 'sysadmin@licensekaki.sg', password: 'admin1234' },
  sldmanager: { email: 'sldmanager@licensekaki.sg', password: 'admin1234' },
  conciergemanager: { email: 'conciergemanager@licensekaki.sg', password: 'admin1234' },
};

/** 실행마다 고유한 테스트 이메일 생성 */
export function uniqueEmail(prefix = 'pw'): string {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e4)}@test.local`;
}

/** UI 로그인 — 로그인 페이지에서 폼 제출 후 사이드바가 보일 때까지 대기 */
export async function loginUI(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.locator('input[type="email"]').fill(email);
  await page.locator('input[type="password"]').fill(password);
  await page.locator('button[type="submit"]').click();
  // 로그인 성공 = 앱 레이아웃(로그아웃 버튼) 노출
  await expect(page.getByRole('button', { name: 'Logout' })).toBeVisible({ timeout: 10_000 });
}

/** API 로그인 → 액세스 토큰 */
export async function apiLogin(request: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await request.post(`${API}/auth/login`, { data: { email, password } });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  return body.accessToken ?? body.token;
}

/** API 회원가입(APPLICANT) → { token, email } */
export async function apiSignupApplicant(request: APIRequestContext) {
  const email = uniqueEmail();
  const res = await request.post(`${API}/auth/signup`, {
    data: {
      email,
      password: 'Test12345',
      firstName: 'Playwright',
      lastName: 'Tester',
      role: 'APPLICANT',
      pdpaConsent: true,
    },
  });
  expect(res.status()).toBe(201);
  const body = await res.json();
  return { token: body.accessToken as string, email, password: 'Test12345' };
}

/** API로 신청 생성 (C-phase 검증 페이로드 그대로) */
export async function apiCreateApplication(request: APIRequestContext, token: string) {
  const res = await request.post(`${API}/applications`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      address: '123 Playwright Street #01-01',
      postalCode: '123456',
      buildingType: 'Residential',
      selectedKva: 45,
      applicantType: 'INDIVIDUAL',
      applicationType: 'NEW',
    },
  });
  expect(res.status()).toBe(201);
  return await res.json();
}
