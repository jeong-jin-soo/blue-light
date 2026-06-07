# LicenseKaki Notification Copy Templates

> **Date**: 2026-05-01
> **Position**: Operational copy sheet — fills in the actual subject lines, headlines, body paragraphs, CTA labels, SMS strings and WhatsApp template payloads for every alert defined in the master catalog.
> **Source of truth (meta)**: [`notification-catalog.en.md`](./notification-catalog.en.md) — role × event × channel matrix
> **Source of truth (strategy)**: [`notification-strategy.md`](./notification-strategy.md) (Korean)
> **Source of truth (AC)**: [`notification-requirements.md`](./notification-requirements.md) (Korean)
> **Audience**: Backend developers (template seeding), Frontend (in-app deep-links), Translators (i18n base), Legal (PDPA / Spam Control Act review), Customer Support (knowledge base).
> **Status**: Draft v1.0 — all 97 catalog IDs covered. Reviewer-decision items consolidated in §11.
> **Singapore English conventions**: `licence` (noun), `license` (verb), `colour`, `centre`, dates as `25 Apr 2026`, time `17:00 SGT`, currency `SGD 350`.

---

## §0. How to use this document

Each alert in §2–§7 is rendered as a **card** with the following fields:

| Field | Meaning |
|---|---|
| **Category** | One of `SECURITY / STATUS / PAYMENT / REMINDER / VISIT / REASSURANCE / EXPIRY / MARKETING / FEEDBACK / OPS` (drives Preference Center toggles + Quiet Hours rules; see catalog §10) |
| **Severity** | Critical (★) / Important (●) / Informational (○) / Marketing (M) — drives opt-out enforcement and Quiet Hours bypass |
| **Recipient** | Self / LEW / Admin / SLD Manager / Concierge Manager / System Admin |
| **Channels** | Subset of E (Email), I (In-app), S (SMS), W (WhatsApp). Cross-reference with catalog "Channel" column |
| **Trigger** | Backend event / scheduler / state transition that fires the notification |
| **Reference** | `ReferenceType#publicCode` used for deep-linking and idempotency |
| **Variables** | `{{token}}` placeholders consumed by the template — see §10 for the master variable index |

### Variable notation

- All placeholders use double curly braces: `{{publicCode}}`, `{{applicantName}}`.
- A trailing `?` (e.g. `{{managerNote?}}`) means "render only if non-empty"; the surrounding sentence is omitted otherwise.
- `{{ctaUrl}}` is always the absolute URL for the primary CTA. In SMS this is shortened to `lk.sg/<x>/<code>`.
- `{{deadline}}` is rendered as `25 Apr 2026, 17:00 SGT` unless noted otherwise.
- `{{amount}}` is rendered as `SGD 350.00` (always 2 decimal places, ISO currency prefix).

### i18n key naming convention

Template strings are stored in the `notification_template` table (see CLAUDE.md §설계 원칙 — single source of truth, no hardcoding). Keys follow:

```
notif.<catalog-id>.<channel>.<part>
notif.A-17.email.subject
notif.A-17.email.headline
notif.A-17.email.body
notif.A-17.email.cta_label
notif.A-17.inapp.title
notif.A-17.inapp.body
notif.A-17.sms.body
notif.A-17.whatsapp.template_name
notif.A-17.whatsapp.body_var_1     # Meta WhatsApp positional vars
notif.A-17.whatsapp.button_1_label
```

Locale suffix is appended at the column level (`body_en`, `body_ko`, `body_zh-Hans`). **Base language for v1.0 is English (`en-SG`)**. Korean / Chinese (Simplified) translations are placeholders in this document — to be commissioned after the English copy is sign-off-ready (see §11 item 4).

---

## §1. Common building blocks (reused everywhere)

### 1.1 Email header block (HTML pseudocode)

```
+--------------------------------------------------------+
|  [LicenseKaki logo · 32px height, dark teal]           |
|  LicenseKaki                                           |
|  Singapore Electrical Installation Licence Platform    |
+--------------------------------------------------------+
```

- Logo + wordmark on left, optional page-title slot on right.
- Background: `#FFFFFF`. Border-bottom: `1px solid #E5E7EB`.
- All emails carry this header — no exceptions.

### 1.2 Email footer block (HTML pseudocode)

```
+--------------------------------------------------------+
|  This is a transactional email from LicenseKaki.       |
|  You are receiving it because you {{footerReason}}.    |
|                                                        |
|  Anti-phishing reminder:                               |
|  • Our only sender domain is @licensekaki.sg.          |
|  • We will never ask you to reply with your password,  |
|    OTP, or PayNow PIN.                                 |
|  • Verify any link by hovering before you click.       |
|                                                        |
|  Manage email preferences: {{preferenceCenterUrl}}     |
|  (Critical security and payment notices cannot be      |
|   disabled — required by Singapore PDPA §13 and our    |
|   terms of service.)                                   |
|                                                        |
|  LicenseKaki Pte Ltd · {{companyAddress}}              |
|  PDPA enquiries: dpo@licensekaki.sg                    |
+--------------------------------------------------------+
```

- `{{footerReason}}` is set per category — see §9.
- `{{preferenceCenterUrl}}` is `https://app.licensekaki.sg/account/preferences`.
- `{{companyAddress}}` is loaded from `system_settings.company_address` (no hardcoding).
- Marketing emails get an additional `[ADV]` subject prefix and STOP instructions (§9).

### 1.3 SMS prefix and length budget

- **Prefix**: every SMS body starts with `[LicenseKaki] ` (14 chars including trailing space).
- **Hard limit**: 160 GSM-7 characters total (1 SMS segment). Templates below are budgeted accordingly.
- **Short URL host**: `lk.sg`. Path encodes reference type + public code, e.g. `lk.sg/p/A-2026-1234`.

### 1.4 WhatsApp template naming convention

```
licensekaki_<role>_<event>_<lang>
```

Examples:

- `licensekaki_applicant_payment_required_en`
- `licensekaki_applicant_visit_d1_en`
- `licensekaki_concierge_loa_sign_en`

Each template must be pre-approved in Meta WhatsApp Business Manager. Variables are positional (`{{1}}`, `{{2}}`, ...) per Meta's syntax — but in this document we use named variables for readability and supply the positional mapping in the WhatsApp section of each card.

### 1.5 In-app metadata schema

Every in-app notification creates a `Notification` row with:

| Column | Source |
|---|---|
| `recipientSeq` | resolved from role + reference |
| `type` | `NotificationType` enum value (one per card) |
| `title` | `notif.<id>.inapp.title` |
| `message` | `notif.<id>.inapp.body` (≤100 char snippet) |
| `referenceType` | e.g. `APPLICATION`, `CONCIERGE_REQUEST`, `SLD_ORDER`, `EXPIRED_LICENCE_ORDER`, `LEW_USER`, `LOA` |
| `referenceId` | numeric seq |
| `categoryKey` | one of `SECURITY / STATUS / PAYMENT / REMINDER / VISIT / REASSURANCE / EXPIRY / MARKETING / FEEDBACK / OPS` |
| `severityKey` | one of `CRITICAL / IMPORTANT / INFORMATIONAL / MARKETING` |
| `deepLinkPath` | e.g. `/applications/{{publicCode}}/payment` |

### 1.6 Tone north stars (apply to every card)

