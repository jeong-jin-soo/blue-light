# LicenseKaki 운영서버 구축 가이드

## Architecture

```
            Internet
               |
         [Route 53 DNS]
         licensekaki.com
               |
        [ACM Certificate]
               |
    [Application Load Balancer]
     HTTPS:443 → HTTP:80
          /          \
   [EC2 Instance A]  [EC2 Instance B]
   t4g.small (ARM)   t4g.small (ARM)
   Docker Compose     Docker Compose
          \                /
      [RDS MySQL 8.0]   [S3 Bucket]
      bluelight_prod    licensekaki-prod-files
```

## 사전 준비

- AWS 계정 (ap-southeast-7 리전)
- licensekaki.com 도메인 (DNS 관리 권한)
- GitHub repository의 production environment secrets 접근 권한

---

## Step 1: S3 버킷 생성

```bash
aws s3 mb s3://licensekaki-prod-files --region ap-southeast-7

# 퍼블릭 접근 차단
aws s3api put-public-access-block \
  --bucket licensekaki-prod-files \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

# 버전 관리 활성화
aws s3api put-bucket-versioning \
  --bucket licensekaki-prod-files \
  --versioning-configuration Status=Enabled

# 서버 측 암호화 (SSE-S3)
aws s3api put-bucket-encryption \
  --bucket licensekaki-prod-files \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

## Step 2: IAM 역할 생성

**EC2 인스턴스용 IAM 역할 (bluelight-prod-ec2-role)**:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ECRReadOnly",
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchCheckLayerAvailability"
      ],
      "Resource": "*"
    },
    {
      "Sid": "S3ProdBucket",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::licensekaki-prod-files",
        "arn:aws:s3:::licensekaki-prod-files/*"
      ]
    }
  ]
}
```

AWS Console에서:
1. IAM → Roles → Create Role
2. Trusted entity: EC2
3. 위 정책 JSON을 Custom Policy로 추가
4. Role name: `bluelight-prod-ec2-role`

## Step 3: 보안 그룹 생성

### ALB 보안 그룹 (bluelight-prod-alb-sg)
| 방향 | 포트 | 소스 |
|------|------|------|
| Inbound | 443 (HTTPS) | 0.0.0.0/0 |
| Inbound | 80 (HTTP) | 0.0.0.0/0 |
| Outbound | All | All |

### EC2 보안 그룹 (bluelight-prod-ec2-sg)
| 방향 | 포트 | 소스 |
|------|------|------|
| Inbound | 80 | bluelight-prod-alb-sg |
| Inbound | 22 (SSH) | 관리 IP |
| Outbound | All | All |

## Step 4: EC2 인스턴스 생성 (2대)

1. **AMI**: Amazon Linux 2023 (ARM64)
2. **Instance type**: t4g.small (2 vCPU, 2GB RAM)
3. **IAM role**: bluelight-prod-ec2-role
4. **Security group**: bluelight-prod-ec2-sg
5. **Storage**: 20GB gp3
6. **Key pair**: 기존 bluelight-key.pem 또는 신규 생성

### 각 인스턴스 초기 설정 (SSH 접속 후)

```bash
# Docker 설치
sudo dnf update -y
sudo dnf install -y docker
sudo systemctl enable docker
sudo systemctl start docker
sudo usermod -aG docker ec2-user

# Docker Compose 설치
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose

# AWS CLI (이미 설치됨, ECR 로그인용)
aws --version

# 배포 디렉토리 생성
mkdir -p ~/bluelight

# 재로그인 (docker 그룹 적용)
exit
```

## Step 5: ALB 생성

1. **EC2 → Load Balancers → Create Application Load Balancer**
2. Name: `bluelight-prod-alb`
3. Scheme: Internet-facing
4. IP address type: IPv4
5. Availability Zones: 2개 이상 선택
6. Security group: bluelight-prod-alb-sg

### Target Group 생성
- Name: `bluelight-prod-tg`
- Protocol: HTTP, Port: 80
- Target type: Instance
- Health check path: `/health`
- Health check interval: 30s
- Healthy threshold: 2
- Unhealthy threshold: 3
- **등록**: 두 EC2 인스턴스 추가

### ALB 유휴 타임아웃 설정
- **Attributes → Idle timeout**: `300`초 (SSE 지원)

## Step 6: ACM 인증서

