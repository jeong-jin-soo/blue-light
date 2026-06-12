import { test, expect } from '@playwright/test';
import { API, SEED, apiLogin, apiSignupApplicant, apiCreateApplication, loginUI } from './helpers';

/**
 * 신청 라이프사이클 — API로 상태를 만들고 UI로 검증하는 하이브리드.
 * (가입 → 신청 → admin 배정·승인 → 결제 확인 → PAID 노출)
 * C-phase API 검증에서 확인된 흐름을 그대로 코드화.
 */

test.describe.serial('applicant journey (hybrid API + UI)', () => {
  let applicant: { token: string; email: string; password: string };
  let appSeq: number;

  test('signup via API and see empty dashboard in UI', async ({ page, request }) => {
    applicant = await apiSignupApplicant(request);
    await loginUI(page, applicant.email, applicant.password);
    await expect(page.getByRole('heading', { name: /Welcome back/ })).toBeVisible();
    await expect(page.getByText('No applications yet')).toBeVisible();
  });

  test('create application via API → appears in My Applications', async ({ page, request }) => {
    const app = await apiCreateApplication(request, applicant.token);
    appSeq = app.applicationSeq;
    expect(app.status).toBe('PENDING_REVIEW');

    await loginUI(page, applicant.email, applicant.password);
    const table = page.getByRole('table');
    await expect(table.getByText('123 Playwright Street #01-01')).toBeVisible();
    await expect(table.getByText('Pending Review').first()).toBeVisible();
  });

  test('admin sees the application and processes to PAID', async ({ page, request }) => {
    const adminToken = await apiLogin(request, SEED.admin.email, SEED.admin.password);
    const auth = { Authorization: `Bearer ${adminToken}` };

    // LEW 배정
    const usersRes = await request.get(`${API}/admin/users?size=100`, { headers: auth });
    const users = (await usersRes.json());
    const lew = (users.content ?? users).find((u: { email: string }) => u.email === SEED.lew.email);
    expect(lew).toBeTruthy();
    const assignRes = await request.post(`${API}/admin/applications/${appSeq}/assign-lew`, {
      headers: auth,
      data: { lewUserSeq: lew.userSeq },
    });
    expect(assignRes.ok()).toBeTruthy();

    // 승인 → PENDING_PAYMENT
    const approveRes = await request.post(`${API}/admin/applications/${appSeq}/approve`, { headers: auth });
    expect(approveRes.ok()).toBeTruthy();
    expect((await approveRes.json()).status).toBe('PENDING_PAYMENT');

    // 결제 확인 → PAID
    const payRes = await request.post(`${API}/admin/applications/${appSeq}/payments/confirm`, {
      headers: auth,
      data: { paymentMethod: 'PAYNOW', transactionId: `PW-${Date.now()}` },
    });
    expect(payRes.status()).toBe(201);

    // UI: admin 리스트에서 해당 신청이 Paid로 보임
    await loginUI(page, SEED.admin.email, SEED.admin.password);
    await page.goto('/admin/applications');
    await page.getByPlaceholder(/Search by address/i).fill(applicant.email);
    const adminTable = page.getByRole('table');
    await expect(adminTable.getByText('Playwright Tester').first()).toBeVisible({ timeout: 10_000 });
    await expect(adminTable.getByText('Paid').first()).toBeVisible();
  });

  test('applicant sees PAID status and notifications', async ({ page }) => {
    await loginUI(page, applicant.email, applicant.password);
    await expect(page.getByRole('table').getByText('Paid').first()).toBeVisible();
  });
});