1. Lead with what's good or actionable, not what's wrong.
2. State the action in the first 12 words.
3. One primary CTA per message. Secondary link is always "View dashboard / View {{thing}}".
4. Never include sensitive data (name, address, licence #, amount) in **email subjects** or **SMS bodies**.
5. Always reference the public code (`{{publicCode}}`, e.g. `A-2026-1234`, `C-2026-0078`, `S-2026-0012`).
6. Sign off without a fake person name. Use `The LicenseKaki Team` only on welcome/feedback mails. Transactional mails go unsigned.

---

## §2. APPLICANT — 54 cards

### 2.1 Account & Security

---

#### A-01 — Email verification after sign-up

| Field | Value |
|---|---|
| Category | SECURITY |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | `AuthService.signup` success (status `PENDING_VERIFICATION`) |
| Reference | `User#{userSeq}` |
| Variables | `{{applicantName}}`, `{{verificationUrl}}`, `{{expiresAtDisplay}}` |

**Email**
- **Subject**: `[LicenseKaki] Verify your email to activate your account`
- **Pre-header**: `Confirm your email within 24 hours to start applying for your installation licence.`
- **Headline**: `Welcome to LicenseKaki. Please confirm your email.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Thank you for signing up with LicenseKaki — Singapore's all-in-one electrical installation licence platform.
  >
  > To activate your account, please confirm your email address. This link is valid until **{{expiresAtDisplay}}**.
- **Primary CTA**: `Verify my email` → `{{verificationUrl}}`
- **Secondary**: (none — single-CTA principle)
- **Footer reason**: `you signed up with this email address.`
- **Anti-phishing**: standard footer block (§1.2)
- **Opt-out**: SECURITY — opt-out unavailable

**In-app / SMS / WhatsApp**: N/A (user is not yet logged in; phone may not be on file).

**Edge cases**
- If user requests resend within 5 minutes, A-07 supersedes; do not send A-01 twice.
- Subject must NOT include the user's name (PDPA — public inbox safety).

---

#### A-02 — Welcome / onboarding

| Field | Value |
|---|---|
| Category | REASSURANCE |
| Severity | Informational (○) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | Email verification success (`AuthService.verifyEmail`) |
| Reference | `User#{userSeq}` |
| Variables | `{{applicantName}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] You're all set — start your first application`
- **Pre-header**: `Your account is active. Here's how to apply for an installation licence in 5 minutes.`
- **Headline**: `Welcome aboard, {{applicantName}}.`
- **Body**:
  > Your LicenseKaki account is active. Here's what you can do next:
  >
  > - **Submit a new application** — upload your floor plan, choose your kVA tier, and we'll match you with a Licensed Electrical Worker (LEW) within 24–72 hours.
  > - **Track progress** in your dashboard at every step.
  > - **Manage notifications** in your preference centre any time.
  >
  > Need a hand? Our support team is at support@licensekaki.sg.
- **Primary CTA**: `Start a new application` → `{{ctaUrl}}` (`/applications/new`)
- **Secondary**: `View dashboard` → `/dashboard`
- **Footer reason**: `your LicenseKaki account is now active.`
- **Sign-off**: `The LicenseKaki Team`
- **Opt-out**: REASSURANCE — can be disabled in Preference Centre

**In-app**
- **Title**: `Welcome to LicenseKaki`
- **Body**: `Your account is active. Start your first application in a few minutes.`
- **Action label**: `Start application`
- **Deep-link**: `/applications/new`

**SMS / WhatsApp**: N/A (informational only).

---

#### A-03 — Password reset link

| Field | Value |
|---|---|
| Category | SECURITY |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | `POST /api/auth/forgot-password` |
| Reference | `User#{userSeq}` |
| Variables | `{{applicantName}}`, `{{resetUrl}}`, `{{expiresAtDisplay}}`, `{{requestIp}}`, `{{requestUserAgent}}` |

**Email**
- **Subject**: `[LicenseKaki] Reset your password`
- **Pre-header**: `A reset link valid for 1 hour was just requested.`
- **Headline**: `We received a request to reset your password.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Someone — hopefully you — asked to reset the password for your LicenseKaki account.
  >
  > This link is valid until **{{expiresAtDisplay}}**:
  >
  > **Request details** (for your security review):
  > - Time: {{expiresAtDisplay}} (request received approximately 1 hour earlier)
  > - IP address: {{requestIp}}
  > - Device: {{requestUserAgent}}
  >
  > **If this wasn't you**, ignore this email — your password stays unchanged. Then, please change your password as a precaution and contact support@licensekaki.sg.
- **Primary CTA**: `Reset my password` → `{{resetUrl}}`
- **Secondary**: (none — security single-CTA)
- **Footer reason**: `a password reset was requested for this account.`
- **Anti-phishing**: standard footer block
- **Opt-out**: SECURITY — opt-out unavailable

**In-app / SMS / WhatsApp**: N/A (user is locked out of the platform).

**Edge cases**
- Subject must NOT include user's name.
- Rate limit: max 3 reset emails per hour per email address (silently squash duplicates beyond that).

---

#### A-04 — Password change successful

| Field | Value |
|---|---|
| Category | SECURITY |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | `AuthService.resetPassword` success |
| Reference | `User#{userSeq}` |
| Variables | `{{applicantName}}`, `{{changedAtDisplay}}`, `{{requestIp}}`, `{{supportUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your password was just changed`
- **Pre-header**: `If this wasn't you, secure your account immediately.`
- **Headline**: `Your password was successfully changed.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your LicenseKaki password was changed on **{{changedAtDisplay}}** from IP address {{requestIp}}.
  >
  > **If this was you**, no further action is needed.
  >
  > **If this wasn't you**, your account may be compromised. Please:
  > 1. Reset your password again immediately.
  > 2. Review recent activity in your account.
  > 3. Email support@licensekaki.sg with the subject "Suspected account compromise".
- **Primary CTA**: `Secure my account` → `{{supportUrl}}` (`/account/security`)
- **Secondary**: (none)
- **Footer reason**: `your account password was just changed.`
- **Anti-phishing**: standard footer block
- **Opt-out**: SECURITY — opt-out unavailable

**Edge cases**
- This alert is mandatory regardless of preferences (PDPA / MAS Notice 661 anti-phishing baseline).
- Send within 60 seconds of password change.

---

#### A-05 — New device / IP login detected

| Field | Value |
|---|---|
| Category | SECURITY |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | Login event with novel UA fingerprint |
| Reference | `User#{userSeq}` |
| Variables | `{{applicantName}}`, `{{loginAtDisplay}}`, `{{requestIp}}`, `{{requestUserAgent}}`, `{{cityCountryGuess}}`, `{{sessionsUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] New sign-in to your account`
- **Pre-header**: `If this wasn't you, please review your active sessions.`
- **Headline**: `A new device just signed in.`
- **Body**:
  > Hello {{applicantName}},
  >
  > We noticed a sign-in we hadn't seen before. Just confirming this was you:
  >
  > - **Time**: {{loginAtDisplay}}
  > - **Location (approximate)**: {{cityCountryGuess}}
  > - **Device**: {{requestUserAgent}}
  > - **IP**: {{requestIp}}
  >
  > **If this was you**, you can ignore this email.
  >
  > **If you don't recognise this sign-in**, sign out of the unknown session and reset your password.
- **Primary CTA**: `Review active sessions` → `{{sessionsUrl}}` (`/security/sessions`)
- **Footer reason**: `a new device signed in to your account.`
- **Opt-out**: SECURITY — opt-out unavailable

---

#### A-06 — Inactive account activation link

| Field | Value |
|---|---|
| Category | SECURITY |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | Login attempt on `INACTIVE` account (`LoginActivationService`) |
| Reference | `User#{userSeq}` |
| Variables | `{{applicantName}}`, `{{activationUrl}}`, `{{expiresAtDisplay}}` |

**Email**
- **Subject**: `[LicenseKaki] Activate your account to sign in`
- **Pre-header**: `Your account is dormant. Use the secure link to reactivate it.`
- **Headline**: `Welcome back. Please reactivate your account.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your LicenseKaki account has been inactive for some time. To protect your data we deactivated it temporarily.
  >
  > To reactivate, click the secure link below — valid until **{{expiresAtDisplay}}**. You'll also be asked to re-confirm our PDPA terms during reactivation.
- **Primary CTA**: `Activate my account` → `{{activationUrl}}`
- **Footer reason**: `you attempted to sign in to a dormant account.`
- **Opt-out**: SECURITY — opt-out unavailable

---

#### A-07 — Resend verification email

| Field | Value |
|---|---|
| Category | SECURITY |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | User clicks "Resend verification" |
| Reference | `User#{userSeq}` |
| Variables | `{{applicantName}}`, `{{verificationUrl}}`, `{{expiresAtDisplay}}` |

**Email**
- **Subject**: `[LicenseKaki] Here's a new verification link`
- **Pre-header**: `Confirm your email within 24 hours to activate your account.`
- **Headline**: `Your new verification link is ready.`
- **Body**:
  > Hello {{applicantName}},
  >
  > As requested, here is a fresh email verification link. Use it before **{{expiresAtDisplay}}** to activate your account.
- **Primary CTA**: `Verify my email` → `{{verificationUrl}}`
- **Footer reason**: `you requested a new verification link.`
- **Opt-out**: SECURITY — opt-out unavailable

---

### 2.2 Application Main Flow

---

#### A-08 — Application submitted (receipt)

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `ApplicationService.createApplication` success |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{installationAddressMasked}}`, `{{kvaLabel}}`, `{{reviewWindowText}}`, `{{ctaUrl}}`, `{{receiptPdfUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Application received · #{{publicCode}}`
- **Pre-header**: `Your application is in. Expected LEW review window: 24–72 hours.`
- **Headline**: `We've received your application.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Thank you for submitting your electrical installation licence application. We've assigned it the reference **#{{publicCode}}**, and a Licensed Electrical Worker (LEW) will be matched within **{{reviewWindowText}}** (typically 24–72 hours).
  >
  > **What happens next**
  > 1. We match a LEW to your application based on workload and location.
  > 2. The LEW reviews your floor plan and may request additional documents.
  > 3. Once approved, we'll prompt you for payment via PayNow.
  > 4. After payment, work begins and your licence is issued upon completion.
  >
  > A PDF receipt is attached to this email for your records.
- **Primary CTA**: `Track my application` → `{{ctaUrl}}` (`/applications/{{publicCode}}`)
- **Secondary**: (none)
- **Attachment**: `receipt-{{publicCode}}.pdf` (generated server-side)
- **Footer reason**: `you submitted an application on LicenseKaki.`
- **Opt-out**: STATUS — opt-out unavailable (transactional)

**In-app**
- **Title**: `Application received: #{{publicCode}}`
- **Body**: `LEW review will start within 24–72 hours. View status anytime.`
- **Action label**: `Track application`
- **Deep-link**: `/applications/{{publicCode}}`

**SMS / WhatsApp**: N/A (no SMS at submission — phone may not be on file).

**Edge cases**
- For RENEWAL applications, replace headline with `We've received your renewal application.` and adjust step 4: `your renewed licence is issued upon completion.`
- For Concierge-originated applications (`viaConciergeRequestSeq != null`), suppress this email — A-31 already provided the receipt.
- Subject must not include the address (PDPA / public inbox safety).

---

#### A-09 — Draft saved (abandonment reminder)

| Field | Value |
|---|---|
| Category | REMINDER |
| Severity | Informational (○) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | Scheduler — D+1 and D+3 after last edit on a `DRAFT` application |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{lastEditedDisplay}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your draft application is waiting`
- **Pre-header**: `Pick up where you left off — it takes about 5 more minutes.`
- **Headline**: `You have a draft application in progress.`
- **Body**:
  > Hello {{applicantName}},
  >
  > You started an application on **{{lastEditedDisplay}}** and saved it as a draft. We've kept everything safe — sign back in to finish.
  >
  > A typical applicant completes the form in about 5 more minutes.
- **Primary CTA**: `Continue my draft` → `{{ctaUrl}}` (`/applications/{{publicCode}}/edit`)
- **Footer reason**: `you have a draft application that hasn't been submitted.`
- **Opt-out**: REMINDER — opt-out available in Preference Centre

**Edge cases**
- Maximum 2 reminders ever. After D+3, mute permanently.

---

#### A-10 — LEW assigned

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `AdminLewService.assignLew` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{lewName}}`, `{{lewGradeLabel}}`, `{{expectedNextStepText}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your reviewer has been assigned · #{{publicCode}}`
- **Pre-header**: `{{lewName}} will review your application. Expect first contact within 48 hours.`
- **Headline**: `Your Licensed Electrical Worker is on the case.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Good news — **{{lewName}}** ({{lewGradeLabel}}) has been assigned as the Licensed Electrical Worker (LEW) for application **#{{publicCode}}**.
  >
  > **What happens next**: {{expectedNextStepText}}. You'll be notified each time the status changes — there's nothing for you to do right now.
- **Primary CTA**: `View application` → `{{ctaUrl}}` (`/applications/{{publicCode}}`)
- **Footer reason**: `a reviewer was assigned to your application.`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Reviewer assigned: {{lewName}}`
- **Body**: `Your LEW will review #{{publicCode}} within the next 48 hours.`
- **Action label**: `View application`
- **Deep-link**: `/applications/{{publicCode}}`

---

#### A-11 — kVA confirmed

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `ApplicationKvaService.confirmKva` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{kvaLabel}}`, `{{quotedAmount}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your kVA tier is confirmed · #{{publicCode}}`
- **Pre-header**: `Fee is fixed. Payment will be requested after final document review.`
- **Headline**: `Your LEW has confirmed your kVA tier.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your reviewer has confirmed the load profile for application **#{{publicCode}}** as **{{kvaLabel}}**, with a fixed service fee of **SGD {{quotedAmount}}**.
  >
  > Payment is **not yet due** — we'll prompt you once the LEW finishes the document checks. No surprises: this is the final fee.
- **Primary CTA**: `View application` → `{{ctaUrl}}` (`/applications/{{publicCode}}`)
- **Footer reason**: `your application's kVA tier was just confirmed.`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `kVA confirmed: {{kvaLabel}}`
- **Body**: `Service fee fixed at SGD {{quotedAmount}}. Payment will be requested later.`
- **Deep-link**: `/applications/{{publicCode}}`

---

#### A-12 — Document request created by LEW

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `DocumentRequestNotifier.notifyCreated` (already implemented) |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{requestedCount}}`, `{{documentLabelsBulleted}}`, `{{deadline}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] {{requestedCount}} document(s) requested · #{{publicCode}}`
- **Pre-header**: `Your reviewer needs a few more files to keep things moving.`
- **Headline**: `Your LEW needs {{requestedCount}} more document(s).`
- **Body**:
  > Hello {{applicantName}},
  >
  > To complete the review of application **#{{publicCode}}**, your Licensed Electrical Worker has requested:
  >
  > {{documentLabelsBulleted}}
  >
  > Please upload these by **{{deadline}}** so the review can continue.
- **Primary CTA**: `Upload documents` → `{{ctaUrl}}` (`/applications/{{publicCode}}#documents`)
- **Footer reason**: `your reviewer requested additional documents.`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `{{requestedCount}} document(s) requested`
- **Body**: `Application #{{publicCode}} needs {{requestedCount}} more file(s) by {{deadline}}.`
- **Deep-link**: `/applications/{{publicCode}}#documents`

---

#### A-13 — Documents approved by LEW

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `DocumentRequestNotifier.notifyApproved` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{documentLabel}}`, `{{remainingCount}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Document approved · #{{publicCode}}`
- **Pre-header**: `Nice — one less item on your list.`
- **Headline**: `Your LEW approved a document.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your reviewer has approved **{{documentLabel}}** for application **#{{publicCode}}**.
  >
  > {{remainingCount}} document(s) remain. We'll keep you posted as the review progresses.
- **Primary CTA**: `View application` → `{{ctaUrl}}` (`/applications/{{publicCode}}#documents`)
- **Footer reason**: `a document on your application was approved.`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Document approved`
- **Body**: `{{documentLabel}} approved on #{{publicCode}}. {{remainingCount}} item(s) remaining.`
- **Deep-link**: `/applications/{{publicCode}}#documents`

---

#### A-14 — Documents rejected by LEW

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I + S |
| Trigger | `DocumentRequestNotifier.notifyRejected` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{documentLabel}}`, `{{rejectionReason}}`, `{{deadline}}`, `{{ctaUrl}}`, `{{shortUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Document needs re-upload · #{{publicCode}}`
- **Pre-header**: `Action required within 24 hours to keep your application on track.`
- **Headline**: `Your LEW needs you to re-upload a document.`
- **Body**:
  > Hello {{applicantName}},
  >
  > **{{documentLabel}}** on application **#{{publicCode}}** wasn't accepted. Reason from your reviewer:
  >
  > > {{rejectionReason}}
  >
  > Please re-upload a corrected version by **{{deadline}}** so the review can resume.
- **Primary CTA**: `Re-upload now` → `{{ctaUrl}}` (`/applications/{{publicCode}}#documents`)
- **Footer reason**: `a document on your application needs to be re-uploaded.`
- **Anti-phishing**: standard footer block
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Document re-upload needed`
- **Body**: `{{documentLabel}} rejected on #{{publicCode}}. Please re-upload by {{deadline}}.`
- **Deep-link**: `/applications/{{publicCode}}#documents`

**SMS** (sent only if applicant has phone on file and SMS enabled for STATUS category)
- **Body**: `[LicenseKaki] Doc rejected on #{{publicCode}}. Re-upload by {{deadline}}: {{shortUrl}}`
- **Length budget**: ~110 chars (with `lk.sg/r/{{publicCode}}` short URL)

**WhatsApp**: not sent (S provides parity); reserve W for VISIT/PAYMENT.

**Edge cases**
- `{{rejectionReason}}` may contain user-supplied prose — escape HTML on render and truncate at 600 chars in the email body.
- SMS must NOT include the rejection reason (PDPA + privacy in shared phones).

---

#### A-15 — Revision requested (REVISION_REQUESTED)

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I (+ W opt-in) |
| Trigger | `AdminApplicationService.requestRevision` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{revisionNotes}}`, `{{deadline}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your application needs a revision · #{{publicCode}}`
- **Pre-header**: `Reviewer left detailed notes — make changes to continue.`
- **Headline**: `Your reviewer requested a revision.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your Licensed Electrical Worker has reviewed application **#{{publicCode}}** and asked for revisions before approval. Their notes:
  >
  > > {{revisionNotes}}
  >
  > Please make the changes and resubmit by **{{deadline}}** to avoid auto-cancellation on D+7.
- **Primary CTA**: `Edit my application` → `{{ctaUrl}}` (`/applications/{{publicCode}}/edit`)
- **Footer reason**: `your reviewer requested changes to your application.`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Revision requested on #{{publicCode}}`
- **Body**: `Make the requested changes by {{deadline}} to keep your application active.`
- **Deep-link**: `/applications/{{publicCode}}/edit`

**WhatsApp** (opt-in only)
- Template name: `licensekaki_applicant_revision_requested_en`
- Body: `Hi {{applicantName}}, your LicenseKaki application #{{publicCode}} needs revisions. Resubmit by {{deadline}} to keep it active.`
- Button 1: `Edit application` (URL → `{{ctaUrl}}`)

---

#### A-16 — Revision not submitted reminder

| Field | Value |
|---|---|
| Category | REMINDER |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E (+ W on D+5) |
| Trigger | `RevisionReminderScheduler` — D+2 and D+5 after `revisionRequestedAt` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{daysSinceRequest}}`, `{{cancelDate}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Reminder: revision pending on #{{publicCode}}`
- **Pre-header**: `Auto-cancellation on {{cancelDate}} unless you resubmit.`
- **Headline**: `Your application revision is still pending.`
- **Body**:
  > Hello {{applicantName}},
  >
  > It's been **{{daysSinceRequest}} day(s)** since your reviewer requested changes to application **#{{publicCode}}**. To keep your application from being auto-cancelled on **{{cancelDate}}**, please resubmit your revisions.
  >
  > Stuck on something? Reply to this email or contact support@licensekaki.sg.
- **Primary CTA**: `Resubmit my revision` → `{{ctaUrl}}` (`/applications/{{publicCode}}/edit`)
- **Footer reason**: `you have a pending revision request on your application.`
- **Opt-out**: REMINDER — opt-out available

**WhatsApp** (sent only on D+5; opt-in only)
- Template name: `licensekaki_applicant_revision_reminder_d5_en`
- Body: `Hi {{applicantName}}, your LicenseKaki application #{{publicCode}} will be auto-cancelled on {{cancelDate}} unless you resubmit your revisions today.`
- Button 1: `Resubmit now` (URL → `{{ctaUrl}}`)

**Edge cases**: Maximum 2 reminders. Suppress if applicant has resubmitted.

---

#### A-17 — Payment required (PENDING_PAYMENT)

| Field | Value |
|---|---|
| Category | PAYMENT |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `AdminApplicationService.approveForPayment` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{kvaLabel}}`, `{{amount}}`, `{{paynowUen}}`, `{{paynowAccountName}}`, `{{paynowReference}}`, `{{deadline}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Payment requested · #{{publicCode}}`
- **Pre-header**: `Your application is approved — pay to begin work.`
- **Headline**: `Your application is approved. Please complete payment to start work.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Good news — your Licensed Electrical Worker has confirmed the scope of work for application **#{{publicCode}}** ({{kvaLabel}}). To begin the work, please settle the payment below by **{{deadline}}**.
  >
  > **Amount due**: SGD {{amount}}
  > **PayNow UEN**: {{paynowUen}}
  > **Payee name**: {{paynowAccountName}}
  > **Reference (must include)**: {{paynowReference}}
  >
  > Including the reference code lets us match your payment automatically — usually within 1 business hour.
- **Primary CTA**: `Pay via PayNow` → `{{ctaUrl}}` (`/applications/{{publicCode}}/payment`)
- **Secondary**: `View application` → `/applications/{{publicCode}}`
- **Footer reason**: `your application is awaiting payment.`
- **Anti-phishing**: standard footer block + `LicenseKaki will never call you to change PayNow details. Verify the UEN above against your dashboard.`
- **Opt-out**: PAYMENT — opt-out unavailable

**In-app**
- **Title**: `Payment requested on #{{publicCode}}`
- **Body**: `Pay SGD {{amount}} via PayNow by {{deadline}} to start work.`
- **Action label**: `Pay now`
- **Deep-link**: `/applications/{{publicCode}}/payment`

**SMS / WhatsApp**: not sent at trigger time — see A-18 (D-3 reminder) and A-19 (D-1 SMS).

**Edge cases**
- If `paymentDueAt` is < 24 h away when triggered, also enqueue A-19 immediately (no debounce).
- Subject must NOT include amount or address (PDPA / public inbox safety).
- After PAID confirmed → A-20 supersedes; cancel any pending A-18/A-19 sends for this application.

---

#### A-18 — Payment due D-3 reminder

| Field | Value |
|---|---|
| Category | REMINDER |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | `PaymentDueScheduler` — 3 days before `paymentDueAt` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{amount}}`, `{{paynowUen}}`, `{{paynowReference}}`, `{{deadline}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Friendly reminder: payment due in 3 days · #{{publicCode}}`
- **Pre-header**: `Your work can start as soon as PayNow is confirmed.`
- **Headline**: `Payment for #{{publicCode}} is due on {{deadline}}.`
- **Body**:
  > Hello {{applicantName}},
  >
  > A quick reminder that **SGD {{amount}}** is due on **{{deadline}}** for your installation licence application.
  >
  > **PayNow UEN**: {{paynowUen}}
  > **Reference**: {{paynowReference}}
  >
  > Once we match your payment, your LEW will begin work right away.
- **Primary CTA**: `Pay via PayNow` → `{{ctaUrl}}` (`/applications/{{publicCode}}/payment`)
- **Footer reason**: `you have an outstanding payment on LicenseKaki.`
- **Opt-out**: REMINDER — opt-out available (note: D-1 escalation cannot be disabled)

---

#### A-19 — Payment due D-1 reminder (urgent)

| Field | Value |
|---|---|
| Category | PAYMENT |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + S (+ W opt-in) |
| Trigger | `PaymentDueScheduler` — 24 h before `paymentDueAt` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{amount}}`, `{{paynowUen}}`, `{{paynowReference}}`, `{{deadline}}`, `{{ctaUrl}}`, `{{shortUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Payment due tomorrow · #{{publicCode}}`
- **Pre-header**: `24 hours left to keep your application active.`
- **Headline**: `Last day to settle your application payment.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your application **#{{publicCode}}** payment of **SGD {{amount}}** is due in less than 24 hours, by **{{deadline}}**.
  >
  > If we don't receive payment, your application will pause and may be auto-cancelled.
  >
  > **PayNow UEN**: {{paynowUen}}
  > **Reference**: {{paynowReference}}
- **Primary CTA**: `Pay now via PayNow` → `{{ctaUrl}}`
- **Footer reason**: `your application payment is due within 24 hours.`
- **Anti-phishing**: standard footer block
- **Opt-out**: PAYMENT — opt-out unavailable

**SMS**
- **Body**: `[LicenseKaki] Payment for #{{publicCode}} is due {{deadline}}. Pay now: {{shortUrl}}`
- **Length budget**: ~115 chars

**WhatsApp** (opt-in only)
- Template name: `licensekaki_applicant_payment_due_d1_en`
- Body: `Hi {{applicantName}}, your LicenseKaki payment of SGD {{amount}} for #{{publicCode}} is due tomorrow ({{deadline}}). Pay via PayNow ref {{paynowReference}} to keep your application active.`
- Button 1: `Pay now` (URL → `{{ctaUrl}}`)

**Edge cases**
- `{{deadline}}` should display SGT timezone explicitly (e.g. `25 Apr 2026, 17:00 SGT`).
- Subject must NOT include amount.

---

#### A-20 — Payment confirmed (PAID)

| Field | Value |
|---|---|
| Category | PAYMENT |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `AdminPaymentService.confirmPayment` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{amount}}`, `{{paidAtDisplay}}`, `{{lewName}}`, `{{ctaUrl}}`, `{{receiptPdfUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Payment received · #{{publicCode}}`
- **Pre-header**: `Work will now begin. Receipt attached.`
- **Headline**: `Payment received. Work is starting.`
- **Body**:
  > Hello {{applicantName}},
  >
  > We've received your PayNow payment of **SGD {{amount}}** for application **#{{publicCode}}** on **{{paidAtDisplay}}**. Thank you.
  >
  > Your reviewer **{{lewName}}** will now coordinate the work and submit the licence to authorities. We'll keep you posted as the status changes.
  >
  > A PDF receipt is attached for your records.
- **Primary CTA**: `View application` → `{{ctaUrl}}` (`/applications/{{publicCode}}`)
- **Attachment**: `receipt-{{publicCode}}.pdf`
- **Footer reason**: `your payment was confirmed.`
- **Opt-out**: PAYMENT — opt-out unavailable

**In-app**
- **Title**: `Payment confirmed on #{{publicCode}}`
- **Body**: `SGD {{amount}} received. {{lewName}} will start work shortly.`
- **Deep-link**: `/applications/{{publicCode}}`

---

#### A-21 — In-progress reassurance

| Field | Value |
|---|---|
| Category | REASSURANCE |
| Severity | Informational (○) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | Scheduler — Application stable in `IN_PROGRESS` for 3 days |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{lewName}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Quick update on #{{publicCode}}`
- **Pre-header**: `Your LEW is on it — no action needed from you.`
- **Headline**: `Your LEW is hard at work.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Just a quick check-in: your reviewer **{{lewName}}** is actively working on application **#{{publicCode}}**. There's nothing for you to do — we'll let you know as soon as your licence is issued.
  >
  > If anything is delayed by more than a week, our support team is at support@licensekaki.sg.
- **Primary CTA**: `View application` → `{{ctaUrl}}`
- **Footer reason**: `your application is currently in progress.`
- **Opt-out**: REASSURANCE — opt-out available

**Edge cases**: Sent at most once per application — set `inProgressReassuranceSentAt` flag.

---

#### A-22 — Licence issued (COMPLETED)

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I + S (+ W opt-in) |
| Trigger | `AdminApplicationService.completeApplication` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{licenceNumber}}`, `{{licenceExpiryDate}}`, `{{ctaUrl}}`, `{{shortUrl}}`, `{{licencePdfUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your installation licence has been issued · #{{publicCode}}`
- **Pre-header**: `Licence PDF attached. Save this email — you'll need it.`
- **Headline**: `Congratulations — your installation licence is live.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your electrical installation licence has been issued by the Energy Market Authority.
  >
  > **Licence number**: {{licenceNumber}}
  > **Valid until**: {{licenceExpiryDate}}
  >
  > A signed PDF is attached for your records. We'll send renewal reminders 90, 60, 30, and 7 days before expiry — no need to set a calendar reminder.
- **Primary CTA**: `View licence` → `{{ctaUrl}}` (`/applications/{{publicCode}}/licence`)
- **Attachment**: `licence-{{publicCode}}.pdf`
- **Footer reason**: `your installation licence has been issued.`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Licence issued for #{{publicCode}}`
- **Body**: `Valid until {{licenceExpiryDate}}. View or download your licence PDF.`
- **Deep-link**: `/applications/{{publicCode}}/licence`

**SMS**
- **Body**: `[LicenseKaki] Your installation licence is issued. View: {{shortUrl}}`
- **Length budget**: ~85 chars
- **Note**: must NOT include licence number, address, or expiry date in SMS body.

**WhatsApp** (opt-in only)
- Template name: `licensekaki_applicant_licence_issued_en`
- Body: `Hi {{applicantName}}, your LicenseKaki installation licence for #{{publicCode}} is now live. Valid until {{licenceExpiryDate}}.`
- Button 1: `View licence` (URL → `{{ctaUrl}}`)
- Button 2: `Download PDF` (URL → `{{licencePdfUrl}}`)

---

#### A-23 — Admin manual status change

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | I (+ optional E) |
| Trigger | `AdminApplicationService.updateStatus` (manual override) |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{previousStatus}}`, `{{newStatus}}`, `{{adminNote?}}`, `{{ctaUrl}}` |

**In-app**
- **Title**: `Status changed: {{newStatus}}`
- **Body**: `#{{publicCode}} moved from {{previousStatus}} to {{newStatus}}.`
- **Deep-link**: `/applications/{{publicCode}}`

**Email** (sent only when admin flags `notifyApplicant=true`)
- **Subject**: `[LicenseKaki] Status update on #{{publicCode}}`
- **Headline**: `An administrator updated your application status.`
- **Body**:
  > Hello {{applicantName}},
  >
  > An administrator has changed the status of your application **#{{publicCode}}** from **{{previousStatus}}** to **{{newStatus}}**.
  >
  > {{adminNote?}}
  >
  > If you have questions, please contact support@licensekaki.sg.
- **Primary CTA**: `View application` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

---

### 2.3 Licence Expiry Lifecycle

---

#### A-24 — Expiry D-90 advance notice

| Field | Value |
|---|---|
| Category | EXPIRY |
| Severity | Informational (○) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | `LicenseExpiryScheduler` — 90 days before `licenseExpiryDate` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{licenceExpiryDate}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your licence expires in 90 days`
- **Pre-header**: `Plenty of time to plan — here's how renewal works.`
- **Headline**: `Heads-up: your licence expires on {{licenceExpiryDate}}.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your installation licence on **#{{publicCode}}** expires in 90 days, on **{{licenceExpiryDate}}**.
  >
  > **You don't have to act yet.** We'll send firmer reminders at D-60 and D-30. If you'd prefer to start early, you can submit a renewal application from your dashboard now.
- **Primary CTA**: `Plan my renewal` → `{{ctaUrl}}` (`/applications/{{publicCode}}/renew`)
- **Footer reason**: `your licence is expiring within 90 days.`
- **Opt-out**: EXPIRY — opt-out unavailable for transactional class

---

#### A-25 — Expiry D-60 reminder

| Field | Value |
|---|---|
| Category | EXPIRY |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | `LicenseExpiryScheduler` — 60 days before |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{licenceExpiryDate}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Renew your licence — 60 days left`
- **Pre-header**: `You can start the renewal application now.`
- **Headline**: `Time to start your renewal.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your licence on **#{{publicCode}}** expires on **{{licenceExpiryDate}}** — about 60 days from now. Most renewals take 7–14 days, so starting now leaves comfortable headroom.
- **Primary CTA**: `Start my renewal` → `{{ctaUrl}}`
- **Opt-out**: EXPIRY — opt-out unavailable

---

#### A-26 — Expiry D-30 warning

| Field | Value |
|---|---|
| Category | EXPIRY |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `LicenseExpiryScheduler` — 30 days before |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{licenceExpiryDate}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] 30 days to licence expiry`
- **Pre-header**: `Renew now or book a concierge visit if you need help.`
- **Headline**: `Your licence expires in 30 days.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your installation licence on **#{{publicCode}}** expires on **{{licenceExpiryDate}}** — 30 days from today. To keep your installation compliant under SS 638 and EMA regulations, please renew before then.
  >
  > **Need a Licensed Electrical Worker to visit on-site?** Our Expired Licence concierge service can handle the assessment for you.
- **Primary CTA**: `Renew my licence` → `{{ctaUrl}}`
- **Secondary**: `Book a concierge visit` → `/orders/expired-licence/new`
- **Opt-out**: EXPIRY — opt-out unavailable

**In-app**
- **Title**: `30 days to expiry on #{{publicCode}}`
- **Body**: `Renew before {{licenceExpiryDate}} to keep your installation compliant.`
- **Deep-link**: `/applications/{{publicCode}}/renew`

---

#### A-27 — Expiry D-7 warning (urgent)

| Field | Value |
|---|---|
| Category | EXPIRY |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + W (opt-in) |
| Trigger | `LicenseExpiryScheduler` — 7 days before |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{licenceExpiryDate}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] One week to licence expiry`
- **Pre-header**: `Renew today to avoid a compliance gap.`
- **Headline**: `Your licence expires in one week.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your installation licence on **#{{publicCode}}** expires on **{{licenceExpiryDate}}** — only 7 days away.
  >
  > Operating an electrical installation with an expired licence breaches SS 638 and may invite EMA penalties. Please renew today, or book a same-week concierge visit if you can't fit a full application in.
- **Primary CTA**: `Renew now` → `{{ctaUrl}}`
- **Secondary**: `Book a concierge visit` → `/orders/expired-licence/new`
- **Opt-out**: EXPIRY — opt-out unavailable

**WhatsApp** (opt-in only)
- Template name: `licensekaki_applicant_expiry_d7_en`
- Body: `Hi {{applicantName}}, your LicenseKaki installation licence on #{{publicCode}} expires on {{licenceExpiryDate}} — 7 days away. Renew today to stay compliant.`
- Button 1: `Renew now` (URL → `{{ctaUrl}}`)
- Button 2: `Book concierge visit` (URL → `/orders/expired-licence/new`)

---

#### A-28 — Expiry D-1 final warning

| Field | Value |
|---|---|
| Category | EXPIRY |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + S |
| Trigger | `LicenseExpiryScheduler` — 1 day before |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{licenceExpiryDate}}`, `{{ctaUrl}}`, `{{shortUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Final notice: licence expires tomorrow`
- **Pre-header**: `One last chance before your installation falls out of compliance.`
- **Headline**: `Your licence expires tomorrow.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Tomorrow ({{licenceExpiryDate}}) your installation licence on **#{{publicCode}}** will expire. After that the platform will mark it EXPIRED and you'll need a fresh application — or our Expired Licence concierge — to recover compliance.
- **Primary CTA**: `Renew now` → `{{ctaUrl}}`
- **Opt-out**: EXPIRY — opt-out unavailable

**SMS**
- **Body**: `[LicenseKaki] Licence on #{{publicCode}} expires {{licenceExpiryDate}}. Renew: {{shortUrl}}`
- **Length budget**: ~110 chars

---

#### A-29 — Licence auto-EXPIRED transition

| Field | Value |
|---|---|
| Category | EXPIRY |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `LicenseExpiryScheduler.expireOverdueLicenses` — flips status to EXPIRED |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{licenceExpiryDate}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your licence has expired · #{{publicCode}}`
- **Pre-header**: `Recover compliance with a new application or a concierge visit.`
- **Headline**: `Your licence has just expired.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your installation licence on **#{{publicCode}}** expired on **{{licenceExpiryDate}}** and has been marked EXPIRED in our system. Operating without a valid licence breaches SS 638.
  >
  > **Two ways to recover quickly:**
  > 1. **Submit a new application** — works if your installation hasn't physically changed.
  > 2. **Book an Expired Licence concierge visit** — a Licensed Electrical Worker visits, assesses, and re-files for you. Often the fastest path.
- **Primary CTA**: `Book a concierge visit` → `{{ctaUrl}}` (`/orders/expired-licence/new`)
- **Secondary**: `Submit a new application` → `/applications/new`
- **Opt-out**: EXPIRY — opt-out unavailable

**In-app**
- **Title**: `Licence expired on #{{publicCode}}`
- **Body**: `Recover compliance — submit a new application or book a concierge visit.`
- **Deep-link**: `/orders/expired-licence/new`

**Edge cases**
- Skip if applicant already has a COMPLETED renewal application active (the new one supersedes).

---

#### A-30 — Post-expiry D+1 service pitch

| Field | Value |
|---|---|
| Category | MARKETING |
| Severity | Marketing (M) |
| Recipient | Applicant (Self) |
| Channels | E (opt-in) |
| Trigger | Scheduler — D+1 after EXPIRED transition, only if no recovery action |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[ADV][LicenseKaki] Need help recovering your licence?`
- **Pre-header**: `An LEW can visit your site and re-file in days, not weeks.`
- **Headline**: `Don't let your installation drift further out of compliance.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your licence on **#{{publicCode}}** expired yesterday. Many installations need a fresh on-site assessment before they can be re-licensed.
  >
  > Our **Expired Licence concierge** dispatches a Licensed Electrical Worker to your site, performs the assessment, and submits the application — typically in 5 working days.
- **Primary CTA**: `Get a concierge quote` → `{{ctaUrl}}` (`/orders/expired-licence/new`)
- **Footer reason**: `you have an expired licence and may benefit from our concierge service.`
- **Marketing footer**: standard MARKETING block (§9) including STOP / unsubscribe instructions and `[ADV]` Spam Control Act label
- **Opt-out**: MARKETING — opt-out available; sent only if explicitly opted in

**Edge cases**: Sent at most once. Suppress if applicant has already booked a concierge / submitted a renewal.

---

### 2.4 Kaki Concierge (v1.5)

---

#### A-31 — Concierge request received

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `ConciergeNotifier.notifySubmitted` |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{setupUrl?}}`, `{{expiresAtDisplay?}}`, `{{ctaUrl}}` |

**Email** (already implemented as `sendConciergeRequestReceivedEmail`)
- **Subject**: `[LicenseKaki] Concierge request received · #{{publicCode}}`
- **Pre-header**: `A manager will call you within 24 hours. Set up your account in the meantime.`
- **Headline**: `Thank you for choosing Kaki Concierge.`
- **Body**:
  > Hello {{applicantName}},
  >
  > We've received your concierge request **#{{publicCode}}**. A manager will call you within 24 hours to discuss the work and provide a quote.
  >
  > While you wait, please set up your LicenseKaki account so you can sign documents and track progress online. The activation link below is valid until **{{expiresAtDisplay}}**:
- **Primary CTA**: `Activate my account` → `{{setupUrl}}`
- **Footer reason**: `you submitted a concierge request.`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Concierge request received: #{{publicCode}}`
- **Body**: `A manager will call within 24 hours. Stand by.`
- **Deep-link**: `/concierge/requests/{{publicCode}}`

---

#### A-32 — Concierge manager assigned

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | Concierge manager assigned |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{managerName}}`, `{{managerContactWindow}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your concierge manager is assigned · #{{publicCode}}`
- **Pre-header**: `{{managerName}} will reach out within {{managerContactWindow}}.`
- **Headline**: `Meet your concierge manager.`
- **Body**:
  > Hello {{applicantName}},
  >
  > **{{managerName}}** is now your dedicated concierge manager for request **#{{publicCode}}**. Expect a call within **{{managerContactWindow}}** to discuss your needs and confirm a quote.
- **Primary CTA**: `View request` → `{{ctaUrl}}` (`/concierge/requests/{{publicCode}}`)
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Manager assigned: {{managerName}}`
- **Body**: `Expect a call within {{managerContactWindow}} for #{{publicCode}}.`
- **Deep-link**: `/concierge/requests/{{publicCode}}`

---

#### A-33 — Concierge quote sent

| Field | Value |
|---|---|
| Category | PAYMENT |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `ConciergeNotifier.notifyQuoteSent` |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{quotedAmount}}`, `{{verificationPhrase}}`, `{{paynowUen}}`, `{{paynowAccountName}}`, `{{paynowReference}}`, `{{managerNote?}}`, `{{callScheduledAt?}}` |

**Email** (already implemented as `sendConciergeQuoteEmail`)
- **Subject**: `[LicenseKaki] Concierge quote ready · #{{publicCode}}`
- **Pre-header**: `Confirm the verification phrase before paying. Quote details inside.`
- **Headline**: `Here's the quote we discussed.`
- **Body**:
  > Hello {{applicantName}},
  >
  > As discussed on our call, here is your concierge service quote for **#{{publicCode}}**.
  >
  > **Verification phrase**: `{{verificationPhrase}}` — this matches the phrase your manager said on the call. **If it doesn't match, do not pay** — contact support@licensekaki.sg immediately.
  >
  > **Quoted amount**: SGD {{quotedAmount}}
  > **PayNow UEN**: {{paynowUen}}
  > **Payee name**: {{paynowAccountName}}
  > **Reference (must include)**: {{paynowReference}}
  >
  > {{managerNote?}}
- **Primary CTA**: `Pay via PayNow` → `pay://...` (instructions only — no clickable PayNow link to avoid spoofing)
- **Footer reason**: `your concierge manager sent you a quote.`
- **Anti-phishing**: emphasized — `Always verify the four-word phrase against your call. LicenseKaki UEN is published in your account dashboard.`
- **Opt-out**: PAYMENT — opt-out unavailable

**In-app**
- **Title**: `Concierge quote: #{{publicCode}}`
- **Body**: `SGD {{quotedAmount}} — verify the phrase before paying.`
- **Deep-link**: `/concierge/requests/{{publicCode}}`

---

#### A-34 — Concierge LOA signature request

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I + S (+ W opt-in) |
| Trigger | `LoaService.generateLoa` |
| Reference | `LOA#{loaToken}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{managerName}}`, `{{loaSignUrl}}`, `{{shortUrl}}`, `{{expiresAtDisplay}}` |

**Email**
- **Subject**: `[LicenseKaki] Sign your Letter of Authorisation · #{{publicCode}}`
- **Pre-header**: `Valid for 72 hours. Required before your LEW can act on your behalf.`
- **Headline**: `Please sign your Letter of Authorisation.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your concierge manager **{{managerName}}** has prepared a Letter of Authorisation (LOA) so the assigned Licensed Electrical Worker can submit your licence on your behalf.
  >
  > **Please sign within 72 hours** — link valid until **{{expiresAtDisplay}}**.
  >
  > Signing is fully digital. The signed PDF will be stored with your record and a copy sent back to you.
- **Primary CTA**: `Review and sign LOA` → `{{loaSignUrl}}`
- **Footer reason**: `your concierge manager sent you a LOA to sign.`
- **Anti-phishing**: standard footer block + `LicenseKaki LOAs are signed only on app.licensekaki.sg. Do not sign LOAs sent via WhatsApp images or third-party PDFs.`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `LOA ready to sign on #{{publicCode}}`
- **Body**: `Sign within 72 hours so your concierge manager can proceed.`
- **Deep-link**: `/loa/{{loaToken}}`

**SMS**
- **Body**: `[LicenseKaki] Sign your LOA for #{{publicCode}} within 72h: {{shortUrl}}`
- **Length budget**: ~95 chars

**WhatsApp** (opt-in only)
- Template name: `licensekaki_applicant_loa_sign_en`
- Body: `Hi {{applicantName}}, your LicenseKaki concierge manager {{managerName}} has prepared a Letter of Authorisation for #{{publicCode}}. Please sign within 72 hours.`
- Button 1: `Review and sign` (URL → `{{loaSignUrl}}`)

---

#### A-35 — LOA reminder (48 h unsigned)

| Field | Value |
|---|---|
| Category | REMINDER |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + S |
| Trigger | `LoaReminderScheduler` — 48 h after `Loa.generatedAt`, still unsigned |
| Reference | `LOA#{loaToken}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{loaSignUrl}}`, `{{shortUrl}}`, `{{expiresAtDisplay}}` |

**Email**
- **Subject**: `[LicenseKaki] Reminder: 24 hours left to sign your LOA · #{{publicCode}}`
- **Pre-header**: `Without a signed LOA, work can't proceed.`
- **Headline**: `Your Letter of Authorisation is still unsigned.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your LOA for **#{{publicCode}}** expires in **24 hours**, on **{{expiresAtDisplay}}**. After that, your manager will need to regenerate the document, delaying your work.
- **Primary CTA**: `Sign LOA now` → `{{loaSignUrl}}`
- **Opt-out**: REMINDER — opt-out unavailable for this one (Critical-class reminder)

**SMS**
- **Body**: `[LicenseKaki] LOA for #{{publicCode}} expires in 24h. Sign: {{shortUrl}}`
- **Length budget**: ~85 chars

---

#### A-36 — LOA proxy-upload confirmation

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E |
| Trigger | `LoaService.uploadSignedByManager` |
| Reference | `LOA#{loaToken}` |
| Variables | `{{applicantName}}`, `{{managerName}}`, `{{publicCode}}`, `{{managerNote?}}`, `{{objectionDeadline}}` |

**Email** (already implemented as `sendConciergeLoaUploadConfirmEmail`)
- **Subject**: `[LicenseKaki] LOA uploaded by your manager · #{{publicCode}}`
- **Pre-header**: `7-day objection window — review and reply if anything looks off.`
- **Headline**: `Your concierge manager uploaded your signed LOA.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your concierge manager **{{managerName}}** has uploaded the signed Letter of Authorisation for **#{{publicCode}}** on your behalf.
  >
  > {{managerNote?}}
  >
  > **7-day objection window**: if you believe this upload is in error or you didn't authorise it, please reply to this email or write to support@licensekaki.sg by **{{objectionDeadline}}**. After that date the LOA is treated as final.
- **Primary CTA**: (none — this is a legal notice; no action needed unless objecting)
- **Footer reason**: `a signed LOA was uploaded on your behalf.`
- **Opt-out**: STATUS — opt-out unavailable

---

#### A-37 — Concierge licence-fee payment request

| Field | Value |
|---|---|
| Category | PAYMENT |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `approveForPayment` AND `viaConciergeRequestSeq != null` |
| Reference | `Application#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{managerName}}`, `{{kvaLabel}}`, `{{amount}}`, `{{paynowUen}}`, `{{paynowReference}}`, `{{deadline}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Concierge licence fee due · #{{publicCode}}`
- **Pre-header**: `Pay this final fee so your manager can complete your licence.`
- **Headline**: `Final step: licence fee payment.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your concierge manager **{{managerName}}** has finalised the licence application for **#{{publicCode}}** ({{kvaLabel}}). The licence fee is the last step.
  >
  > **Amount due**: SGD {{amount}}
  > **PayNow UEN**: {{paynowUen}}
  > **Reference (must include)**: {{paynowReference}}
  > **Deadline**: {{deadline}}
- **Primary CTA**: `Pay via PayNow` → `{{ctaUrl}}`
- **Anti-phishing**: standard footer + `Your concierge manager will never request payment via personal accounts. Verify the UEN above.`
- **Opt-out**: PAYMENT — opt-out unavailable

**In-app**
- **Title**: `Concierge licence fee · #{{publicCode}}`
- **Body**: `SGD {{amount}} due {{deadline}}. Pay via PayNow.`
- **Deep-link**: `/applications/{{publicCode}}/payment`

---

#### A-38 — Concierge visit scheduled

| Field | Value |
|---|---|
| Category | VISIT |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I + S |
| Trigger | `ConciergeService.scheduleVisit` |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{visitAtDisplay}}`, `{{visitAddress}}`, `{{managerName}}`, `{{managerPhone}}`, `{{ctaUrl}}`, `{{shortUrl}}`, `{{icalAttachmentUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Visit confirmed · #{{publicCode}}`
- **Pre-header**: `Calendar invite attached. Manager contact details inside.`
- **Headline**: `Your on-site visit is confirmed.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your concierge visit for **#{{publicCode}}** is confirmed:
  >
  > - **When**: {{visitAtDisplay}}
  > - **Where**: {{visitAddress}}
  > - **Manager**: {{managerName}} (mobile: {{managerPhone}})
  >
  > A calendar invite (.ics) is attached — open it to add the visit to your phone calendar in one tap.
  >
  > **Day-before reminder** will arrive by SMS at 09:00 SGT, plus a 30-minute heads-up when your manager is on the way.
- **Primary CTA**: `View request` → `{{ctaUrl}}`
- **Attachment**: `concierge-visit-{{publicCode}}.ics`
- **Footer reason**: `your concierge visit was scheduled.`
- **Opt-out**: VISIT — opt-out unavailable

**In-app**
- **Title**: `Visit confirmed: {{visitAtDisplay}}`
- **Body**: `Manager {{managerName}} will visit you. Tap for details.`
- **Deep-link**: `/concierge/requests/{{publicCode}}`

**SMS**
- **Body**: `[LicenseKaki] Visit confirmed for #{{publicCode}} on {{visitAtDisplay}}. Mgr {{managerName}}. Details: {{shortUrl}}`
- **Length budget**: ~155 chars (tight — `{{visitAtDisplay}}` rendered as `25 Apr 14:00`)

**Edge cases**
- If the visit is rescheduled, body must include the line `This replaces the previous schedule.`
- SMS must NOT include the address (PDPA — phones may be shared).

---

#### A-39 — Visit D-1 reminder

| Field | Value |
|---|---|
| Category | VISIT |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | S (+ W opt-in) |
| Trigger | `VisitReminderScheduler` — daily 09:00 SGT, visits 24 h ahead |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{visitAtDisplay}}`, `{{managerName}}`, `{{managerPhone}}`, `{{shortUrl}}` |

**SMS**
- **Body**: `[LicenseKaki] Tomorrow {{visitAtDisplay}}, mgr {{managerName}} visits #{{publicCode}}. Call: {{managerPhone}}. {{shortUrl}}`
- **Length budget**: ~155 chars

**WhatsApp** (opt-in only)
- Template name: `licensekaki_applicant_visit_d1_en`
- Body: `Hi {{applicantName}}, your LicenseKaki concierge visit is tomorrow at {{visitAtDisplay}}. Manager {{managerName}} can be reached at {{managerPhone}}.`
- Button 1: `View visit details` (URL → `/concierge/requests/{{publicCode}}`)

**Email / In-app**: not sent — already received in A-38; reduces noise.

---

#### A-40 — Visit arrival 30 min away

| Field | Value |
|---|---|
| Category | VISIT |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | S + W |
| Trigger | Manager taps "On the way" in app |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{managerName}}`, `{{managerPhone}}`, `{{publicCode}}` |

**SMS**
- **Body**: `[LicenseKaki] {{managerName}} is on the way to #{{publicCode}}, arriving ~30 min. Call: {{managerPhone}}`
- **Length budget**: ~125 chars

