import { test, expect } from '@playwright/test';
import { SEED, loginUI } from './helpers';

/**
 * 스모크: 공개 화면 + 인증 + 역할별 대시보드 진입.
 */

test.describe('public pages', () => {
  test('landing renders hero and CTAs', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: /Electrical Installation Licences/i })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Apply for a Licence' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Start Kaki Concierge' })).toBeVisible();
  });

  test('landing → login navigation', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Sign In' }).click();
    await expect(page).toHaveURL(/\/login/);
    await expect(page.locator('input[type="email"]')).toBeVisible();
  });

  test('login page shows split brand panel value line', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByText(/Singapore['’]s electrical licensing/).first()).toBeVisible();
  });
});

test.describe('auth', () => {
  test('wrong password is rejected and stays on login', async ({ page }) => {
    await page.goto('/login');
    await page.locator('input[type="email"]').fill(SEED.admin.email);
    await page.locator('input[type="password"]').fill('wrong-password');
    await page.locator('button[type="submit"]').click();
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole('button', { name: 'Logout' })).not.toBeVisible();
  });

  test('unauthenticated access to admin route redirects to login', async ({ page }) => {
    await page.goto('/admin/dashboard');
    await expect(page).toHaveURL(/\/login/);
  });
});

test.describe('role dashboards', () => {
  test('admin → Admin Dashboard with Hero KPI', async ({ page }) => {
    await loginUI(page, SEED.admin.email, SEED.admin.password);
    await expect(page.getByRole('heading', { name: 'Admin Dashboard' })).toBeVisible();
    await expect(page.getByText('Needs your review')).toBeVisible();
    await expect(page.getByText('Action queue')).toBeVisible();
  });

  test('lew → LEW Dashboard', async ({ page }) => {
    await loginUI(page, SEED.lew.email, SEED.lew.password);
    await expect(page.getByRole('heading', { name: 'LEW Dashboard' })).toBeVisible();
  });

  test('sldmanager → SLD Dashboard', async ({ page }) => {
    await loginUI(page, SEED.sldmanager.email, SEED.sldmanager.password);
    await expect(page.getByRole('heading', { name: /SLD.*Dashboard/i })).toBeVisible();
  });

  test('conciergemanager → Concierge Dashboard', async ({ page }) => {
    await loginUI(page, SEED.conciergemanager.email, SEED.conciergemanager.password);
    await expect(page.getByRole('heading', { name: /Dashboard/i })).toBeVisible();
  });
});