1. **ACM → Request certificate**
2. Domain names:
   - `licensekaki.com`
   - `*.licensekaki.com`
3. Validation method: DNS
4. DNS에 검증 CNAME 레코드 추가
5. 검증 완료 후 ALB에 연결

### ALB Listener 설정
- **HTTPS:443** → Forward to `bluelight-prod-tg` (인증서 선택)
- **HTTP:80** → Redirect to HTTPS:443 (301)

## Step 7: DB 분리 (같은 RDS, 별도 DB/계정)

RDS에 접속하여:

```sql
-- 운영 전용 DB
CREATE DATABASE bluelight_prod CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 운영 전용 계정
CREATE USER 'prod_user'@'%' IDENTIFIED BY '<강력한-비밀번호>';
GRANT ALL PRIVILEGES ON bluelight_prod.* TO 'prod_user'@'%';

-- 개발 계정의 prod DB 접근 차단
REVOKE ALL PRIVILEGES ON bluelight_prod.* FROM 'user'@'%';

FLUSH PRIVILEGES;
```

## Step 8: GitHub Secrets 설정

GitHub → Settings → Environments → `production` → Secrets:

| Secret | 값 |
|--------|-----|
| `PROD_SERVER_HOST_1` | EC2 Instance A의 공인 IP |
| `PROD_SERVER_HOST_2` | EC2 Instance B의 공인 IP |
| `PROD_SERVER_USER` | `ec2-user` |
| `PROD_SERVER_SSH_KEY` | SSH 프라이빗 키 |
| `DB_URL` | `jdbc:mysql://<RDS엔드포인트>:3306/bluelight_prod?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Singapore` |
| `DB_USERNAME` | `prod_user` |
| `DB_PASSWORD` | (위에서 설정한 비밀번호) |
| `JWT_SECRET` | (새로 생성: `openssl rand -base64 64`) |
| `CORS_ALLOWED_ORIGINS` | `https://licensekaki.com,https://www.licensekaki.com` |
| `AWS_S3_BUCKET` | `licensekaki-prod-files` |
| `AWS_S3_REGION` | `ap-southeast-7` |
| `SLD_AGENT_SERVICE_KEY` | (새로 생성: `openssl rand -hex 32`) |
| `PASSWORD_RESET_BASE_URL` | `https://licensekaki.com` |
| `MAIL_*` | (기존 메일 설정과 동일) |

## Step 9: DNS 전환

### Route 53 사용 시
1. `licensekaki.com` → A 레코드 (Alias) → ALB DNS name
2. `www.licensekaki.com` → A 레코드 (Alias) → ALB DNS name
3. `dev.licensekaki.com` → 기존 dev EC2 IP 유지

### 외부 DNS 사용 시
1. `www.licensekaki.com` → CNAME → ALB DNS name
2. `licensekaki.com` → A 레코드 → ALB IP (또는 레지스트라 리다이렉트)

## Step 10: 첫 배포

```bash
# 로컬에서
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions `deploy-prod.yml`이 트리거되어:
1. Docker 이미지 빌드 (ARM64) → ECR 푸시
2. Instance A 배포 → 헬스체크 대기
3. Instance B 배포 → 헬스체크 대기

## 검증 체크리스트

- [ ] `curl https://licensekaki.com/health` → `ok`
- [ ] `curl https://licensekaki.com/api/actuator/health` → `{"status":"UP"}`
- [ ] ALB Target Group → 2 Healthy instances
- [ ] 로그인 테스트 (admin@bluelight.sg / admin1234)
- [ ] SLD 생성 테스트
- [ ] 파일 업로드/다운로드 테스트 (S3)
- [ ] 비밀번호 재설정 이메일 테스트

## 비용 모니터링

AWS Cost Explorer에서 `bluelight-prod` 태그로 필터:
- 예상: ~$77/월
- 알림: Budget → $100 초과 시 이메일

## 향후 확장 경로

| 시점 | 조치 |
|------|------|
| 메모리 부족 | t4g.small → t4g.medium ($30→$60, 총 ~$107) |
| DB 부하 증가 | RDS 별도 인스턴스 분리 (+$15) |
| DB 고가용성 | RDS Multi-AZ 활성화 (+$15) |
| 글로벌 접근 | CloudFront CDN 추가 (~$5) |
| 보안 강화 | WAF 추가 (~$10) |