**WhatsApp**
- Template name: `licensekaki_applicant_visit_eta_en`
- Body: `Hi! {{managerName}} is on the way to your LicenseKaki visit (#{{publicCode}}) — arriving in about 30 minutes.`
- Button 1: `Call manager` (PHONE → `{{managerPhone}}`)

---

#### A-41 — Concierge visit completed (photos)

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `ConciergeService.uploadVisitPhotos` |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{managerName}}`, `{{visitSummary}}`, `{{photoCount}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Visit complete — please confirm · #{{publicCode}}`
- **Pre-header**: `{{photoCount}} photos uploaded. Review and confirm in one tap.`
- **Headline**: `Your concierge visit is done.`
- **Body**:
  > Hello {{applicantName}},
  >
  > **{{managerName}}** has finished the on-site visit for **#{{publicCode}}**. They've uploaded **{{photoCount}}** photo(s) and a work summary:
  >
  > > {{visitSummary}}
  >
  > Please review and confirm completion so we can proceed with the licence filing.
- **Primary CTA**: `Review and confirm` → `{{ctaUrl}}` (`/concierge/requests/{{publicCode}}/confirm`)
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Visit complete on #{{publicCode}}`
- **Body**: `{{photoCount}} photos uploaded. Review and confirm.`
- **Deep-link**: `/concierge/requests/{{publicCode}}/confirm`

---

#### A-42 — Concierge final completion

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I (+ W) |
| Trigger | Concierge `COMPLETED` transition |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{npsUrl}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Concierge service complete · #{{publicCode}}`
- **Pre-header**: `Thanks for letting us handle it. A quick rating helps us improve.`
- **Headline**: `Thank you for trusting Kaki Concierge.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your concierge service for **#{{publicCode}}** is now complete. We hope it took the load off your plate.
  >
  > In a few days we'll send a 1-tap rating request — your feedback shapes how we hire and train managers.
- **Primary CTA**: `View summary` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Concierge service complete`
- **Body**: `#{{publicCode}} closed. Thanks for choosing Kaki Concierge.`
- **Deep-link**: `/concierge/requests/{{publicCode}}`

**WhatsApp** (opt-in only)
- Template name: `licensekaki_applicant_concierge_complete_en`
- Body: `Hi {{applicantName}}, your LicenseKaki concierge service for #{{publicCode}} is complete. Thank you for trusting our managers.`
- Button 1: `View summary` (URL → `{{ctaUrl}}`)

---

#### A-43 — Concierge cancelled

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | Manager / Admin cancellation |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{cancelReason}}`, `{{refundDetails?}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Concierge request cancelled · #{{publicCode}}`
- **Pre-header**: `Refund handling and next steps inside.`
- **Headline**: `Your concierge request has been cancelled.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your concierge request **#{{publicCode}}** has been cancelled. Reason:
  >
  > > {{cancelReason}}
  >
  > {{refundDetails?}}
  >
  > If you'd like to discuss alternatives, reply to this email or contact support@licensekaki.sg.
- **Primary CTA**: `View request` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Concierge cancelled · #{{publicCode}}`
- **Body**: `Reason: {{cancelReason}}. Tap for refund details.`
- **Deep-link**: `/concierge/requests/{{publicCode}}`

---

### 2.5 SLD Order

---

#### A-44 — SLD quote proposed

| Field | Value |
|---|---|
| Category | PAYMENT |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `SldOrderService.proposeQuote` |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{quotedAmount}}`, `{{validUntilDisplay}}`, `{{paynowUen}}`, `{{paynowReference}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] SLD quote ready · #{{publicCode}}`
- **Pre-header**: `Accept and pay to start your single-line diagram.`
- **Headline**: `Your SLD quote is ready.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Our SLD team has reviewed your requirements for **#{{publicCode}}** and prepared a quote:
  >
  > **Amount**: SGD {{quotedAmount}}
  > **Valid until**: {{validUntilDisplay}}
  > **PayNow UEN**: {{paynowUen}}
  > **Reference**: {{paynowReference}}
  >
  > Once payment is confirmed, drafting begins and your draft is typically ready in 3–5 working days.
- **Primary CTA**: `Review quote` → `{{ctaUrl}}` (`/orders/sld/{{publicCode}}`)
- **Opt-out**: PAYMENT — opt-out unavailable

**In-app**
- **Title**: `SLD quote ready · #{{publicCode}}`
- **Body**: `SGD {{quotedAmount}} valid until {{validUntilDisplay}}.`
- **Deep-link**: `/orders/sld/{{publicCode}}`

