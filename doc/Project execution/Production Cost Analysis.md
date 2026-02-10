# Blue Light - 프로덕션 운영 비용 분석

> MVP 초기 배포 이후, 서비스 활성화 단계(AI 챗봇, 메신저 연동, 서버 다중화, DB 백업 등)의 전체 인프라 비용 분석
> 작성일: 2026-02-10
> 기준 리전: AWS Asia Pacific (Singapore) `ap-southeast-1`

---

## 1. 시나리오 정의

### Phase A: MVP 초기 (현재)
- 단일 서버, 소규모 트래픽
- 기존 `Deployment Options.md` 참조 → **$5~14/월**

### Phase B: 서비스 활성화 (본 문서 분석 대상)
- **사용자 규모**: 100~500명 활성 사용자, 동시 접속 50~100명
- **추가 기능**: AI 챗봇 (FAQ/가이드), Telegram 메신저 연동
- **인프라 요구**: 서버 다중화(Multi-AZ), DB 백업, CDN, 모니터링

### Phase C: 확장 단계 (참고용 추정)
- **사용자 규모**: 1,000~5,000명, 동시 접속 200~500명
- 대규모 트래픽 대응 아키텍처

---

## 2. Phase B 아키텍처 구성도

```
┌─────────────────────────────────────────────────────────┐
│                    AWS ap-southeast-1                     │
│                                                          │
│  ┌──────────┐     ┌──────────────────────────────┐      │
│  │CloudFront│────▶│  S3 (Frontend 정적 파일)       │      │
│  │  (CDN)   │     │  + 업로드 파일 저장소           │      │
│  └──────────┘     └──────────────────────────────┘      │
│                                                          │
│  ┌──────────┐     ┌──────────────────────────────┐      │
│  │   ALB    │────▶│  ECS Fargate (Backend x2)     │      │
│  │          │     │  AZ-a + AZ-b (Multi-AZ)       │      │
│  └──────────┘     └──────────┬───────────────────┘      │
│                              │                           │
│                    ┌─────────▼──────────┐                │
│                    │  RDS MySQL 8.0     │                │
│                    │  Multi-AZ Standby  │                │
│                    │  + 자동 백업 (7일)   │                │
│                    └────────────────────┘                │
│                                                          │
│  ┌──────────────────┐  ┌──────────────────────┐         │
│  │ Telegram Bot      │  │ AI Chatbot Service   │         │
│  │ (Fargate Task)    │  │ (Fargate Task)       │         │
│  └──────────────────┘  └──────────────────────┘         │
│                                                          │
│  ┌──────────────┐  ┌──────────┐  ┌──────────────┐      │
│  │  SES (Email) │  │ CloudWatch│  │  WAF         │      │
│  └──────────────┘  │ (모니터링) │  │ (보안)       │      │
│                    └──────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────┘
```

---

## 3. 항목별 상세 비용 (Phase B — 월간)

### 3-1. 컴퓨팅 — ECS Fargate (서버 다중화)

Spring Boot 백엔드를 ECS Fargate로 2개 Task(Multi-AZ) 운영.

| 항목 | 사양 | 단가 (싱가포르) | 수량 | 월 비용 |
|------|------|---------------|------|---------|
| Backend Task | 0.5 vCPU, 1GB RAM | vCPU $0.04656/h + RAM $0.00511/h | 2 Tasks | ~$75 |
| Telegram Bot | 0.25 vCPU, 0.5GB RAM | 동일 단가 | 1 Task | ~$20 |
| AI Chatbot Proxy | 0.25 vCPU, 0.5GB RAM | 동일 단가 | 1 Task | ~$20 |
| **소계** | | | | **~$115** |

> 💡 **절감 옵션**: Fargate Spot 사용 시 최대 70% 할인 → ~$35/월
> 💡 **대안**: EC2 t3.medium 2대 → $0.0528/h × 2 × 730h ≈ **$77/월** (RI 1년 시 ~$50)

### 3-2. 데이터베이스 — RDS MySQL Multi-AZ

