import { defineConfig, devices } from '@playwright/test';

/**
 * E2E 테스트 설정.
 *
 * 사전 조건 (로컬):
 *   1. MySQL:   cd blue-light-backend && docker compose up -d
 *   2. 백엔드:  cd blue-light-backend && ./gradlew bootRun   (port 8090)
 *   3. 프론트는 webServer 설정이 자동 기동 (이미 떠 있으면 재사용)
 *
 * 실행: npm run test:e2e
 * 주의: 쓰기 시나리오(가입·신청 생성)가 로컬 DB에 데이터를 남긴다.
 *       개발서버/운영을 향해 절대 실행하지 말 것.
 */
export default defineConfig({
  testDir: './e2e',
  // 시드 DB 상태를 공유하므로 직렬 실행 (테스트 간 간섭 방지)
  workers: 1,
  fullyParallel: false,
  timeout: 30_000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