---

#### A-45 — SLD quote reminder

| Field | Value |
|---|---|
| Category | REMINDER |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E (+ W on D-1) |
| Trigger | Scheduler — D-3 and D-1 to `validUntil` |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{quotedAmount}}`, `{{validUntilDisplay}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] SLD quote expires soon · #{{publicCode}}`
- **Pre-header**: `Accept by {{validUntilDisplay}} to lock in the quoted price.`
- **Headline**: `Your SLD quote expires on {{validUntilDisplay}}.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Just a reminder that your SLD quote of **SGD {{quotedAmount}}** for **#{{publicCode}}** expires on **{{validUntilDisplay}}**. After that we'll need to re-quote (prices may differ).
- **Primary CTA**: `Review quote` → `{{ctaUrl}}`
- **Opt-out**: REMINDER — opt-out available

**WhatsApp** (D-1 only, opt-in)
- Template name: `licensekaki_applicant_sld_quote_d1_en`
- Body: `Hi {{applicantName}}, your LicenseKaki SLD quote for #{{publicCode}} expires tomorrow ({{validUntilDisplay}}).`
- Button 1: `Review quote` (URL → `{{ctaUrl}}`)

---

#### A-46 — SLD drawing uploaded

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I (+ W opt-in) |
| Trigger | `SldManagerService.uploadSld` |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{previewUrl}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your SLD draft is ready to review · #{{publicCode}}`
- **Pre-header**: `Preview, request revisions, or approve in one click.`
- **Headline**: `Your SLD draft is ready.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Our SLD team has uploaded the first draft of your single-line diagram for **#{{publicCode}}**. Please review and either approve or request revisions.
  >
  > Preview the drawing: {{previewUrl}}
- **Primary CTA**: `Review draft` → `{{ctaUrl}}` (`/orders/sld/{{publicCode}}`)
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `SLD draft ready · #{{publicCode}}`
- **Body**: `Tap to review and approve or request revisions.`
- **Deep-link**: `/orders/sld/{{publicCode}}`