| 항목 | 사양 | 단가 | 월 비용 |
|------|------|------|---------|
| RDS db.t3.medium | 2 vCPU, 4GB RAM, **Multi-AZ** | ~$0.144/h (Single $0.072 × 2) | ~$105 |
| Storage (gp3) | 50 GB | $0.138/GB-month | ~$7 |
| 자동 백업 저장소 | 50 GB (DB 크기 이내 무료 초과분) | $0.095/GB-month | ~$0 (무료 범위) |
| **소계** | | | **~$112** |

> 💡 **절감 옵션**: RI(예약 인스턴스) 1년 약정 시 ~30% 할인 → ~$78/월
> 💡 **대안**: db.t3.small Multi-AZ ($0.072/h) → ~$53/월 (초기에 충분)

### 3-3. 스토리지 — S3

| 항목 | 예상 용량 | 단가 | 월 비용 |
|------|----------|------|---------|
| S3 Standard (파일 저장) | 10 GB | $0.025/GB | ~$0.25 |
| S3 Standard (프론트엔드 빌드) | 100 MB | $0.025/GB | ~$0.01 |
| PUT/GET 요청 | ~10,000건 | $0.005/1000 PUT, $0.0004/1000 GET | ~$0.10 |
| **소계** | | | **~$1** |

### 3-4. CDN — CloudFront

| 항목 | 예상 사용량 | 단가 (싱가포르) | 월 비용 |
|------|-----------|---------------|---------|
| 데이터 전송 (아시아) | 50 GB/월 | $0.140/GB (첫 10TB) | ~$7 |
| HTTPS 요청 | 500,000건/월 | $0.0120/10,000건 | ~$0.60 |
| **소계** | | | **~$8** |

### 3-5. 로드 밸런서 — ALB

| 항목 | 단가 (싱가포르) | 월 비용 |
|------|---------------|---------|
| ALB 시간 요금 | $0.0252/h × 730h | ~$18.40 |
| LCU 사용량 (경량 트래픽) | $0.008/LCU-h × ~5 LCU avg | ~$29 |
| **소계** | | **~$25** |

> 예상 LCU: 동시 접속 50~100명, 초당 ~10 요청 기준 약 1~3 LCU 평균

### 3-6. 네트워크

| 항목 | 예상 사용량 | 단가 | 월 비용 |
|------|-----------|------|---------|
| NAT Gateway (시간) | 730h | $0.045/h | ~$33 |
| NAT Gateway (데이터) | 30 GB | $0.045/GB | ~$1.35 |
| Inter-AZ 전송 | 20 GB | $0.01/GB (양방향) | ~$0.40 |
| 인터넷 Egress (EC2/Fargate) | 50 GB | $0.12/GB (싱가포르→인터넷) | ~$6 |
| **소계** | | | **~$41** |

> ⚠️ **NAT Gateway**가 숨은 비용 — Fargate가 외부 API(OpenAI 등) 호출 시 필수
> 💡 **절감**: VPC Endpoint 활용으로 S3/ECR 트래픽 NAT 우회 가능

### 3-7. 이메일 — SES

| 항목 | 예상 사용량 | 단가 | 월 비용 |
|------|-----------|------|---------|
| 발송 (상태 알림 등) | 5,000건/월 | $0.10/1,000건 | ~$0.50 |
| 데이터 전송 | 1 GB | 포함 | $0 |
| **소계** | | | **~$1** |

### 3-8. AI 챗봇 — OpenAI API

FAQ 안내, 신청 가이드, 문서 도움말 등 경량 챗봇 용도.

| 항목 | 예상 사용량 | 단가 | 월 비용 |
|------|-----------|------|---------|
| GPT-4o-mini Input | 5M tokens/월 | $0.15/1M tokens | ~$0.75 |
| GPT-4o-mini Output | 2M tokens/월 | $0.60/1M tokens | ~$1.20 |
| **소계** | | | **~$2** |

> 사용량 기준: 일 ~100건 대화, 대화당 ~1,500 input + ~600 output 토큰
> 💡 GPT-4o-mini는 FAQ/가이드 용도에 충분한 성능, 매우 저렴

### 3-9. Telegram Bot

| 항목 | 비용 |
|------|------|
| Telegram Bot API | **무료** |
| Webhook 서버 | Fargate Task에 포함 (3-1 항목) |
| **소계** | **$0** (컴퓨팅 비용에 포함) |

