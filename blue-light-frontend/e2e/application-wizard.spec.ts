import { test, expect } from '@playwright/test';
import { apiSignupApplicant, loginUI } from './helpers';

/**
 * 신청 마법사 전체 UI 제출 — 가이드 → Step 0~3 → Declaration → 제출 → 상세 이동.
 * kVA는 "I don't know"(Phase 5) 경로로 제출해 UNKNOWN 흐름을 함께 검증.
 */

test('full application wizard submits via UI', async ({ page, request }) => {
  const applicant = await apiSignupApplicant(request);
  await loginUI(page, applicant.email, applicant.password);

  await page.goto('/applications/new');

  // ── Before You Begin 가이드 통과
  await expect(page.getByRole('heading', { name: 'Before You Begin' })).toBeVisible();
  await page.getByRole('button', { name: 'Start Application' }).first().click();

  // ── Step 0: Application Type (NEW 기본) + Licence Period
  await expect(page.getByRole('heading', { name: 'Application Type' })).toBeVisible();
  // 우측 가이드 레일(§9-2 C) 노출 확인
  await expect(page.getByText('No documents needed now')).toBeVisible();
  await page.getByRole('button', { name: /New Licence/ }).first().click();
  await page.getByRole('button', { name: /12 Months/ }).click();
  await page.getByRole('button', { name: 'Continue' }).click();

  // ── Step 1: 5-part 주소 (Block/Street/Postal 필수)
  await expect(page.getByRole('heading', { name: 'Property Details' })).toBeVisible();
  await page.getByLabel('Block / House No').fill('133');
  await page.getByLabel('Street Name').fill('NEW BRIDGE ROAD');
  await page.getByLabel('Postal Code').fill('059413');
  await page.getByRole('button', { name: 'Continue' }).click();

  // ── Step 2: kVA — "I don't know" 경로
  await expect(page.getByRole('heading', { name: 'Capacity & Pricing' })).toBeVisible();
  // 우측 레일에 kVA 팁 노출
  await expect(page.getByText('Not sure about your kVA?')).toBeVisible();
  await page
    .getByLabel(/Electric Box \(kVA\)/)
    .selectOption({ label: "I don't know — let LEW confirm me later" });
  await page.getByRole('button', { name: 'Continue' }).click();

  // ── Step 3: Review + Declaration 3종 체크
  await expect(page.getByRole('heading', { name: 'Declaration' })).toBeVisible();
  await page.locator('label').filter({ hasText: 'true and complete' }).locator('input').check();
  await page.locator('label').filter({ hasText: 'complies with Singapore' }).locator('input').check();
  await page.locator('label').filter({ hasText: 'periodic inspections' }).locator('input').check();

  // ── 제출 → ConfirmDialog → 상세 페이지 이동
  await page.getByRole('button', { name: 'Submit Application' }).click();
  await page.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page).toHaveURL(/\/applications\/\d+/, { timeout: 15_000 });
  await expect(page.getByText('Pending Review').first()).toBeVisible();
});