**WhatsApp** (opt-in)
- Template name: `licensekaki_applicant_sld_uploaded_en`
- Body: `Hi {{applicantName}}, your LicenseKaki SLD draft for #{{publicCode}} is ready to review.`
- Button 1: `Review draft` (URL → `{{ctaUrl}}`)

---

#### A-47 — SLD order completed

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `SldManagerService.markComplete` |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{dxfUrl}}`, `{{pdfUrl}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] SLD complete · #{{publicCode}}`
- **Pre-header**: `DXF and PDF downloads attached.`
- **Headline**: `Your single-line diagram is complete.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your SLD order **#{{publicCode}}** is now complete. The signed DXF and PDF files are attached, and also available from your dashboard.
  >
  > **How to use**:
  > - Submit the PDF as part of an EMA application (we accept this format on LicenseKaki main applications).
  > - Open the DXF in any AutoCAD-compatible tool for further editing.
- **Primary CTA**: `View files` → `{{ctaUrl}}`
- **Attachments**: `sld-{{publicCode}}.dxf`, `sld-{{publicCode}}.pdf`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `SLD complete · #{{publicCode}}`
- **Body**: `Download DXF and PDF from your dashboard.`
- **Deep-link**: `/orders/sld/{{publicCode}}`

---

### 2.6 Expired Licence Order

---

#### A-48 — Expired Licence quote proposed

| Field | Value |
|---|---|
| Category | PAYMENT |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `ExpiredLicenseOrderService.proposeQuote` |
| Reference | `ExpiredLicenceOrder#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{quotedAmount}}`, `{{availableSlotsBulleted}}`, `{{validUntilDisplay}}`, `{{paynowUen}}`, `{{paynowReference}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Expired Licence quote ready · #{{publicCode}}`
- **Pre-header**: `Choose a visit slot when you accept the quote.`
- **Headline**: `Your Expired Licence quote is ready.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Our concierge team has prepared your quote and proposed visit slots for **#{{publicCode}}**:
  >
  > **Amount**: SGD {{quotedAmount}}
  > **Available visit slots**:
  > {{availableSlotsBulleted}}
  > **PayNow UEN**: {{paynowUen}}
  > **Reference**: {{paynowReference}}
  > **Quote valid until**: {{validUntilDisplay}}
- **Primary CTA**: `Accept and pick a slot` → `{{ctaUrl}}` (`/orders/expired-licence/{{publicCode}}`)
- **Opt-out**: PAYMENT — opt-out unavailable

**In-app**
- **Title**: `Expired Licence quote · #{{publicCode}}`
- **Body**: `SGD {{quotedAmount}} — pick a visit slot to accept.`
- **Deep-link**: `/orders/expired-licence/{{publicCode}}`

---

#### A-49 — Expired Licence visit scheduled

| Field | Value |
|---|---|
| Category | VISIT |
| Severity | Critical (★) |
| Recipient | Applicant (Self) |
| Channels | E + I + S |
| Trigger | `ExpiredLicenseManagerService.scheduleVisit` |
| Reference | `ExpiredLicenceOrder#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{visitAtDisplay}}`, `{{visitAddress}}`, `{{managerName}}`, `{{managerPhone}}`, `{{ctaUrl}}`, `{{shortUrl}}`, `{{icalAttachmentUrl}}` |

Same structure as A-38. Subject: `[LicenseKaki] Expired Licence visit confirmed · #{{publicCode}}`. Body adjusted to mention "Expired Licence visit" rather than "concierge visit". SMS body: `[LicenseKaki] Expired Licence visit for #{{publicCode}} on {{visitAtDisplay}}. Mgr {{managerName}}. Details: {{shortUrl}}`.

---

#### A-50 — Expired Licence visit D-1 reminder

| Field | Value |
|---|---|
| Category | VISIT |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | S (+ W opt-in) |
| Trigger | `VisitReminderScheduler` for ExpiredLicenceOrder |
| Reference | `ExpiredLicenceOrder#{publicCode}` |
| Variables | as A-39 |

Same structure as A-39. Identifier text changed to "Expired Licence visit". SMS body: `[LicenseKaki] Tomorrow {{visitAtDisplay}}, mgr {{managerName}} visits #{{publicCode}}. Call: {{managerPhone}}. {{shortUrl}}`.

---

#### A-51 — Visit check-in (manager arrived)

| Field | Value |
|---|---|
| Category | VISIT |
| Severity | Informational (○) |
| Recipient | Applicant (Self) |
| Channels | I |
| Trigger | Manager taps "Check in" on-site |
| Reference | `ExpiredLicenceOrder#{publicCode}` |
| Variables | `{{managerName}}`, `{{publicCode}}`, `{{checkedInAtDisplay}}` |

**In-app**
- **Title**: `{{managerName}} has arrived`
- **Body**: `Your manager checked in for #{{publicCode}} at {{checkedInAtDisplay}}.`
- **Deep-link**: `/orders/expired-licence/{{publicCode}}`

**Email / SMS / WhatsApp**: N/A — manager and applicant are usually side-by-side.

---

#### A-52 — Visit completed

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | `ExpiredLicenseManagerService.uploadVisitPhotos` |
| Reference | `ExpiredLicenceOrder#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{managerName}}`, `{{visitSummary}}`, `{{photoCount}}`, `{{diagnosis}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Visit complete — please confirm · #{{publicCode}}`
- **Pre-header**: `{{photoCount}} photos and a diagnosis ready for review.`
- **Headline**: `Your Expired Licence visit is done.`
- **Body**:
  > Hello {{applicantName}},
  >
  > **{{managerName}}** has finished the on-site assessment for **#{{publicCode}}**. They've uploaded **{{photoCount}}** photo(s), a work summary, and a diagnosis:
  >
  > > {{diagnosis}}
  >
  > Please review and confirm so we can proceed with re-licensing.
- **Primary CTA**: `Review and confirm` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Visit complete on #{{publicCode}}`
- **Body**: `Review {{photoCount}} photos and the diagnosis.`
- **Deep-link**: `/orders/expired-licence/{{publicCode}}/confirm`

---

#### A-53 — Expired Licence final completion

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | Confirmation by applicant |
| Reference | `ExpiredLicenceOrder#{publicCode}` |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Expired Licence service complete · #{{publicCode}}`
- **Pre-header**: `Receipt and full record attached.`
- **Headline**: `Thank you — your Expired Licence service is complete.`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your Expired Licence service for **#{{publicCode}}** is closed. The full record, including photos, diagnosis, and licence outcome, is available from your dashboard.
- **Primary CTA**: `View record` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Expired Licence complete · #{{publicCode}}`
- **Body**: `View the full record from your dashboard.`
- **Deep-link**: `/orders/expired-licence/{{publicCode}}`

---

### 2.7 Feedback

---

#### A-54 — NPS survey request