### 3-10. 보안 — WAF

| 항목 | 단가 | 월 비용 |
|------|------|---------|
| Web ACL | $5/월 | $5 |
| Rule | $1/rule × 5 rules | $5 |
| 요청 | $0.60/1M 요청 × ~1M | ~$0.60 |
| **소계** | | **~$11** |

### 3-11. 모니터링 — CloudWatch

| 항목 | 월 비용 |
|------|---------|
| 기본 메트릭 (EC2, RDS, ALB) | 무료 |
| 커스텀 메트릭 (10개) | ~$3 |
| 로그 수집 (5 GB) | ~$3.50 |
| 알람 (10개) | ~$1 |
| **소계** | **~$8** |

### 3-12. 기타

| 항목 | 월 비용 |
|------|---------|
| Route 53 (도메인 DNS) | ~$0.50 |
| ACM (SSL 인증서) | 무료 |
| Secrets Manager (2개) | ~$0.80 |
| ECR (컨테이너 이미지) | ~$1 |
| **소계** | **~$3** |

---

## 4. Phase B 총 비용 요약

### 표준 구성 (On-Demand)

| 카테고리 | 월 비용 | 비율 |
|----------|---------|------|
| 컴퓨팅 (Fargate × 4 Tasks) | $115 | 35% |
| 데이터베이스 (RDS Multi-AZ) | $112 | 34% |
| 네트워크 (NAT + Inter-AZ + Egress) | $41 | 13% |
| 로드 밸런서 (ALB) | $25 | 8% |
| CDN (CloudFront) | $8 | 2% |
| 보안 (WAF) | $11 | 3% |
| 모니터링 (CloudWatch) | $8 | 2% |
| AI 챗봇 (OpenAI API) | $2 | 1% |
| 스토리지 (S3) | $1 | <1% |
| 이메일 (SES) | $1 | <1% |
| 기타 (DNS, 인증서, ECR 등) | $3 | 1% |
| **합계** | **~$327/월** | |

### 최적화 구성 (RI + Spot 활용)

| 최적화 항목 | 절감 내용 | 절감액 |
|------------|----------|--------|
| RDS 1년 RI | Multi-AZ db.t3.medium → ~30% 할인 | -$34 |
| Fargate Spot (Bot + AI) | 비핵심 Task에 Spot 적용 → 70% 할인 | -$28 |
| DB 다운사이징 | db.t3.small Multi-AZ로 시작 | -$52 |
| NAT 최적화 | VPC Endpoint로 S3/ECR 우회 | -$5 |
| **최적화 후 합계** | | **~$208/월** |

### EC2 기반 대안 (가장 저렴)

Fargate 대신 EC2로 직접 운영 시:

| 항목 | 구성 | 월 비용 |
|------|------|---------|
| EC2 t3.medium × 2 (Multi-AZ) | 1년 RI | ~$50 |
| RDS db.t3.small Multi-AZ | 1년 RI | ~$37 |
| ALB | On-Demand | ~$25 |
| NAT Gateway | 1 AZ만 | ~$34 |
| CloudFront + S3 | - | ~$9 |
| WAF + CloudWatch | - | ~$19 |
| SES + OpenAI + 기타 | - | ~$6 |
| **합계** | | **~$180/월** |

---

## 5. Phase C 확장 단계 추정 (참고)

사용자 1,000~5,000명, 동시 접속 200~500명 기준:

| 카테고리 | Phase B | Phase C | 비고 |
|----------|---------|---------|------|
| 컴퓨팅 | $115 | $230~400 | Fargate 4~8 Tasks 또는 EC2 4대 |
| 데이터베이스 | $112 | $200~350 | db.r6g.large + Read Replica |
| Redis 캐시 | $0 | $80~120 | ElastiCache cache.t3.medium |
| 네트워크 | $41 | $80~150 | 트래픽 증가에 비례 |
| ALB | $25 | $40~60 | LCU 증가 |
| CDN | $8 | $20~50 | 데이터 전송 증가 |
| AI 챗봇 | $2 | $10~30 | 대화량 10배 |
| 기타 | $24 | $30~50 | |
| **합계** | **~$327** | **~$700~1,200** | |

