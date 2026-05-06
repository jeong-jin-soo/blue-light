# EMA ELISE 양식 동등 필드 확장 — PDPA·보안 영향 분석

**대상**: 라이센스 신청·갱신 폼 (Application 도메인) 필드 확장
**기준**: Singapore PDPA (2012, 개정 2020), OWASP ASVS 4.0.3
**작성**: 2026-04-22
**연계 문서**: `kaki-concierge-security-review.md`, `service-orders-refinement-spec.md`

---

## 1. 요약

- **최상위 리스크 3**: ① MSSL·UEN·Landlord EI 등 **식별자 클러스터**가 동일 테이블에 집중되면 Sec 26D 통지 트리거가 앞당겨짐. ② LEW 전용 CoF 섹션이 **신청자 소유 테이블에 혼재**되면 접근 제어가 컬럼 단위로 파편화. ③ Declaration 4-checkbox는 **불변 법적 선언**이므로 일반 엔티티 UPDATE 경로에서 수정 가능하면 법적 증거력 소실.
- **전체 권고 방향**: (a) 식별자·CoF·Declaration을 **3개 논리 섹션**으로 분리하고 각각 접근제어·보존정책을 달리 적용, (b) MSSL·Landlord EI는 **마스킹+부분 해시 저장**, (c) Declaration은 `user_consent_logs` 패턴을 차용한 **append-only 로그**로 기록.
- JIT 원칙은 유지하되, **"지금 필요 없는 필드는 DB에도 두지 않는다"**를 기본 원칙으로. Renewal 경로는 변경 체크박스 ON 시에만 신규 값을 요구한다.

---

## 2. 필드별 PDPA 민감도 분류

**카테고리 정의**
- **식별정보(PI)**: Sec 2(1) "personal data" — 자연인 식별 가능
- **민감성 식별정보(Sensitive PI)**: 부정 사용 시 재정적·평판적 피해 큰 식별자 (MSSL, 라이선스 번호 등 — PDPA는 카테고리 구분 없으나 PDPC Advisory Guidelines에 따른 고위험)
- **업무정보(Biz)**: 법인 식별자 — Sec 4(5) "business contact information" 예외로 수집 동의 완화

| 필드 | 카테고리 | 법적 근거 | 암호화 (rest / transit) | 마스킹 | 감사로그 | 보존기간 권고 | 비고 |
|---|---|---|---|---|---|---|---|
| Installation Name | Biz | Sec 4(5) BCI 예외 | TLS only / - | - | 수정만 | 라이선스 만료+5년 | 법인명과 분리 |
| Installation Address (5분리) | PI (혼합) | Sec 13 동의 | TLS / - | - | 수정만 | 라이선스 만료+5년 | Block/Unit은 개인 위치 노출 가능 |
| Premises/Consumer Type / Retailer | Biz | Sec 4(5) | TLS / - | - | - | 라이선스 만료+5년 | 분류형 ENUM |
| **MSSL Account No** | Sensitive PI | Sec 13, Sec 24 | **AES-256-GCM (컬럼)** / TLS | **앞 12자리 마스킹 `***-**-****-X`** | 생성/수정/조회 | 라이선스 만료+5년 | SP Group 전력 계정 — 부정 사용 시 요금·전력 탈취 |
| Landlord EI Licence No | Sensitive PI | Sec 13 | **AES-256-GCM (컬럼)** / TLS | 앞 5자리 마스킹 | 생성/수정/조회 | 라이선스 만료+5년 | 제3자 라이선스 — Sec 13 제3자 동의 필요 |
| UEN | Biz | Sec 4(5) BCI | TLS / - | - | 수정만 | 라이선스 만료+5년 | 기존 수집 중 |
| Company Name | Biz | Sec 4(5) | TLS / - | - | 수정만 | 라이선스 만료+5년 | 스냅샷 존재 |
| Name of Applicant | PI | Sec 13, Sec 20 | TLS / - | - | 수정만 | 탈퇴+1년 | 스냅샷 존재 |
| Designation | Biz | Sec 4(5) | TLS / - | - | 수정만 | 라이선스 만료+5년 | 스냅샷 존재 |
| Correspondence Address (5분리) | **PI (거주지 추정)** | Sec 13, Sec 24 | TLS / **권장 AES 컬럼** | Postal만 공개, 나머지 제한 | 생성/수정 | 탈퇴+1년 | 개인 거주지 — 최고 민감 |
| Contact No for SMS | PI | Sec 13 | TLS / - | 뒤 4자리만 표시 | 생성/수정 | 탈퇴+1년 | |
| Telephone + Ext | PI (or Biz) | Sec 4(5) / Sec 13 | TLS / - | 뒤 4자리만 | 수정만 | 탈퇴+1년 | 법인 전화면 BCI |
| Fax | Biz | Sec 4(5) | TLS / - | - | - | 탈퇴+1년 | 거의 사용 안 함 — 선택 수집 |
| **CoF: Consent Date** | 업무기록 | Sec 24 | TLS / - | - | **생성/수정 전부** | 라이선스 만료+5년 | LEW 기록 |
| CoF: Inspection Interval | 업무기록 | - | TLS / - | - | 수정만 | 라이선스 만료+5년 | LEW 기록 |
| CoF: Supply Voltage | 업무기록 | - | TLS / - | - | 수정만 | 라이선스 만료+5년 | |
| CoF: Approved Load | 업무기록 | - | TLS / - | - | 수정만 | 라이선스 만료+5년 | |
| CoF: Generator | 업무기록 | - | TLS / - | - | 수정만 | 라이선스 만료+5년 | |
| **LEW Appointment Date** | 업무기록 | Sec 24 | TLS / - | - | 생성/수정 | 라이선스 만료+5년 | LEW–신청자 계약 기점 |
| **Declaration 4-checkbox** | **법적 선언 (불변)** | Sec 13 + 계약법 | TLS / - | - | **불변 append-only** | **영구(10년 이상)** | IP+UA+timestamp 동반 |
| Renewal: Company Name 변경 | 메타플래그 | - | TLS / - | - | 생성 시 스냅샷 | 라이선스 만료+5년 | 이전 값 보존 필수 |
| Renewal: Installation Address 변경 | 메타플래그 | - | TLS / - | - | 생성 시 스냅샷 | 라이선스 만료+5년 | |
| SLD 상태 3-option radio | 업무정보 | - | TLS / - | - | 수정만 | 라이선스 만료+5년 | 현재 `sld_option` 확장 |

