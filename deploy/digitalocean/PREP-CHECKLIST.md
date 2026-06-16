# 이관 전 — 사용자 사전 준비 체크리스트

> 코드/스크립트(제가 작성한 초안)와 별개로, **사람만 할 수 있는 일**(가입·결제·접근권한·값 확보·결정)을 모았습니다.
> ⛔ = 이게 없으면 다음 단계 진행 불가(blocking).

## A. DigitalOcean 계정 ⛔ (가장 먼저)
- [ ] **DO 가입** — https://www.digitalocean.com (이메일은 운영용 사용)
- [ ] **국가 = 대한민국으로 정확히 설정** ← AWS의 KH(캄보디아) 실수 반복 금지. 결제문제 재발 방지.
- [ ] **결제수단 등록** — 신용카드 또는 PayPal(한국 카드 가능). 신규 계정은 소액 인증/초기 디파짓 있을 수 있음.
- [ ] **2FA 활성화** (보안)
- [ ] (선택) 팀/프로젝트 이름 `licensekaki` 생성

## B. DO 접근 도구·키 (가입 후)
- [ ] **API 토큰 2종 발급** (Settings → API → Tokens)
  - `DIGITALOCEAN_ACCESS_TOKEN` — CI에서 빌드/푸시·doctl용 (read+write)
  - `DO_REGISTRY_TOKEN` — Droplet에서 이미지 pull용 (read-only 권장)
- [ ] **SSH 키 새로 생성 + DO 등록** — `ssh-keygen -t ed25519 -C bluelight-do`
  - ⚠️ 노출된 기존 `bluelight-key.pem` 재사용 금지. 새 키페어 사용.
- [ ] `doctl` CLI 설치 + `doctl auth init`(로컬에서 프로비저닝할 경우)

## C. 도메인 / 이메일 접근 확인 ⛔
- [ ] **NameCheap 로그인 접근** 확인 (licensekaki.com 소유 계정) — 네임서버 변경 권한
- [ ] **Resend 대시보드 로그인 접근** 확인
  - [ ] 현재 운영 중인 **Resend API 키** 확보(= `MAIL_PASSWORD`). 모르면 Resend에서 재발급(기존 무효화 주의)
  - [ ] 도메인 재검증(Verified) 가능 상태 확인
- [ ] **Google Workspace** 관리 접근(수신 MX 그대로 둘 것이라 변경은 없지만, 문제 시 대비)

## D. 기존 시크릿 값 확보 (이전 시 동일 유지 필요) ⛔
> 운영 GitHub Secrets / 서버 `.env`에서 현재 값을 그대로 옮겨야 함. 특히 ★는 바뀌면 장애.
- [ ] ★ **`FILE_ENCRYPTION_KEY`** — 다르면 **기존 업로드 파일 전부 복호화 불가**. 현재 값 정확히 확보.
- [ ] **`JWT_SECRET`** — 바뀌면 기존 로그인 세션/토큰 무효(재로그인 필요)
- [ ] **DB 접속정보**(`DB_PASSWORD` 등) — RDS 덤프용
- [ ] `GEMINI_API_KEY`, `SLD_AGENT_SERVICE_KEY`
- [ ] 현재 운영 GitHub Secrets **전체 목록 스냅샷** (development/production 둘 다)

## E. GitHub 권한
- [ ] 레포 **Settings → Secrets and variables → Actions** 편집 권한
- [ ] **Environments**(development/production) 편집 권한
- [ ] 워크플로 파일 교체(PR/머지) 권한

## F. 결정해야 할 사항 (정보)
- [ ] **DNS 방식**: ① DO DNS 완전 이전(NameCheap NS 변경) vs ② Route53 유지하고 A만 교체(보수적)
- [ ] **Droplet 사양 확정**: 기본안 2GB/2vCPU ×(dev1+prod2). 부하 측정 후 조정
- [ ] **컷오버 시점**: 다운타임 수~수십 분 허용 가능한 시간대(싱가포르 트래픽 낮은 새벽 권장)
- [ ] **예산 확인**: 월 ≈ $91 (현재 AWS ~$140)

## G. 보안 (이전과 병행 권장)
- [ ] **노출된 AWS IAM 키 로테이션** (`lisenceKaki-deployer`) + GitHub Secrets 갱신
  - 이전 기간에도 이 키로 S3/배포가 도므로, 완전 종료 전까지는 폐기 대신 **신규 키로 교체**
- [ ] `AWS` 평문 파일을 1Password 등 비밀저장소로 이동, 레포·로컬에서 제거
- [ ] DO에서는 IAM 키 대신 **Spaces 전용 키**(권한 최소화) 사용

---

## 준비 순서 요약
1. **A**(DO 가입·결제·국가) → 2. **B**(토큰·SSH 키) → 3. **C·D**(도메인/이메일 접근 + 기존 시크릿 확보)
→ 4. **F**(결정) → 5. 제가 만든 `provision.sh`로 인프라 생성 → 스테이징 검증 → 컷오버

> 제가 할 수 있는 것: 인프라 스크립트·CI·DNS 레코드 작성(완료), DB 용량 실측, 키 로테이션 절차 안내.
> 사용자만 할 수 있는 것: 위 A~E(가입·결제·로그인·시크릿 보유)와 F 결정.