> 💡 Phase C에서는 **Savings Plan** (1~3년) 적용 시 40~60% 절감 가능

---

## 6. DB 백업 전략별 비용

| 전략 | 구성 | 추가 비용 |
|------|------|----------|
| **기본 자동 백업** (7일) | RDS 자동 스냅샷, DB 크기만큼 무료 | $0 (DB 크기 이내) |
| **확장 백업** (35일) | 보관 기간 연장 | +$4~8/월 (50GB 기준) |
| **Cross-Region 백업** | 싱가포르→시드니 복제 | +$10~15/월 (전송 + 저장) |
| **수동 스냅샷 보관** | 월 1회 장기 보관 | +$5/월 (50GB 기준) |

**권장**: 기본 자동 백업(7일) + 주 1회 수동 스냅샷 → **추가 비용 ~$0~5/월**

---

## 7. 비용 비교: MVP → 활성화

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│  Phase A (MVP)        Phase B (활성화)        Phase C (확장)       │
│                                                                  │
│  Railway/Lightsail    AWS 풀 구성              AWS 대규모          │
│  $14/월        ──▶    $327/월        ──▶      $1,200/월        │    
│                                                                  │
│  단일 서버             서버 2대 + Multi-AZ DB    서버 4~8대 + 캐시    │
│  백업 없음             자동 백업 7일              Cross-Region 백업   │
│  AI/메신저 없음         AI 챗봇 + Telegram       고급 AI + 다채널     │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 8. 핵심 인사이트

### 비용 구조의 80/20 법칙
- **컴퓨팅(35%) + DB(34%) + 네트워크(13%) = 전체의 82%**
- AI 챗봇과 Telegram은 전체 비용의 1% 미만으로 매우 저렴
- **NAT Gateway($33/월)**가 예상 외로 큰 고정 비용 → 최적화 필수

### 최대 비용 절감 포인트
1. **RDS 다운사이징**: db.t3.medium → db.t3.small (초기 충분) → **-$52/월**
2. **RI/Savings Plan**: 1년 약정으로 30~40% 절감 → **-$40~60/월**
3. **Fargate Spot**: 비핵심 서비스에 적용 → **-$28/월**
4. **EC2 전환**: 관리 부담 증가하지만 비용 최소화 → 총 **$180/월** 가능

### AWS vs 대안 비교 (Phase B 기준)

| 플랫폼 | 예상 월 비용 | 서버 다중화 | DB 백업 | 관리 난이도 |
|--------|------------|-----------|---------|-----------|
| **AWS (최적화)** | **$180~210** | ✅ Multi-AZ | ✅ 자동 | 중간 |
| **AWS (표준)** | $300~330 | ✅ Multi-AZ | ✅ 자동 | 중간 |
| Railway Pro | $100~200 | ✅ Replicas | ⚠️ 수동 | 낮음 |
| DigitalOcean | $120~200 | ✅ LB+Droplets | ✅ 자동 | 낮음 |

---

## Sources

- [AWS EC2 On-Demand Pricing](https://aws.amazon.com/ec2/pricing/on-demand/)
- [AWS RDS MySQL Pricing](https://aws.amazon.com/rds/mysql/pricing/)
- [AWS Fargate Pricing](https://aws.amazon.com/fargate/pricing/)
- [AWS CloudFront Pricing](https://aws.amazon.com/cloudfront/pricing/)
- [AWS ALB Pricing](https://aws.amazon.com/elasticloadbalancing/pricing/)
- [AWS NAT Gateway Pricing](https://costgoat.com/pricing/aws-nat-gateway)
- [AWS S3 Pricing](https://aws.amazon.com/s3/pricing/)
- [AWS SES Pricing](https://aws.amazon.com/ses/pricing/)
- [AWS RDS Backup Costs](https://aws.amazon.com/blogs/database/demystifying-amazon-rds-backup-storage-costs/)
- [OpenAI API Pricing](https://openai.com/api/pricing/)
- [EC2 t3 Instance Pricing (Vantage)](https://instances.vantage.sh/aws/ec2/t3.medium)
- [GPT-4o-mini Pricing](https://pricepertoken.com/pricing-page/model/openai-gpt-4o-mini)