---

## 3. 암호화 대상 선정

**원칙**: 저장소 침해 시 단독으로 금전·신원 피해를 발생시킬 수 있는 식별자만 컬럼 레벨 암호화한다. 나머지는 TLS+디스크 암호화(S3 SSE, RDS TDE)로 충분.

**컬럼 레벨 AES-256-GCM 적용 권장**:
- `mssl_account_no` — SP Group 계정 부정 사용 위험. **뒤 4자리만 평문 저장, 앞부분은 AES-256-GCM 암호문 + HMAC-SHA256 검색용 해시** 병렬 저장.
- `landlord_ei_licence_no` — 제3자 식별자. 동일 패턴 적용.
- `correspondence_address_*` — 거주지 정보. 최소 Postal 외 4분리(Block/Unit/Street/Building)는 암호화 권장.

**키 관리 방안**: 현행 `FILE_ENCRYPTION_KEY` (AES-256, Base64, `.env` 주입) 패턴을 **재사용하되 키 분리**.
- `FILE_ENCRYPTION_KEY` → 파일 바이너리 전용 (현행 유지)
- `FIELD_ENCRYPTION_KEY` → DB 컬럼 전용 (신규 추가)
- 이유: 파일 키 유출 ≠ DB 필드 키 유출. 회전 주기 상이(파일 키 회전은 재암호화 배치 수반, 필드 키는 JPA `AttributeConverter` 교체로 단순).
- 운영: AWS Secrets Manager 또는 Parameter Store(SecureString). 로컬은 기존 `.env` 방식 유지.
- 구현 패턴: `@Convert(converter = EncryptedStringConverter.class)` — `FieldEncryptionUtil`을 `FileEncryptionUtil`과 형제 클래스로 배치.

**암호화 불필요 판단**:
- UEN, Company Name, Installation Name — 공개 등록 정보(BizFile 등에서 조회 가능). 컬럼 암호화 비용 > 보호 가치.
- Telephone / Fax — 뒤 4자리 마스킹으로 충분.

**향후 플래그**: 현재는 LicenseKaki 내부 워크플로 한정이므로 DB 필드 암호화만으로 충분. **향후 EMA e-Licence 직접 제출 연동 시**에는 전송 구간에 mTLS + 서명 페이로드(JWS) 추가 필요.

---

## 4. 감사 로그 대상

현행 `audit_logs` 테이블과 `AuditAction` enum을 기준으로 확장한다.

