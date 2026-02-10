# Blue Light - 배포 옵션 비교

> 싱가포르 프로젝트 요청자 및 협업자가 외부에서 상시 접근할 수 있도록 배포하기 위한 옵션 정리
> 작성일: 2026-02-08

## 프로젝트 배포 요구사항

- **서비스 구성**: Spring Boot (Backend) + MySQL 8.0 (DB) + React (Frontend)
- **용도**: MVP 데모 및 협업자 확인용 (프로덕션 트래픽 아님)
- **접근 대상**: 싱가포르 프로젝트 요청자 + 한국 개발팀

---

## 옵션 1: Railway (PaaS) — 추천

### 개요
Git push만으로 자동 배포되는 PaaS 플랫폼. 서버 관리 불필요.

### 가격
| 플랜 | 구독료 | 포함 크레딧 | 비고 |
|------|--------|------------|------|
| Free Trial | $0 | $5 (1회, 30일) | 30일 후 Free 전환 |
| Free | $0 | $1/월 | RAM 0.5GB 제한, 이 프로젝트엔 부족 |
| **Hobby** | **$5/월** | **$5/월** | **권장 플랜** |
| Pro | $20/월 | $20/월 | 팀 기능 포함 |

### 리소스 제한 (Hobby 플랜)
| 항목 | 제한 |
|------|------|
| RAM | 서비스당 최대 48 GB |
| CPU | 서비스당 최대 48 vCPU |
| Volume | 5 GB |
| Replicas | 6개 |

### 과금 방식
- **CPU**: $20/vCPU/월 (실제 사용률 기반)
- **RAM**: $10/GB/월 (실제 사용률 기반)
- **Network Egress**: $0.05/GB
- **Volume Storage**: $0.15/GB/월

### 예상 비용 (이 프로젝트)
| 서비스 | 예상 RAM | 예상 CPU | 예상 월 비용 |
|--------|----------|----------|-------------|
| MySQL | ~0.3 GB | ~0.1 vCPU | ~$5 |
| Spring Boot | ~0.5 GB | ~0.1 vCPU | ~$7 |
| React (정적) | ~0.1 GB | ~0.05 vCPU | ~$2 |
| **합계** | | | **~$5~14/월** |

> 유휴 시간이 많으면 실제 과금은 더 낮아질 수 있음 (사용률 기반 과금)

### 장점
- Git push 자동 배포
- MySQL 애드온 제공
- 서버 관리 불필요
- 환경변수 GUI 설정
- HTTPS 자동 적용

### 단점
- 완전 무료 불가 (최소 $5/월)
- 서비스 3개 운영 시 $5 초과 가능

### 배포 방법 (요약)
1. railway.com 가입 → Hobby 플랜 구독
2. 프로젝트 생성 → MySQL 애드온 추가
3. GitHub 연동 → Backend 서비스 추가 (Spring Boot)
4. Frontend 서비스 추가 (React 정적 빌드)
5. 환경변수 설정 (DB 연결, JWT, CORS 등)
6. 자동 배포 확인

---

## 옵션 2: AWS Lightsail (VPS)

### 개요
저렴한 고정 가격 VPS. Docker Compose를 그대로 올려서 운영.

### 가격
| 인스턴스 | RAM | CPU | SSD | 전송량 | 월 비용 |
|----------|-----|-----|-----|--------|---------|
| Nano | 0.5 GB | 1 vCPU | 20 GB | 1 TB | $3.50 |
| **Micro** | **1 GB** | **1 vCPU** | **40 GB** | **2 TB** | **$5** |
| Small | 2 GB | 1 vCPU | 60 GB | 3 TB | $10 |

### 장점
- 고정 비용 (예측 가능)
- Docker Compose 그대로 사용
- SSH 접근으로 자유로운 커스터마이징
- 3개월 무료 체험 (일부 인스턴스)

### 단점
- 서버 직접 관리 필요 (업데이트, 보안 등)
- 배포 자동화 별도 구성 필요
- $5 인스턴스 (1GB RAM)에서 3개 서비스 빡빡할 수 있음 → $10 인스턴스 권장

### 배포 방법 (요약)
1. Lightsail 인스턴스 생성 (Ubuntu, $5~10/월)
2. Docker + Docker Compose 설치
3. 프로젝트 clone → docker-compose.yml 수정 (프로덕션용)
4. Nginx 설정 (리버스 프록시 + 정적 파일)
5. Let's Encrypt SSL 설정
6. 수동 또는 GitHub Actions로 배포