| Field | Value |
|---|---|
| Category | FEEDBACK |
| Severity | Informational (○) |
| Recipient | Applicant (Self) |
| Channels | E + I |
| Trigger | Scheduler — D+3 after `COMPLETED` |
| Reference | `Application#{publicCode}` (or other product) |
| Variables | `{{applicantName}}`, `{{publicCode}}`, `{{npsToken}}`, `{{npsUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] How did we do?`
- **Pre-header**: `One tap to rate. Helps us improve LicenseKaki for everyone.`
- **Headline**: `How was your LicenseKaki experience?`
- **Body**:
  > Hello {{applicantName}},
  >
  > Your application **#{{publicCode}}** wrapped up a few days ago. Could you spare 10 seconds to rate your experience? One tap below — no form to fill in.
- **Primary CTA**: `Rate my experience` → `{{npsUrl}}` (`/feedback/{{npsToken}}`)
- **Footer reason**: `you recently completed a service on LicenseKaki.`
- **Opt-out**: FEEDBACK — opt-out available

**In-app**
- **Title**: `Rate your LicenseKaki experience`
- **Body**: `Tap a number 0–10. Takes 5 seconds.`
- **Deep-link**: `/feedback/{{npsToken}}`

**Edge cases**: Sent at most once per completed product.

---

## §3. LEW — 12 cards

> Reminder: most LEW notifications go through the **digest engine** (see §8). Real-time email is reserved for SLA breaches and account approval.

---

#### L-01 — LEW registration approved

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Critical (★) |
| Recipient | LEW (Self) |
| Channels | E + I |
| Trigger | `AdminUserController.approveLew` |
| Reference | `User#{userSeq}` |
| Variables | `{{lewName}}`, `{{lewGradeLabel}}`, `{{onboardingUrl}}`, `{{dashboardUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Your LEW registration is approved`
- **Pre-header**: `Welcome aboard. Here's how to start picking up applications.`
- **Headline**: `You're approved as a Licensed Electrical Worker on LicenseKaki.`
- **Body**:
  > Hello {{lewName}},
  >
  > Welcome to the LicenseKaki LEW network — your registration as **{{lewGradeLabel}}** is now approved.
  >
  > **Get started in 3 steps**:
  > 1. Sign in to your dashboard.
  > 2. Set your weekly availability and service area.
  > 3. We'll start matching applications to you within 24 hours.
  >
  > Our LEW onboarding guide walks through document review SLAs, CoF signing, and our digest schedule.
- **Primary CTA**: `Open dashboard` → `{{dashboardUrl}}` (`/admin/dashboard`)
- **Secondary**: `Read onboarding guide` → `{{onboardingUrl}}`
- **Sign-off**: `The LicenseKaki Team`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `LEW registration approved`
- **Body**: `You can now accept applications. Set your availability to begin.`
- **Deep-link**: `/admin/dashboard`

---

#### L-02 — LEW registration rejected

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Critical (★) |
| Recipient | LEW (Self) |
| Channels | E |
| Trigger | `AdminUserController.rejectLew` (reason required) |
| Reference | `User#{userSeq}` |
| Variables | `{{lewName}}`, `{{rejectionReason}}`, `{{reapplyUrl}}`, `{{supportEmail}}` |

**Email**
- **Subject**: `[LicenseKaki] Your LEW registration needs revision`
- **Pre-header**: `Reviewer notes inside. You can reapply once addressed.`
- **Headline**: `We need a few updates before approving your registration.`
- **Body**:
  > Hello {{lewName}},
  >
  > Thank you for applying to join LicenseKaki as a Licensed Electrical Worker. Our admin team has reviewed your submission and would like the following changes before approval:
  >
  > > {{rejectionReason}}
  >
  > **How to reapply**: Sign in, update your profile and credentials, and resubmit. Most applicants are approved within 1 business day on the second pass.
  >
  > Questions? Email {{supportEmail}}.
- **Primary CTA**: `Update my registration` → `{{reapplyUrl}}` (`/admin/profile`)
- **Sign-off**: `The LicenseKaki Team`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**: not applicable — rejected accounts cannot log in until amended (depends on platform policy; if accounts remain accessible, mirror as `LEW_REJECTED` notification).

---

#### L-03 — Application assigned

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | LEW (Self) |
| Channels | I (immediate) + E (digest) |
| Trigger | `AdminLewService.assignLew` (already implemented for I; E becomes digest item) |
| Reference | `Application#{publicCode}` |
| Variables | `{{lewName}}`, `{{publicCode}}`, `{{installationAreaText}}`, `{{estimatedReviewMinutes}}`, `{{applicantNameMasked}}`, `{{ctaUrl}}` |

**In-app**
- **Title**: `New application: #{{publicCode}}`
- **Body**: `{{installationAreaText}} · est. {{estimatedReviewMinutes}} min review.`
- **Deep-link**: `/admin/applications/{{publicCode}}`

**Email** — bundled into LEW digest (§8). Per-item line:
> #{{publicCode}} — {{installationAreaText}} (est. {{estimatedReviewMinutes}} min) — Review now: `/admin/applications/{{publicCode}}`

**SMS / WhatsApp**: N/A.

---

#### L-04 — Unassigned

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | LEW (Self) |
| Channels | E + I |
| Trigger | `AdminLewService.unassignLew` |
| Reference | `Application#{publicCode}` |
| Variables | `{{lewName}}`, `{{publicCode}}`, `{{unassignReason}}`, `{{dashboardUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Application removed from your queue · #{{publicCode}}`
- **Pre-header**: `Reason inside. No action needed from you.`
- **Headline**: `An application has been removed from your queue.`
- **Body**:
  > Hello {{lewName}},
  >
  > Application **#{{publicCode}}** was reassigned away from you. Reason:
  >
  > > {{unassignReason}}
  >
  > No action is needed from you. Your queue has been updated.
- **Primary CTA**: `Open dashboard` → `{{dashboardUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Removed from queue: #{{publicCode}}`
- **Body**: `Reason: {{unassignReason}}.`
- **Deep-link**: `/admin/dashboard`

---

#### L-05 — Documents uploaded (review needed)

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | LEW (Self) |
| Channels | I (immediate) + E (digest) |
| Trigger | `DocumentRequestNotifier.notifyFulfilled` (already implemented) |
| Reference | `DocumentRequest#{drId}` |
| Variables | `{{lewName}}`, `{{publicCode}}`, `{{documentLabel}}`, `{{ctaUrl}}` |

**In-app**
- **Title**: `Document uploaded on #{{publicCode}}`
- **Body**: `{{documentLabel}} ready for review.`
- **Deep-link**: `/admin/applications/{{publicCode}}#documents`

**Email** — digest line:
> #{{publicCode}} — {{documentLabel}} ready for review: `/admin/applications/{{publicCode}}#documents`

---

#### L-06 — Payment confirmed (work can start)

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | LEW (Self) |
| Channels | E + I |
| Trigger | `AdminPaymentService.confirmPayment` (already implemented) |
| Reference | `Application#{publicCode}` |
| Variables | `{{lewName}}`, `{{publicCode}}`, `{{installationAddress}}`, `{{applicantPhone?}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Payment received — work can start · #{{publicCode}}`
- **Pre-header**: `Site address and applicant contact inside.`
- **Headline**: `Payment confirmed. You can now begin work.`
- **Body**:
  > Hello {{lewName}},
  >
  > Application **#{{publicCode}}** has been paid. You can now coordinate the work.
  >
  > - **Site address**: {{installationAddress}}
  > - **Applicant phone**: {{applicantPhone?}}
- **Primary CTA**: `Open application` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Paid — start work on #{{publicCode}}`
- **Body**: `Site: {{installationAddress}}.`
- **Deep-link**: `/admin/applications/{{publicCode}}`

---

#### L-07 — Revision resubmission received

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | LEW (Self) |
| Channels | I + E (digest) |
| Trigger | Applicant `resubmit` after revision |
| Reference | `Application#{publicCode}` |
| Variables | `{{lewName}}`, `{{publicCode}}`, `{{ctaUrl}}` |

**In-app**
- **Title**: `Revision resubmitted · #{{publicCode}}`
- **Body**: `Applicant has addressed your notes. Ready for re-review.`
- **Deep-link**: `/admin/applications/{{publicCode}}`

**Email** — digest line:
> #{{publicCode}} — Revision resubmitted, ready for re-review.

---

#### L-08 — SLA warning (24 h no action)

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Critical (★) |
| Recipient | LEW (Self) |
| Channels | E + I |
| Trigger | `LewSlaScheduler` — assigned > 24 h, no action |
| Reference | `Application#{publicCode}` |
| Variables | `{{lewName}}`, `{{publicCode}}`, `{{assignedAtDisplay}}`, `{{slaDeadline}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Action needed within 24 hours · #{{publicCode}}`
- **Pre-header**: `SLA breach in 24 hours unless you take action.`
- **Headline**: `One of your applications is approaching its SLA deadline.`
- **Body**:
  > Hello {{lewName}},
  >
  > Application **#{{publicCode}}** was assigned to you on **{{assignedAtDisplay}}** and has had no review action for 24 hours. Our SLA breach point is **{{slaDeadline}}** — please complete an initial review or document request before then.
- **Primary CTA**: `Review now` → `{{ctaUrl}}`
- **Opt-out**: OPS — opt-out unavailable

**In-app**
- **Title**: `SLA warning · #{{publicCode}}`
- **Body**: `Action needed before {{slaDeadline}}.`
- **Deep-link**: `/admin/applications/{{publicCode}}`

---

#### L-09 — SLA breach (48 h+)

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Critical (★) |
| Recipient | LEW (Self) + Admin CC (M-05) |
| Channels | E + I |
| Trigger | `LewSlaScheduler` — assigned > 48 h, no action |
| Reference | `Application#{publicCode}` |
| Variables | `{{lewName}}`, `{{publicCode}}`, `{{hoursElapsed}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] SLA breach on #{{publicCode}}`
- **Pre-header**: `Admin has been notified. Take action immediately.`
- **Headline**: `SLA breach on #{{publicCode}} — please act now.`
- **Body**:
  > Hello {{lewName}},
  >
  > Application **#{{publicCode}}** has been pending for **{{hoursElapsed}} hours** without action. This breaches our 48-hour SLA. Our admin team has been notified.
  >
  > Please review immediately or contact ops@licensekaki.sg if you cannot.
- **Primary CTA**: `Review now` → `{{ctaUrl}}`
- **Opt-out**: OPS — opt-out unavailable

**In-app**
- **Title**: `SLA breach · #{{publicCode}}`
- **Body**: `Pending {{hoursElapsed}}h. Admin notified.`
- **Deep-link**: `/admin/applications/{{publicCode}}`

---

#### L-10 — LEW Service Order events

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | LEW (Self) |
| Channels | E + I (digest for non-critical) |
| Trigger | LEW Service Order state transitions |
| Reference | `LewServiceOrder#{publicCode}` |
| Variables | depends on transition |

**Pattern**: subject `[LicenseKaki] LEW Service Order update · #{{publicCode}}`. Body summarises the transition (booking, change, completion). Bundled into the LEW digest unless the transition is "booking changed within 24h of visit" (real-time email + I).

---

#### L-11 — Field work scheduled (cross-notification trigger)

This entry triggers an applicant-facing notification (e.g. variant of A-38). LEW themselves does not receive a separate notification beyond the dashboard update; included in catalog for completeness only.

**No card** — see A-38 for the user-facing copy.

---

#### L-12 — Daily closing summary

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Informational (○) |
| Recipient | LEW (Self) |
| Channels | E |
| Trigger | `LewDailySummaryScheduler` — daily 18:00 SGT |
| Reference | `User#{lewSeq}` |
| Variables | `{{lewName}}`, `{{date}}`, `{{doneCount}}`, `{{pendingCount}}`, `{{slaWarningCount}}`, `{{dashboardUrl}}` |

**Email**
- **Subject**: `[Digest] {{doneCount}} done, {{pendingCount}} pending · {{date}}`
- **Pre-header**: `Your closing snapshot for the day.`
- **Headline**: `Today's wrap-up.`
- **Body**:
  > Hello {{lewName}},
  >
  > Here's your end-of-day snapshot for **{{date}}**:
  >
  > - **Completed today**: {{doneCount}}
  > - **Still pending**: {{pendingCount}}
  > - **SLA warnings**: {{slaWarningCount}}
  >
  > A clean queue tomorrow morning is the easiest way to stay ahead of SLAs.
- **Primary CTA**: `Open dashboard` → `{{dashboardUrl}}`
- **Opt-out**: OPS — opt-out available

---

## §4. ADMIN — 10 cards

---

#### M-01 — New application received

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Important (●) |
| Recipient | Admin |
| Channels | I (+ optional E) |
| Trigger | `ApplicationService.createApplication` |
| Reference | `Application#{publicCode}` |
| Variables | `{{publicCode}}`, `{{kvaLabel}}`, `{{installationAreaText}}`, `{{ctaUrl}}` |

**In-app**
- **Title**: `New application: #{{publicCode}}`
- **Body**: `{{kvaLabel}} · {{installationAreaText}} · ready to assign.`
- **Deep-link**: `/admin/applications/{{publicCode}}`

**Email** (only when admin enables the toggle in `system_settings.admin_notify_email_application_intake`)
- **Subject**: `[LicenseKaki][Ops] New application · #{{publicCode}}`
- **Body**: short summary + link.

---

#### M-02 — New LEW registration

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Important (●) |
| Recipient | Admin |
| Channels | E + I |
| Trigger | LEW signs up |
| Reference | `User#{userSeq}` |
| Variables | `{{candidateName}}`, `{{credentialSummary}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][Ops] LEW registration awaiting approval`
- **Pre-header**: `New candidate in the queue. Tap to review.`
- **Headline**: `A new LEW is awaiting your approval.`
- **Body**:
  > Hello team,
  >
  > **{{candidateName}}** has registered as a Licensed Electrical Worker. Credentials summary:
  >
  > > {{credentialSummary}}
  >
  > Please review their submission and approve or send back with notes.
- **Primary CTA**: `Review LEW registration` → `{{ctaUrl}}`
- **Opt-out**: OPS — opt-out unavailable for admin role

**In-app**
- **Title**: `LEW awaiting approval`
- **Body**: `{{candidateName}} — review credentials.`
- **Deep-link**: `/admin/users/lew-applications`

---

#### M-03 — New concierge request

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Important (●) |
| Recipient | Admin |
| Channels | E + I |
| Trigger | `ConciergeNotifier.notifySubmitted` (already implemented) |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{publicCode}}`, `{{regionMasked}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][Ops] New concierge request · #{{publicCode}}`
- **Pre-header**: `Assign a manager within the 24h SLA.`
- **Headline**: `New concierge request to triage.`
- **Body**:
  > New concierge request **#{{publicCode}}** in {{regionMasked}}. Assign a manager to start the 24-hour first-contact SLA clock.