**생성 시점 기록 (APPLICATION_CREATED 세분화)**:
- 전체 신청서 생성 시 기존 `APPLICATION_CREATED` 유지.
- 민감 필드(MSSL, Landlord EI)만 별도 `after_value`에 **마스킹된 값**으로 기록. 평문을 `after_value`에 기록하면 audit 테이블 자체가 위반 경로가 된다.

**수정 시점 기록 (APPLICATION_UPDATED 신규 서브액션)**:
- `MSSL_ACCOUNT_UPDATED`, `LANDLORD_EI_UPDATED`, `CORRESPONDENCE_ADDRESS_UPDATED` — `before_value`/`after_value`는 마스킹 저장.
- `INSTALLATION_ADDRESS_UPDATED` — Renewal 변경 체크박스 경로에서 트리거.
- `COMPANY_NAME_UPDATED` — 동일.
- `CERTIFICATE_OF_FITNESS_UPDATED` (LEW 전용) — 누가, 언제, 어떤 CoF 필드 바꿨는지 기록. 감사 책임은 LEW.
- `LEW_APPOINTMENT_DATE_SET` — 일회성 중요 이벤트.

**조회 시점 기록 (신규, DATA_PROTECTION 카테고리)**:
- `APPLICATION_VIEWED_BY_ADMIN` — ADMIN/SYSTEM_ADMIN이 타인 신청서 열람 시 (본인 신청자는 제외).
- `MSSL_UNMASKED_VIEW` — 마스킹 해제 뷰 접근 (LEW 실무 작업 중 전체 번호 필요 시). Rate limit + 이유 입력 요구.

**불변 선언 로그 (append-only)**:
- Declaration 4-checkbox는 `user_consent_logs` 테이블에 `consent_type = 'APPLICATION_DECLARATION_V1'`로 4건 append. `source_context = 'APPLICATION_SUBMIT'`, `document_version = '2026-04-declaration-v1'`. IP + UA 동반 기록(이미 컬럼 존재).
- 대안: 신규 테이블 `application_declaration_logs` 신설. 사유는 user_consent_logs는 신청 FK가 없고, 증거 인양 시 신청 단위 조회가 더 자연스럽기 때문. **권고는 신규 테이블**.

---

## 5. Access Control 매트릭스

(R=Read, W=Write, - = 불가, M=마스킹된 값만 R)

| 필드 그룹 | APPLICANT (본인) | LEW (배정된 신청) | ADMIN | SYSTEM_ADMIN |
|---|---|---|---|---|
| Installation Name/Address | R/W (제출 전) | R | R | R |
| MSSL Account No | R(M) / W (최초 1회) | R(full) / - | R(M) | R(M) + 감사 로그 경유 full |
| Landlord EI Licence No | R(M) / W | R(full) | R(M) | R(M) |
| Correspondence Address | R/W | R | R | R |
| Contact No for SMS / Tel | R/W | R | R | R |
| UEN / Company Name | R / W (변경 Renewal만) | R | R | R |
| Certificate of Fitness 섹션 | R (제출 후) | **R/W** | R | R |
| LEW Appointment Date | R | R/W | R | R |
| Declaration 4-checkbox | R/W (제출 시) | R | R | R |
| Renewal 변경 체크박스 | R/W | R | R | R |
| SLD 상태 radio | R/W | R/W | R | R |

**Spring Security 구현**: 기존 `@PreAuthorize` 패턴 유지하되, **필드 단위 마스킹은 DTO Response 변환 레이어에서 역할 분기**. 엔티티 직접 노출 금지. CoF 필드는 별도 `CertificateOfFitnessRequest` DTO로 분리하여 LEW 전용 엔드포인트(`PATCH /api/lew/applications/{id}/cof`)에서만 수정 가능.

**현행 `SecurityConfig`의 H-4 주의**: `/api/admin/**` 경로가 LEW에게도 허용되는 현재 설정(kaki-concierge-security-review §H-4)을 먼저 바로잡아야 한다. EMA 필드 추가 시 CoF 엔드포인트가 `/api/admin/**` 아래로 들어가면 LEW가 임의 신청서의 CoF를 수정할 수 있다 — **`/api/lew/**` 별도 경로 권고**.

---

## 6. JIT 적용에서 발생하는 보안 이슈