---

## 옵션 3: Render (PaaS)

### 개요
Railway와 유사한 PaaS. 정적 사이트 무료 호스팅이 장점.

### 가격
| 서비스 | 비용 |
|--------|------|
| Static Site (React) | 무료 |
| Web Service (Spring Boot) | $7/월~ |
| PostgreSQL (무료) | 무료 (90일, 이후 유료) |
| MySQL | 직접 지원 안 함 (PostgreSQL 권장) |

### 장점
- React 정적 사이트 무료
- Git push 자동 배포
- PostgreSQL 90일 무료

### 단점
- MySQL 미지원 → PostgreSQL 전환 필요
- 무료 서비스 15분 유휴 시 슬립 (첫 요청 30초+ 대기)
- 백엔드 최소 $7/월

### 이 프로젝트 적합성
- MySQL → PostgreSQL 전환 작업 필요하므로 **비추천**

---

## 옵션 4: Oracle Cloud Free Tier (완전 무료)

### 개요
Oracle Cloud의 Always Free 인스턴스. ARM 기반으로 넉넉한 리소스 무료 제공.

### 무료 리소스
| 항목 | 제공량 |
|------|--------|
| ARM 인스턴스 | 최대 4 OCPU, 24 GB RAM |
| Boot Volume | 200 GB |
| 네트워크 | 10 TB/월 |
| MySQL HeatWave | 항상 무료 (별도) |

### 장점
- 완전 무료 (신용카드 등록 필요하나 과금 없음)
- 리소스 매우 넉넉 (24GB RAM)
- MySQL HeatWave 무료 제공

### 단점
- 가입 승인이 어려울 수 있음 (리전 제한)
- ARM 아키텍처 → Docker 이미지 호환성 확인 필요
- 초기 세팅 복잡 (네트워크, 방화벽 등)
- 안정성/가용성 보장 없음 (Free Tier)

---

## 옵션 5: ngrok / Cloudflare Tunnel (임시용)

### 개요
로컬 서버를 외부에서 접근 가능하게 터널링. 즉시 공유 가능.

### 가격
- ngrok 무료 (랜덤 URL, 세션 제한)
- Cloudflare Tunnel 무료 (고정 도메인 가능)

### 장점
- 설정 1분, 즉시 공유
- 추가 비용 없음

### 단점
- PC를 켜놔야 작동 (상시 운영 불가)
- 무료 ngrok은 URL이 매번 바뀜
- 불안정 (네트워크 끊김)

### 적합성
- 일회성 데모 용도로만 적합

---

## 최종 비교 요약

| 옵션 | 월 비용 | 난이도 | 상시 운영 | MySQL 지원 | 자동 배포 |
|------|---------|--------|----------|-----------|----------|
| **Railway (Hobby)** | **$5~14** | **쉬움** | **O** | **O** | **O** |
| AWS Lightsail | $5~10 (고정) | 보통 | O | O | 별도 구성 |
| Render | $7~15 | 쉬움 | O | X (PostgreSQL) | O |
| Oracle Cloud | 무료 | 어려움 | O | O | 별도 구성 |
| ngrok/Tunnel | 무료 | 쉬움 | X | - | - |

### 추천 순위
1. **Railway Hobby** — 가장 빠르고 간편, MVP 데모에 최적
2. **AWS Lightsail** — 고정 비용, 자유로운 커스터마이징
3. **Oracle Cloud Free** — 비용 0원, 단 셋업 난이도 높음

---

## 배포 시 필요한 작업

배포 옵션 선택 후 아래 작업이 추가로 필요:

1. **Docker 프로덕션 설정**
   - Backend Dockerfile 작성 (멀티스테이지 빌드)
   - Frontend Dockerfile 또는 정적 빌드 설정
   - docker-compose.prod.yml 작성

2. **환경변수 분리**
   - DB 접속 정보 (호스트, 포트, 비밀번호)
   - JWT Secret (프로덕션용 강력한 키)
   - CORS 허용 도메인 (배포 URL)
   - 파일 업로드 경로

3. **보안 설정**
   - HTTPS 적용 (SSL 인증서)
   - 프로덕션 DB 비밀번호 설정
   - JWT Secret 변경

4. **프론트엔드 API URL**
   - `VITE_API_BASE_URL`을 배포된 백엔드 URL로 변경