- **Primary CTA**: `Triage now` → `{{ctaUrl}}`
- **Opt-out**: OPS — opt-out unavailable

**In-app**
- **Title**: `New concierge: #{{publicCode}}`
- **Body**: `{{regionMasked}} — assign a manager.`
- **Deep-link**: `/admin/concierge/requests/{{publicCode}}`

**Edge cases**: Subject must NOT contain customer name (PDPA — admin inboxes may be shared).

---

#### M-04 — Concierge 24 h SLA breach

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Critical (★) |
| Recipient | Admin |
| Channels | E + I |
| Trigger | `ConciergeSlaScheduler` — hourly |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{publicCode}}`, `{{managerName}}`, `{{hoursElapsed}}`, `{{lastContactDisplay}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][Escalation] Concierge SLA breach · #{{publicCode}}`
- **Pre-header**: `{{hoursElapsed}}h elapsed. No first-contact log.`
- **Headline**: `Concierge SLA breach — escalate.`
- **Body**:
  > | Field | Value |
  > | --- | --- |
  > | Request | #{{publicCode}} |
  > | Manager | {{managerName}} |
  > | Hours since submission | {{hoursElapsed}} |
  > | Last contact note | {{lastContactDisplay}} |
  >
  > Please intervene — reassign or escalate to the manager directly.
- **Primary CTA**: `Open request` → `{{ctaUrl}}`
- **Opt-out**: OPS — opt-out unavailable

**In-app**
- **Title**: `SLA breach · #{{publicCode}}`
- **Body**: `{{hoursElapsed}}h since intake. Manager: {{managerName}}.`
- **Deep-link**: `/admin/concierge/requests/{{publicCode}}`

---

#### M-05 — LEW SLA breach CC

Same body as L-09 but addressed to admin team. Subject: `[LicenseKaki][Escalation] LEW SLA breach · #{{publicCode}}`. Includes assigned LEW name in the body.

---

#### M-06 — Payment / PayNow match failure

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Critical (★) |
| Recipient | Admin |
| Channels | E + I |
| Trigger | PayNow matching scheduler — unmatched > N attempts |
| Reference | `Application#{publicCode}` or unmatched txn id |
| Variables | `{{publicCode?}}`, `{{applicantNameMasked}}`, `{{amount}}`, `{{paynowReference}}`, `{{txnId}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][Anomaly] PayNow match failed`
- **Pre-header**: `Manual reconciliation needed.`
- **Headline**: `A PayNow payment couldn't be matched automatically.`
- **Body**:
  > | Field | Value |
  > | --- | --- |
  > | Application | #{{publicCode?}} |
  > | Applicant | {{applicantNameMasked}} |
  > | Amount | SGD {{amount}} |
  > | Reference seen | {{paynowReference}} |
  > | Bank txn ID | {{txnId}} |
- **Primary CTA**: `Open reconciliation` → `{{ctaUrl}}`
- **Opt-out**: OPS — opt-out unavailable

**In-app**
- **Title**: `PayNow match failed`
- **Body**: `Ref {{paynowReference}} · SGD {{amount}}.`
- **Deep-link**: `/admin/payments/reconciliation`

---

#### M-07 — Invoice auto-generation failure

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Important (●) |
| Recipient | Admin |
| Channels | I (+ E) |
| Trigger | `invoiceGenerationService` failure |
| Reference | `Application#{publicCode}` or order |
| Variables | `{{publicCode}}`, `{{failureReason}}`, `{{retryOutcome}}`, `{{ctaUrl}}` |

**In-app**
- **Title**: `Invoice generation failed · #{{publicCode}}`
- **Body**: `Reason: {{failureReason}}. Retry: {{retryOutcome}}.`
- **Deep-link**: `/admin/applications/{{publicCode}}/invoice`

**Email** (escalation only)
- Subject: `[LicenseKaki][Anomaly] Invoice generation failed · #{{publicCode}}`
- Body summarises reason + manual generation link.

---

#### M-08 — Data breach alert

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Critical (★) |
| Recipient | Admin |
| Channels | E + I |
| Trigger | `DataBreachService` |
| Reference | `DataBreachIncident#{incidentSeq}` |
| Variables | `{{incidentSeq}}`, `{{detectedAtDisplay}}`, `{{affectedUserCount}}`, `{{breachSummary}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][Critical] Data breach incident #{{incidentSeq}}`
- **Pre-header**: `PDPA §26D 3-day notification clock has started.`
- **Headline**: `A data breach incident has been recorded.`
- **Body**:
  > **Incident**: #{{incidentSeq}}
  > **Detected**: {{detectedAtDisplay}}
  > **Affected users (preliminary)**: {{affectedUserCount}}
  > **Summary**: {{breachSummary}}
  >
  > **Statutory clock**: under PDPA §26D, the PDPC must be notified within **3 calendar days**. Affected individuals must be notified as soon as practicable.
- **Primary CTA**: `Open incident` → `{{ctaUrl}}`
- **Opt-out**: OPS — opt-out unavailable

**In-app**
- **Title**: `Data breach #{{incidentSeq}}`
- **Body**: `{{affectedUserCount}} users affected. PDPA clock running.`
- **Deep-link**: `/admin/security/incidents/{{incidentSeq}}`

---

#### M-09 — LEW licence auto-expiry detected

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Important (●) |
| Recipient | Admin |
| Channels | E + I |
| Trigger | Scheduler — daily |
| Reference | `User#{userSeq}` |
| Variables | `{{lewName}}`, `{{lewLicenceNumber}}`, `{{expiredOnDisplay}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][Ops] LEW licence expired`
- **Body**: lists each expired LEW with name, licence #, expiry date. Action: deactivate or follow up on renewal.
- **Primary CTA**: `Open LEW list` → `{{ctaUrl}}`
- **Opt-out**: OPS — opt-out unavailable

---

#### M-10 — Daily operations digest

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Informational (○) |
| Recipient | Admin |
| Channels | E |
| Trigger | `AdminDailyDigestScheduler` — 09:00 SGT |
| Reference | none (system-wide) |
| Variables | `{{date}}`, KPI block (intake, completion, breach counts) |

See §8 digest cards.

---

## §5. SYSTEM_ADMIN — 5 cards

System Admin alerts use a terser, ops-flavoured tone. Subject prefix: `[LicenseKaki][SysAdmin]`.

---

#### S-01 — System failure (SMTP fail rate > 5 %)

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Critical (★) |
| Recipient | System Admin |
| Channels | E (+ optional Slack via webhook) |
| Trigger | Metrics threshold |
| Reference | none |
| Variables | `{{windowMinutes}}`, `{{failRatePercent}}`, `{{logUrl}}`, `{{runbookUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][SysAdmin] SMTP fail rate {{failRatePercent}}% over {{windowMinutes}} min`
- **Body**:
  > SMTP failure rate exceeded threshold.
  >
  > - Window: {{windowMinutes}} min
  > - Fail rate: {{failRatePercent}}%
  > - Logs: {{logUrl}}
  > - Runbook: {{runbookUrl}}
- **Primary CTA**: `Open logs` → `{{logUrl}}`
- **Opt-out**: OPS — opt-out unavailable

---

#### S-02 — File encryption key load failure

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Critical (★) |
| Recipient | System Admin |
| Channels | E |
| Trigger | App startup |
| Reference | none |
| Variables | `{{environment}}`, `{{failureCause}}`, `{{runbookUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][SysAdmin] FILE_ENCRYPTION_KEY load failed (env: {{environment}})`
- **Body**: failure cause + runbook link. CTA: `Open runbook`.
- **Opt-out**: OPS — opt-out unavailable

---

#### S-03 — AI Service long outage

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Important (●) |
| Recipient | System Admin |
| Channels | E |
| Trigger | Health-check scheduler |
| Reference | none |
| Variables | `{{serviceName}}`, `{{containerStatus}}`, `{{gitCommit}}`, `{{outageDurationMinutes}}`, `{{logUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][SysAdmin] {{serviceName}} unhealthy for {{outageDurationMinutes}} min`
- **Body**: container state + git commit + log URL.
- **Opt-out**: OPS — opt-out unavailable

---

#### S-04 — DB backup failure

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Critical (★) |
| Recipient | System Admin |
| Channels | E |
| Trigger | Backup scheduler |
| Reference | none |
| Variables | `{{backupAtDisplay}}`, `{{failureReason}}`, `{{runbookUrl}}` |

**Email**
- **Subject**: `[LicenseKaki][SysAdmin] DB backup failed at {{backupAtDisplay}}`
- **Body**: failure reason + runbook.
- **Opt-out**: OPS — opt-out unavailable

---

#### S-05 — Admin M-* carbon copy

| Field | Value |
|---|---|
| Category | OPS |
| Severity | Important (●) |
| Recipient | System Admin |
| Channels | E + I |
| Trigger | Each M-* event |
| Reference | varies |
| Variables | varies |

Body identical to the source M-* event with subject prefix `[CC]`.

---

## §6. SLD_MANAGER — 7 cards

> SLD Manager experience is queue-driven. Most non-critical items are bundled into the SLD digest at 09:00 / 15:00 SGT (§8).

---

#### D-01 — New SLD order

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | SLD Manager |
| Channels | I (immediate) + E (digest) |
| Trigger | `SldOrderService.createOrder` (15-min debounce) |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{publicCode}}`, `{{kvaLabel}}`, `{{requestSummary}}`, `{{ctaUrl}}` |

**In-app**
- **Title**: `New SLD order: #{{publicCode}}`
- **Body**: `{{kvaLabel}} · {{requestSummary}}.`
- **Deep-link**: `/admin/sld/orders/{{publicCode}}`

**Email** — digest line:
> #{{publicCode}} — {{kvaLabel}}, {{requestSummary}}: `/admin/sld/orders/{{publicCode}}`

---

#### D-02 — Assigned as manager

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | SLD Manager (Self) |
| Channels | E + I |
| Trigger | `SldOrderService.assignManager` |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{managerName}}`, `{{publicCode}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Assigned to SLD order · #{{publicCode}}`
- **Body**: short summary + CTA.
- **Primary CTA**: `Open order` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Assigned · #{{publicCode}}`
- **Body**: `New SLD order in your queue.`
- **Deep-link**: `/admin/sld/orders/{{publicCode}}`

---

#### D-03 — Payment complete (start work)

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | SLD Manager |
| Channels | E + I |
| Trigger | `SldOrderService.acceptQuote` → PAID |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{managerName}}`, `{{publicCode}}`, `{{requirementsJsonUrl}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] SLD payment received — start work · #{{publicCode}}`
- **Body**:
  > Hello {{managerName}},
  >
  > Payment for SLD order **#{{publicCode}}** is in. The frozen requirements JSON is at: {{requirementsJsonUrl}}
- **Primary CTA**: `Open order` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Paid — start SLD on #{{publicCode}}`
- **Body**: `Requirements frozen. Begin drafting.`
- **Deep-link**: `/admin/sld/orders/{{publicCode}}`

---

#### D-04 — Quote rejected by applicant

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | SLD Manager |
| Channels | E + I (digest) |
| Trigger | `SldOrderService.rejectQuote` |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{publicCode}}`, `{{rejectReason?}}` |

**In-app**
- **Title**: `Quote rejected · #{{publicCode}}`
- **Body**: `{{rejectReason?}}`
- **Deep-link**: `/admin/sld/orders/{{publicCode}}`

**Email** — digest line:
> #{{publicCode}} — Quote rejected. Reason: {{rejectReason?}}.

---

#### D-05 — Revision requested

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | SLD Manager |
| Channels | E + I |
| Trigger | `SldOrderService.requestRevision` |
| Reference | `SldOrder#{publicCode}` |
| Variables | `{{managerName}}`, `{{publicCode}}`, `{{revisionNotes}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] SLD revision requested · #{{publicCode}}`
- **Body**:
  > {{managerName}}, the applicant requested revisions to **#{{publicCode}}**:
  >
  > > {{revisionNotes}}
- **Primary CTA**: `Open order` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Revision requested · #{{publicCode}}`
- **Body**: trim of `{{revisionNotes}}` (≤100 chars).
- **Deep-link**: `/admin/sld/orders/{{publicCode}}`

---

#### D-06 — Applicant completion confirmed

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Informational (○) |
| Recipient | SLD Manager |
| Channels | I (digest) |
| Trigger | `SldOrderService.confirmCompletion` |
| Reference | `SldOrder#{publicCode}` |

**In-app**
- **Title**: `Customer confirmed completion · #{{publicCode}}`
- **Body**: `Order closed.`
- **Deep-link**: `/admin/sld/orders/{{publicCode}}`

---

#### D-07 — Daily queue summary

See §8 digest card 2.

---

## §7. CONCIERGE_MANAGER — 9 cards

> Concierge manager experience is field-mode. Visit-related events are real-time; all others are bundled into the concierge digest at 15:00 SGT (§8).

---

#### C-01 — New concierge request

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Concierge Manager |
| Channels | E + I |
| Trigger | `ConciergeNotifier.notifySubmitted` (already implemented) |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{publicCode}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] New concierge request · #{{publicCode}}`
- **Pre-header**: `Begin first contact within the 24-hour SLA.`
- **Headline**: `New concierge request to handle.`
- **Body**:
  > A new concierge request **#{{publicCode}}** has been submitted. Open it to begin first contact within the 24-hour SLA.
- **Primary CTA**: `Open request` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `New concierge: #{{publicCode}}`
- **Body**: `A new request needs a manager.`
- **Deep-link**: `/concierge-manager/requests`

---