1. **"부분 채워진 신청서"의 권한 경계** — 신청자가 기본 섹션만 제출 → LEW가 CoF 섹션 추가 → 이 시점 신청자가 다시 수정 진입하면 CoF를 볼 수 있어야 하는가? **정책**: 신청자는 CoF를 R만, W 불가. Application status가 `PENDING_REVIEW` 이후에는 신청자 측 PATCH가 허용 필드를 화이트리스트로 제한해야 함. 현재 `UpdateApplicationRequest`는 개별 필드별 null-skip 병합을 쓰므로 **CoF 필드는 DTO 자체에 포함하지 않는 것이 안전**.

2. **Declaration 타임스탬프 무결성** — JIT로 신청서 여러 번 저장하면서 최종 제출 시점에 Declaration을 찍는 경우, 저장 시점의 폼 해시 ≠ 제출 시점의 폼 해시가 되면 "무엇에 동의했는지"가 흐려진다. **권고**: Declaration 체크 시 **그 시점 Application 스냅샷 해시(SHA-256)**를 `declaration_log.form_snapshot_hash`로 함께 저장.

3. **LEW 필드가 있는 상태에서 신청자 탈퇴 요청** — Sec 25 접근·정정권 vs CoF의 LEW 소유권 충돌. **정책**: 신청자 탈퇴 시 PI 필드는 익명화하되 **CoF 섹션은 LEW 업무 기록으로 유지**(LEW 책임 이력). 감사 로그에 `ACCOUNT_DELETED_WITH_LEW_RECORDS_RETAINED` 기록.

4. **"변경 체크박스 OFF 상태에서 값 편집"** — 프론트에서 체크박스 OFF인데 Company Name 필드가 수정되어 요청이 오는 경우. 서버는 체크박스 플래그를 **반드시 신뢰하지 말고** 이전 값과 비교해야 한다. 변경 플래그 ON + 실제 값 변경 여부 둘 다 확인.

5. **MSSL 입력 폼의 BFLA** — `/api/applications/{id}`에 타인 application_seq 넣어 MSSL 변경 시도. 현행 BaseEntity `created_by` 체크만으로는 부족하고 `application.user_seq == currentUser.user_seq` 검증이 서비스 계층에 반드시 있어야 함.

---

## 7. Breach 시 통지 범위 (Sec 26D)

**현행 통지 트리거**:
- PDPA Sec 26D(1): **유해 결과 가능성 있는 침해** 즉시 통지 (PDPC 72h, 영향 개인 즉시)
- Sec 26D(5): **500명 이상** 영향 시 자동 통지 의무

**신규 필드 추가 시 리스크 증가**:

| 필드 추가 전 | 필드 추가 후 |
|---|---|
| 이메일/이름/법인 식별 정보 중심 — 피싱 스피어 위험 중간 | **MSSL Account** 추가 → SP Group 전력 계정 부정 사용(요금 연체 발생, 타인 명의 전력 소비) 가능 — Sec 26D(1) "significant harm" 트리거 가능성 상승 |
| 거주지 정보 제한적 | **Correspondence Address 5분리** 추가 → 물리적 스토킹·도난 위험 증가 |
| Landlord 정보 없음 | **Landlord EI** 추가 → 제3자(임대인) Sec 13 동의 체인 끊기면 수집 단계에서 이미 Sec 13 위반 |

**결론**: 현재 PDPC Advisory Guidelines "Significant Harm" 정의상 MSSL + Address 조합은 **500명 미달이어도 즉시 통지 대상**으로 해석될 여지 큼. 따라서 **Breach 대응 Playbook에 "MSSL 또는 Address 필드가 영향 범위에 있으면 Sec 26D(1) 경로로 즉시 통지"** 조항을 추가해야 한다. 현행 `data_breach_notifications` 테이블의 `data_types_affected` 컬럼을 활용해 해당 필드 포함 여부를 태깅.

---

## 8. Renewal 시 변경 체크박스 처리

**원칙**: 신청은 **immutable snapshot** + 사용자 프로필은 **mutable current**로 분리. 기존 `applications` 테이블의 LOA 스냅샷 패턴(`applicant_name_snapshot`, `company_name_snapshot`, `uen_snapshot`, `designation_snapshot`, 모두 `updatable=false`) 을 확장하여 재사용한다.

**Renewal 흐름**:
1. 사용자가 Renewal 신청 시작 → 시스템이 직전 Application의 스냅샷에서 prefill.
2. "Company Name 변경" 체크박스 ON → 새 값 입력 → 제출 시 **users.company_name** 업데이트 + 새 `applications` 행에 새 스냅샷 기록.
3. 변경 체크박스 OFF → users.company_name 유지, 새 Application 스냅샷도 이전 값 복사.
4. 양쪽 경로 모두 **이전 Application 행은 수정 금지** (JPA `@Column(updatable=false)` + DB 트리거 권고).

