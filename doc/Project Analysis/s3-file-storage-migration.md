# 파일 저장소 S3 전환 가이드

**작성일**: 2026-04-22
**상태**: 코드 준비 완료 (`FILE_STORAGE_TYPE=s3` 로 활성화), 운영 서버 파일 마이그레이션만 남음.

---

## 1. 아키텍처 (확정본)

```
┌──────────────────┐    FileStorageService (interface, 4 methods)
│   FileService    │───────────┬──────────────────────────────────┐
│ (caller)         │           │                                  │
└──────────────────┘           │                                  │
                               ▼                                  ▼
                  ┌──────────────────────────┐        ┌──────────────────────────┐
                  │ LocalFileStorageService  │        │   S3FileStorageService   │
                  │ (profile: local, default)│        │  (profile: s3)           │
                  │                          │        │                          │
                  │ 1. AES-256-GCM encrypt   │        │ 1. AES-256-GCM encrypt   │
                  │    (FileEncryptionUtil)  │        │    (FileEncryptionUtil)  │
                  │ 2. 디스크 저장            │        │ 2. PutObject (+ SSE-S3)  │
                  └──────────────────────────┘        └──────────────────────────┘
```

**이중 암호화 (defense in depth)**:
- 클라이언트 측 AES-256-GCM — 앱이 들고 있는 `FILE_ENCRYPTION_KEY` 로 직접 암호화
- S3 서버 측 SSE-S3 — AWS 관리 키로 두 번째 암호화 (무료, 버킷 misconfig 방어)

**로컬·S3 키 체계 동일** — `FILE_ENCRYPTION_KEY` 하나. 마이그레이션 시 재암호화 불필요.

---

## 2. 활성화 절차

### 2.1 환경변수
```bash
FILE_STORAGE_TYPE=s3                                  # local → s3
AWS_S3_BUCKET=licensekaki-prod-files                   # 미리 생성한 버킷
AWS_S3_REGION=ap-southeast-1                           # 또는 ap-southeast-7
FILE_ENCRYPTION_KEY=<Base64 AES-256 key>               # 로컬과 동일 키
# (선택) 로컬 테스트: AWS_S3_ENDPOINT=http://localhost:4566 (LocalStack)
```

### 2.2 IAM 권한 (최소 권한 원칙)
`licensekaki-backend` IAM Role 또는 User에 다음 policy:
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::licensekaki-prod-files/*"
  }]
}
```

### 2.3 버킷 설정 권장
- Block Public Access: **ON** (모든 옵션)
- Versioning: **ON** (실수 삭제 복구)
- Default encryption: **SSE-S3 (AES-256)**
- Lifecycle: 없음 (회계·법적 이유로 영구 보관)

---

## 3. 기존 로컬 파일 마이그레이션 (운영 배포 전 1회)

`./uploads/**` 의 기존 파일을 S3로 옮기는 절차.

### 3.1 파일 양 확인
```bash
ssh ops@43.210.92.190 'du -sh /path/to/uploads && find /path/to/uploads -type f | wc -l'
```

### 3.2 백업 (안전장치)
```bash
tar czf uploads-backup-$(date +%Y%m%d).tar.gz /path/to/uploads
aws s3 cp uploads-backup-*.tar.gz s3://licensekaki-backups/ --storage-class GLACIER
```

### 3.3 마이그레이션 스크립트 (권장 구조)
```bash
# 파일 시스템 구조가 S3 키 구조와 동일하므로 단순 복사:
#   /uploads/applications/1/uuid.pdf  →  s3://bucket/applications/1/uuid.pdf
aws s3 sync /path/to/uploads s3://licensekaki-prod-files/ \
  --exclude "*.tmp" --exclude "*.partial" \
  --sse AES256
```

### 3.4 검증
```bash
# 파일 수 비교
aws s3 ls s3://licensekaki-prod-files/ --recursive | wc -l

# 랜덤 샘플 30개 → DB fileEntity.fileUrl 존재 확인
```

### 3.5 전환
1. 점검 모드 진입 (신규 업로드 차단)
2. 잔여 파일 재동기화 (`aws s3 sync --delete` 옵션 사용 금지)
3. `FILE_STORAGE_TYPE=s3` 환경변수 반영 후 애플리케이션 재시작
4. 다운로드 smoke test (샘플 10개)
5. 점검 해제

### 3.6 롤백 (문제 발생 시)
- `FILE_STORAGE_TYPE=local` 로 되돌리고 재시작 → 기존 로컬 파일로 서비스 재개
- 로컬 `./uploads/` 는 마이그레이션 후에도 **최소 1주일 유지**

---

## 4. 결정 완료 사항

| 항목 | 결정 | 이유 |
|---|---|---|
| 암호화 방식 | 클라이언트 AES-256-GCM + SSE-S3 | 로컬·S3 키 체계 통일, 이중 방어 |
| 다운로드 | 백엔드 프록시 (현재 유지) | 소유권·감사로그 제어, Presigned URL은 성능 문제 발생 시 별도 PR |
| 버전 관리 | S3 Versioning ON | 실수 삭제 복구 |
| 스토리지 클래스 | Standard | 활발히 읽는 파일들. Glacier로 이전은 별도 lifecycle |

## 5. 향후 작업 (선택)

- **Presigned URL 도입** — 백엔드 메모리 프록시 부담 해소. 대용량 파일(>50MB) 많아지면.
- **CloudFront 캐싱** — 읽기 부하 분산. 파일 수 10만 넘으면.
- **KMS 키 사용** — CMK로 전환. 감사·키 로테이션 요구되면.
- **로컬 `./uploads/` DROP** — 마이그레이션 성공·안정화 2주 후.

---

## 참조

- `blue-light-backend/src/main/java/com/bluelight/backend/api/file/FileStorageService.java` — 인터페이스
- `.../api/file/LocalFileStorageService.java` — 로컬 구현 (기본값)
- `.../api/file/S3FileStorageService.java` — S3 구현 (클라이언트 암호화 + SSE-S3)
- `.../api/file/S3Config.java` — S3Client bean (IAM Role / 환경변수 자동 감지)
- `application.yaml` §file — 설정 스키마