#### C-02 — Assigned as manager

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Concierge Manager (Self) |
| Channels | E + I |
| Trigger | Manager assigned |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{managerName}}`, `{{publicCode}}`, `{{applicantPhone?}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Assigned to concierge request · #{{publicCode}}`
- **Body**:
  > Hello {{managerName}},
  >
  > You're now the manager for concierge request **#{{publicCode}}**. Customer phone (where available): **{{applicantPhone?}}**.
  >
  > **24-hour SLA** clock has started — please log your first contact note in the app once you've called.
- **Primary CTA**: `Open request` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Assigned · #{{publicCode}}`
- **Body**: `Call customer within 24h.`
- **Deep-link**: `/admin/concierge/requests/{{publicCode}}`

---

#### C-03 — 24 h first-contact SLA imminent / breach

Mirror of M-04 but tone is direct ("you" rather than "team"). Subject: `[LicenseKaki][SLA] Concierge first-contact pending · #{{publicCode}}`.

---

#### C-04 — Applicant signed LOA

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Concierge Manager |
| Channels | E + I |
| Trigger | `LoaService.sign` |
| Reference | `ConciergeRequest#{publicCode}` |
| Variables | `{{managerName}}`, `{{publicCode}}`, `{{signedAtDisplay}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] LOA signed · #{{publicCode}}`
- **Body**:
  > {{managerName}}, the customer signed the LOA for **#{{publicCode}}** at **{{signedAtDisplay}}**. You can now proceed with filing.
- **Primary CTA**: `Open request` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `LOA signed · #{{publicCode}}`
- **Body**: `Proceed with filing.`
- **Deep-link**: `/admin/concierge/requests/{{publicCode}}`

---

#### C-05 — Expired Licence Order received

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Important (●) |
| Recipient | Concierge Manager |
| Channels | E + I |
| Trigger | `ExpiredLicenseOrderService.createOrder` |
| Reference | `ExpiredLicenceOrder#{publicCode}` |
| Variables | `{{publicCode}}`, `{{regionMasked}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] New Expired Licence order · #{{publicCode}}`
- **Body**: short summary + CTA.
- **Primary CTA**: `Open order` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `New Expired Licence: #{{publicCode}}`
- **Body**: `{{regionMasked}}.`
- **Deep-link**: `/admin/expired-licence/orders/{{publicCode}}`

---

#### C-06 — Expired Licence revisit requested

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Critical (★) |
| Recipient | Concierge Manager |
| Channels | E + I |
| Trigger | `ExpiredLicenseOrderService.requestRevisit` |
| Reference | `ExpiredLicenceOrder#{publicCode}` |
| Variables | `{{managerName}}`, `{{publicCode}}`, `{{revisitReason}}`, `{{ctaUrl}}` |

**Email**
- **Subject**: `[LicenseKaki] Revisit requested · #{{publicCode}}`
- **Body**:
  > {{managerName}}, the customer asked for a revisit on **#{{publicCode}}**:
  >
  > > {{revisitReason}}
- **Primary CTA**: `Open order` → `{{ctaUrl}}`
- **Opt-out**: STATUS — opt-out unavailable

**In-app**
- **Title**: `Revisit requested · #{{publicCode}}`
- **Body**: trim of `{{revisitReason}}` (≤100 chars).
- **Deep-link**: `/admin/expired-licence/orders/{{publicCode}}`

---

#### C-07 — Expired Licence applicant confirmed

| Field | Value |
|---|---|
| Category | STATUS |
| Severity | Informational (○) |
| Recipient | Concierge Manager |
| Channels | I (digest) |
| Trigger | Applicant `confirmCompletion` |
| Reference | `ExpiredLicenceOrder#{publicCode}` |

**In-app**
- **Title**: `Customer confirmed · #{{publicCode}}`
- **Body**: `Order closed.`
- **Deep-link**: `/admin/expired-licence/orders/{{publicCode}}`

---

#### C-08 — Daily visit summary

See §8 digest card 3.

---

#### C-09 — Visit arrival 30-min trigger

This is a **trigger** not a manager-facing notification — it sends A-40 to the applicant. The manager sees only an in-app confirmation: "Notified the customer."

---

## §8. Digest email templates

All digests follow the same shell:

```
Header: [LicenseKaki] (§1.1)
Section title: ROLE — DIGEST TYPE — DATE
Section: New since last digest (N items) — bullet list with deep-links
Section: Pending action (M items)
Section: This week's totals (KPI)
Footer: §1.2
```

---

#### D-01 — LEW digest (twice daily)

| Field | Value |
|---|---|
| Schedule | Daily 09:00 / 15:00 SGT |
| Recipient | LEW |
| Subject | `[Digest] {{newCount}} review tasks today · {{date}}` |

**Body**:
> Hello {{lewName}},
>
> Here's your queue snapshot.
>
> **New since last digest ({{newCount}})**
> {{newItemsBulleted}}  *(L-03, L-05, L-07, L-10 lines)*
>
> **Awaiting your action ({{pendingCount}})**
> {{pendingItemsBulleted}}
>
> **This week**: {{weekDoneCount}} done · avg {{avgReviewMinutes}} min per application.
- **Primary CTA**: `Open dashboard` → `{{dashboardUrl}}`
- **Opt-out**: REMINDER — digest is opt-out (defaults ON)

---

#### D-02 — SLD digest

| Field | Value |
|---|---|
| Schedule | Daily 09:00 / 15:00 SGT |
| Recipient | SLD Manager |
| Subject | `[Digest] SLD order queue · {{date}}` |

Body identical structure with sections "New orders", "Awaiting your draft", "Pending revisions". Bundles D-01, D-04, D-06.

---

#### D-03 — Concierge digest

| Field | Value |
|---|---|
| Schedule | Daily 15:00 SGT |
| Recipient | Concierge Manager |
| Subject | `[Digest] Concierge workload · {{date}}` |

Body sections: "Tomorrow's visits", "Pending first-contact", "Closed today". Bundles C-07 + non-field events.

---

#### D-04 — Admin ops digest

| Field | Value |
|---|---|
| Schedule | Daily 09:00 SGT |
| Recipient | Admin |
| Subject | `[Digest] Ops summary · {{date}}` |

Body sections: KPI block (intake, completion, breach counts). Bundles M-03, M-07 non-critical entries; serves as M-10.

---

#### D-05 — LEW daily summary

Already detailed as L-12 above (singleton digest).

---

## §9. Category footer blocks

These footer fragments are appended to the standard footer (§1.2) based on category.

### 9.1 SECURITY

> This is a security notification. It cannot be disabled — it's required to keep your account safe under PDPA §13 and our terms of service. If you suspect your account is compromised, write to security@licensekaki.sg.

### 9.2 PAYMENT

> This is a payment notification. It cannot be disabled while you have an active application or order. Verify any payment details against your dashboard before sending money — LicenseKaki will never request payment to a personal account.

### 9.3 REMINDER

> You're receiving this reminder because you have an outstanding action on LicenseKaki. Manage which reminders we send you in your **[notification preferences]({{preferenceCenterUrl}})**.

### 9.4 EXPIRY

> Licence expiry notices are sent regardless of preferences while you have an active licence — required to keep you compliant with SS 638 and EMA regulations.

### 9.5 REASSURANCE / FEEDBACK

> You can opt out of reassurance updates or feedback requests in your **[notification preferences]({{preferenceCenterUrl}})**.

### 9.6 MARKETING (with §ADV label)

> **[ADV]** This email is a promotional message from LicenseKaki Pte Ltd. You're receiving it because you opted in during sign-up or in your preferences.
>
> To stop these messages: reply STOP to this email, click [unsubscribe]({{unsubscribeUrl}}), or update your preferences at {{preferenceCenterUrl}}. We'll process your request within 30 days as required by Singapore's Spam Control Act.

### 9.7 OPS (Admin / SysAdmin)

> This is an operational notification routed to your role. Operational alerts cannot be disabled — disable a role's email in user management if a teammate no longer needs them.

---

## §10. Master variable index

| Variable | Source | Example | Fallback if empty |
|---|---|---|---|
| `{{applicantName}}` | `User.fullName` | `Tan Wei Ming` | `Customer` |
| `{{lewName}}` | `User.fullName` (LEW) | `Eng. Lim Jian Hong` | `LEW` |
| `{{managerName}}` | `User.fullName` (concierge or SLD) | `Sarah Lee` | `your manager` |
| `{{candidateName}}` | `User.fullName` (LEW applicant) | `Lim Soon Hock` | `Candidate` |
| `{{publicCode}}` | `Application.publicCode` / `*.publicCode` | `A-2026-1234` | (always required — never empty) |
| `{{licenceNumber}}` | `Application.licenseNumber` | `EMA/IL/2026/001234` | — |
| `{{licenceExpiryDate}}` | `Application.licenseExpiryDate` | `25 Apr 2027` | — |
| `{{installationAddressMasked}}` | `Application.installationAddress` (masked street #) | `Block 123, ████ Drive` | `your installation site` |
| `{{installationAddress}}` (LEW only) | full address | `Block 123, Sengkang Drive #04-12` | — |
| `{{visitAddress}}` | `*.visitAddress` (full, applicant-facing) | `Block 123, Sengkang Drive #04-12` | — |
| `{{kvaLabel}}` | `master_prices.label` (per CLAUDE.md — never hardcode) | `45 kVA TPN` | `your kVA tier` |
| `{{quotedAmount}}` / `{{amount}}` | `Application.quotedAmount` / `SldOrder.quotedAmount` | `350.00` | — |
| `{{paynowUen}}` | `system_settings.payment_paynow_uen` | (loaded at send time) | — |
| `{{paynowAccountName}}` | `system_settings.payment_paynow_name` | (loaded at send time) | — |
| `{{paynowReference}}` | derived = `publicCode` | `A-2026-1234` | — |
| `{{deadline}}` | event-specific | `25 Apr 2026, 17:00 SGT` | — |
| `{{visitAtDisplay}}` | `*.visitAt` | `25 Apr 2026, 14:00 SGT` | — |
| `{{date}}` | scheduler day | `25 Apr 2026` | — |
| `{{rejectionReason}}` / `{{revisionNotes}}` / `{{cancelReason}}` | LEW or admin input | (free text) | `(no reason given)` |
| `{{ctaUrl}}` | per card primary deep-link | `https://app.licensekaki.sg/applications/A-2026-1234` | — |
| `{{shortUrl}}` | URL shortener | `lk.sg/p/A-2026-1234` | — |
| `{{verificationPhrase}}` | `ConciergeQuote.verificationPhrase` | `tropical thunder maple banyan` | (mandatory; do not send if empty) |
| `{{managerPhone}}` | `User.phoneNumber` (manager) | `+65 9123 4567` | omit phone line entirely |
| `{{applicantPhone}}` | `User.phoneNumber` (applicant) | `+65 9123 4567` | `not on file` |
| `{{preferenceCenterUrl}}` | constant | `https://app.licensekaki.sg/account/preferences` | — |
| `{{companyAddress}}` | `system_settings.company_address` | (loaded at send time) | — |
| `{{supportEmail}}` | `system_settings.support_email` | `support@licensekaki.sg` | — |
| `{{unsubscribeUrl}}` | per-recipient signed token | `https://app.licensekaki.sg/u/<token>` | — |
| `{{onboardingUrl}}` | constant | `https://help.licensekaki.sg/lew-onboarding` | — |
| `{{runbookUrl}}` | per-incident link | `https://docs.licensekaki.sg/runbooks/smtp` | — |

**Rule**: every variable must be supplied at template render time. If `Required` and missing → fall back to safe text or skip the entire sentence (use `{{var?}}` syntax for optional). Adheres to CLAUDE.md §1 — values like `paynowUen`, `companyAddress`, `kvaLabel` come from `system_settings` / `master_prices`, never hardcoded.

---

## §11. Reviewer decision items (5)

The following decisions are out of scope for the copywriter and must be made by the product / legal / brand teams before this document is sealed.

1. **Tone register** — the document currently uses *formal-friendly* register ("Hello {{applicantName}}," "Good news"). Strategist had recommended "안심·친절·투명" for applicants but more austere for LEW/Admin. **Decide**: stick with mixed register (current draft), or apply a single register everywhere? Recommendation: keep mixed.
2. **From display name** — current draft assumes `LicenseKaki <noreply@licensekaki.sg>` (catalog §11). **Decide** between:
   - `LicenseKaki` (clean, brand-first)
   - `LicenseKaki Team` (warmer, may suggest human reply)
   - `LicenseKaki Notifications` (most explicit, may feel cold)
   Recommendation: `LicenseKaki` for transactional, `LicenseKaki Team` for welcome / onboarding / NPS only.
3. **Deadline format** — current draft uses **absolute** SGT timestamps (`25 Apr 2026, 17:00 SGT`). Some platforms prefer **relative** ("in 24 hours"). **Decide** which is the default; the template engine should support both. Recommendation: absolute everywhere except SMS where relative saves space.
4. **i18n scheduling** — Korean and Chinese (Simplified) translations are placeholders. **Decide** whether v1.0 ships English-only (Singapore official-language coverage) and translations are P2, or whether legal review of CN strings is required before any production deployment in case of multilingual recipients. Recommendation: ship English-only for v1.0.
5. **WhatsApp template registration** — Meta requires templates to be pre-registered in Business Manager before they can be sent. **Decide** whether this document doubles as the registration submission file (each WhatsApp section is structured for direct copy-in to Meta) or whether a separate "Meta WhatsApp Template Submission" doc is produced. Recommendation: this document IS the registration source — just extract the WhatsApp blocks into a Meta-formatted CSV at submission time.

---

## §12. Cross-references

- This document references catalog cards: **A-01 through A-54, L-01 through L-12, M-01 through M-10, S-01 through S-05, D-01 through D-07, C-01 through C-09** — all 97 alerts. (Note: catalog `D-*` IDs are SLD Manager events; the digest section in this doc reuses the labels D-01..D-05 for digest cards, which is purely a §8-local naming.)
- Strategy AC mapping: `notification-strategy.md` §7.2 AC-NOTIF-1/2/3 — every card respects category × channel preferences (§9 footers), digest bundling (§8), and Quiet Hours (catalog §10).
- Requirements AC: `notification-requirements.md` §5.1–§5.9 P0 specs all have a corresponding card with at least Email + In-app body.
- CLAUDE.md adherence:
  - **Single Source of Truth** — every variable in §10 reads from `system_settings`, `master_prices`, or domain entities; nothing hardcoded. Templates themselves should be seeded into `notification_template` table (planned).
  - **JIT** — copy never asks for already-known data; references like `{{applicantName}}` are loaded server-side.
- LEW review-flow context: `memory/lew-review-flow-roadmap.md` — A-17 Payment Required and L-06 Payment Confirmed are aligned with the recently merged "Request payment" trigger and PAID-after-CoF gating.
- Existing implementation cross-check (catalog ✓ items already coded in `EmailService`):
  - A-01 → `sendEmailVerificationEmail`
  - A-03 → `sendPasswordResetEmail`
  - A-12 → `sendDocumentRequestCreatedEmail`
  - A-13 → `sendDocumentRequestApprovedEmail`
  - A-14 → `sendDocumentRequestRejectedEmail` (Email + In-app done; SMS still TODO)
  - A-31 → `sendConciergeRequestReceivedEmail`
  - A-33 → `sendConciergeQuoteEmail`
  - A-36 → `sendConciergeLoaUploadConfirmEmail`
  These cards' copy in this doc must be reconciled against the inline HTML in `SmtpEmailService.java` during the seeding migration — current English text wins where the inline HTML drifts.

---

*End of document.*