**추가 컬럼 권고**:
- `applications.installation_address_snapshot` (JSON) — 5분리 구조 보존
- `applications.renewal_company_name_changed BOOLEAN` — 변경 체크박스 플래그 보존
- `applications.renewal_address_changed BOOLEAN`
- 기존 변경 이력은 `audit_logs.before_value/after_value`로 충분. 별도 이력 테이블 불필요.

**증거 사슬**: 감사 시 "이 라이선스의 과거 발급 시점 법인명은?" → `applications.company_name_snapshot`만 조회하면 됨. 현행 users 테이블만 봤을 때 과거 값 소실되는 문제를 스냅샷이 방지.

---

## 9. 구현 시 권고 체크리스트

- **① MSSL 저장**: 앞 12자리는 AES-256-GCM 암호문 + HMAC-SHA256(검색용 고정 해시) 두 컬럼에 저장, `mssl_account_last4` 평문 1컬럼(표시·검색 UX). 화면은 항상 `***-**-****-XXXX`.
- **② Landlord EI**: 동일 패턴. 존재 자체가 "임차 관계" 민감 정보이므로 **수집 시점에 별도 동의 체크박스** (Sec 13(1)(a)).
- **③ Declaration**: `application_declaration_logs` 신규 테이블(append-only, `updated_at` 없음). 4개 체크 각각 1행 기록 + IP + UA + user_seq + application_seq + form_snapshot_hash + timestamp. `@Immutable` + `@SQLDelete` 미적용.
- **④ CoF 섹션**: 별도 엔티티 `CertificateOfFitness` 1:1 매핑 + `/api/lew/applications/{id}/cof` 전용 엔드포인트 + `@PreAuthorize("hasRole('LEW') and @appSec.isAssignedLew(#id, authentication)")`.
- **⑤ Correspondence Address**: 5분리 컬럼 중 Block/Unit/Street/Building은 `AttributeConverter`로 필드 암호화. Postal Code는 평문(지역 통계·배송 UX).
- **⑥ Renewal 변경 체크박스**: 서버 검증 — `applicant.companyName != previousSnapshot.companyName` 이면서 `renewalCompanyNameChanged == false` 인 케이스는 400 Bad Request.
- **⑦ 감사 로그 확장**: `AuditAction`에 `MSSL_UNMASKED_VIEW`, `CERTIFICATE_OF_FITNESS_UPDATED`, `APPLICATION_DECLARATION_RECORDED` 등 추가. 기존 enum 패턴 유지.
- **⑧ JIT 필드 검증**: `UpdateApplicationRequest` DTO에서 CoF 필드 완전 제외. LEW가 동일 PATCH 경로로 CoF 수정하는 실수 차단.
- **⑨ 응답 DTO 분리**: `ApplicationResponse` (신청자용, 마스킹), `LewApplicationResponse` (LEW용, 전체), `AdminApplicationResponse` (관리자용, 마스킹 + 감사 표시). 엔티티 직접 리턴 금지.
- **⑩ Breach Playbook**: `data_breach_notifications.data_types_affected`에 신규 필드 키워드 등록 (MSSL, LANDLORD_EI, CORRESPONDENCE_ADDRESS). 해당 필드 포함 시 Sec 26D(1) 경로 강제.

---

## 부록: 향후 EMA 직접 제출 연동 시 추가 과제 (플래그)

현재 범위 밖이나 미리 flag:
- **EMA API 전송 시 필드 매핑**: MSSL/Landlord EI/Declaration 모두 평문으로 EMA 시스템에 전송될 것이므로, 전송 구간 **mTLS + JWS 서명 페이로드** 필요.
- **EMA 응답의 라이선스 번호 수신·저장**: 발급 번호가 추가 식별자로 편입됨 — 민감 PI 대상 확대.
- **Cross-border 여부 확인**: EMA가 싱가포르 내 시스템이면 Sec 26A(1) 이전 제한 비해당, 그러나 재난복구 리전이 해외라면 Sec 26A(1) 준수 필요.
- **Sec 13 재동의**: EMA 제3자 제공 동의는 현행 가입 동의에 없을 가능성 — 약관 개정 + 기존 사용자 재동의 플로우 필요.
