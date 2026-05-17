# LicenseKaki Master Notification Catalog

> **Date**: 2026-04-24
> **Position**: Single Source of Truth — consolidates all notifications (role × event × channel) into one reference
> **Source Documents**:
> - Requirements & AC: [`notification-requirements.md`](./notification-requirements.md) (Korean)
> - Strategy & Channels & Journeys: [`notification-strategy.md`](./notification-strategy.md) (Korean)
> - Korean catalog: [`notification-catalog.md`](./notification-catalog.md)
> **Purpose**: Define scope of the infrastructure sprint (SMS/WhatsApp gateway, phoneNumber JIT collection, Preference Center, Digest engine) and provide the build order reference.

---

## 0. Legend

| Abbrev | Meaning |
|--------|---------|
| **E** | Email |
| **I** | In-app notification (logged-in users / Notification table) |
| **S** | SMS |
| **W** | WhatsApp Business API |
| **★** | Critical — legal/financial/security mandatory, no opt-out, exempt from Quiet Hours |
| **●** | Important — journey progress info, opt-out by category allowed |
| **○** | Informational — reassurance, digest, reference |
| **M** | Marketing — explicit opt-in only, Spam Control Act §ADV labeling required |
| **✓** | Currently implemented |
| **∆** | Partial (some channels missing) |
| **✗** | Not implemented |

**Common Principles (apply to every notification)**
1. One message = one primary CTA (Stripe pattern)
2. No sensitive data (name, address, license #, amount) in Email subject / SMS body — route via platform link
3. Strict separation of Transactional vs Marketing — no promotional content in transactional alerts
4. In-app (I) is always created on every state transition — no opt-out (audit trail)
5. Quiet Hours (SGT 22:00–08:00): only Critical sends immediately; others queued for 08:00
6. Daily caps per user: E 5 / S 2 / W 4 — auto-switch to digest if exceeded
7. Idempotency: no duplicates within 30 min for the same `user_seq + reference_type + reference_id + type + channel`
8. Failure isolation: notification delivery failure must NEVER rollback business transaction (`afterCommit` hook + WARN log)

---

## 1. Channel Decision Matrix

| Condition | Required channels | Optional |
|-----------|------------------|----------|
| All state transitions (log) | **I** | — |
| External proof needed (payment, license, LOA) | **E** | — |
| <24h deadline + legal/financial consequence | **E + I** | S or W |
| On-site visit (Concierge, Expired License, LEW Service) | **E + I** | W (D-1) + S (30 min before arrival) |
| Signature request (LOA, e-doc) | **E + I** | W (reminder) |
| Admin internal operations | **I** | E (on SLA breach) |
| Security (password change, new device) | **E + I** | — |
| Marketing | **E (opt-in)** | W (opt-in) |

---

## 2. APPLICANT — 54 Notifications

### 2.1 Account & Security

| # | Event | Timing | Recipient | Channel | Severity | Purpose | Summary | CTA | Status |
|---|-------|--------|-----------|---------|:--------:|---------|---------|-----|:------:|
| A-01 | Email verification after signup | Right after signup | Self | E | ★ | Action | 24h valid link + 1-line service intro | `/verify-email?token=` | ✓ |
| A-02 | Welcome / onboarding | After verification | Self | E+I | ○ | Re-engage | "Start your first application" CTA | `/applications/new` | ✗ P2 |
| A-03 | Password reset link | forgot-password request | Self | E | ★ | Action | 1h valid reset link + identity check note | `/reset-password?token=` | ✓ |
| A-04 | Password change successful | resetPassword completed | Self | E | ★ | Security | "Your password was just changed. If this wasn't you…" + support contact | (none) | **✗ P0** |
| A-05 | New device / IP login detected | Login + novel UA fingerprint | Self | E | ● | Security | Time/IP/UA + "Not you?" link | `/security/sessions` | ✗ P2 |
| A-06 | Inactive account activation link | Login attempt on inactive account | Self | E | ★ | Action | Activation link + PDPA re-consent prompt | `/activate?token=` | ✓ |
| A-07 | Resend verification email | User request | Self | E | ● | Action | New verification link | `/verify-email?token=` | ✓ |

### 2.2 Application Main Flow

| # | Event | Timing | Recipient | Channel | Severity | Purpose | Summary | CTA | Status |
|---|-------|--------|-----------|---------|:--------:|---------|---------|-----|:------:|
| A-08 | **Application submitted — receipt** | `createApplication` success | Self | **E+I** | ● | Info | Application #, address, expected LEW review window (24–72h) + PDF attached | `/applications/{id}` | **✗ P0** |
| A-09 | Draft saved (abandonment reminder) | D+1 / D+3 | Self | E | ○ | Re-engage | "You have a draft in progress" — max 2 reminders | `/applications/{id}/edit` | ✗ P2 |
| A-10 | LEW assigned | `assignLew` success | Self | **E+I** | ● | Info | "Your reviewer {name} has been assigned" + expected timeline | `/applications/{id}` | ∆ P1 |
| A-11 | kVA confirmed | `confirmKva` | Self | **E+I** | ● | Info | Confirmed kVA + fixed fee quote | `/applications/{id}` | ∆ (I only) P1 |
| A-12 | Document request created by LEW | LEW creates request | Self | E+I | ● | Action | Required documents list + submission deadline | `/applications/{id}#documents` | ✓ |
| A-13 | Documents approved by LEW | LEW approval | Self | E+I | ● | Info | Approved docs + remaining required list | `/applications/{id}#documents` | ✓ |
| A-14 | **Documents rejected by LEW** | LEW rejection | Self | **E+I+S** | ★ | Urgent action | Rejection reason + re-upload request + 24h SLA | `/applications/{id}#documents` | ∆ (no S) P1 |
| A-15 | **Revision requested (REVISION_REQUESTED)** | `requestRevision` | Self | **E+I** (+ W opt-in) | ★ | Action | Full revision notes + submit CTA | `/applications/{id}/edit` | ∆ (no I) P1 |
| A-16 | Revision not submitted reminder | D+2 / D+5 | Self | E (+ W D+5 only) | ● | Nudge | "Auto-cancelled on D+7" — max 2 | `/applications/{id}/edit` | ✗ P1 |
| A-17 | **Payment required (PENDING_PAYMENT)** | `approveForPayment` | Self | **E+I** | ★ | Action | PayNow UEN / reference code / amount / deadline + anti-phishing note | `/applications/{id}/payment` | ∆ (no I) P0 |
| A-18 | Payment due D-3 reminder | Scheduler | Self | E | ● | Nudge | Deadline restated + PayNow reminder | `/applications/{id}/payment` | ✗ P1 |
| A-19 | **Payment due D-1 reminder** | Scheduler | Self | **E+S** (+ W opt-in) | ★ | Urgent nudge | 24h countdown — SMS 160 chars, short URL | `lk.sg/p/{code}` | ✗ P0 |
| A-20 | **Payment confirmed (PAID)** | `confirmPayment` | Self | **E+I** | ● | Info | Receipt PDF + "Work will now begin" | `/applications/{id}` | ∆ (no I) P0 |
| A-21 | In-progress reassurance | IN_PROGRESS stable D+3 | Self | E | ○ | Reassurance | "Your LEW is working on-site" — once, flag required | `/applications/{id}` | ✗ P2 |
| A-22 | **License issued (COMPLETED)** | `completeApplication` | Self | **E+I+S** (+ W opt-in) | ★ | Info critical | License #, expiry date, PDF — SMS conveys issuance fact only | `/applications/{id}/licence` | ∆ (no S/I) P0 |
| A-23 | Admin manual status change | `updateStatus` | Self | I (+ optional E) | ● | Info | Before/after state + reason (if any) | `/applications/{id}` | ✗ P1 |

### 2.3 License Expiry Lifecycle

| # | Event | Timing | Recipient | Channel | Severity | Purpose | Summary | CTA | Status |
|---|-------|--------|-----------|---------|:--------:|---------|---------|-----|:------:|
| A-24 | Expiry D-90 advance notice | Scheduler | Self | E | ○ | Info | Expiry date + renewal guidance | `/applications/{id}/renew` | ✗ P1 |
| A-25 | Expiry D-60 reminder | Scheduler | Self | E | ● | Nudge | "You can renew now" | `/applications/{id}/renew` | ✗ P1 |
| A-26 | Expiry D-30 warning | Scheduler | Self | E+I | ● | Warning | Deadline + Expired License visit service recommendation | `/applications/{id}/renew` | ∆ (no I) |
| A-27 | Expiry D-7 warning | Scheduler | Self | **E+W** (opt-in) | ★ | Urgent | Expires in 1 week | `/applications/{id}/renew` | ✗ P0 |
| A-28 | Expiry D-1 final warning | Scheduler | Self | E+S | ★ | Urgent | Expires tomorrow | `lk.sg/r/{code}` | ✗ P0 |
| A-29 | **License auto-EXPIRED transition** | Scheduler flips to EXPIRED | Self | **E+I** | ★ | Warning | Now expired + visit renewal service CTA | `/orders/expired-licence/new` | **✗ P0** |
| A-30 | Post-expiry D+1 service pitch | Scheduler | Self | E | ○ (Marketing-ish) | Re-engage | Expired License Order promo — once | `/orders/expired-licence/new` | ✗ P2 |

### 2.4 Kaki Concierge (v1.5)

| # | Event | Timing | Recipient | Channel | Severity | Purpose | Summary | CTA | Status |
|---|-------|--------|-----------|---------|:--------:|---------|---------|-----|:------:|
| A-31 | Concierge request received | `notifySubmitted` | Self | E+I (+ account link) | ● | Info | Request code + promised 24h contact + account setup link (C3) | `/concierge/requests/{code}` | ✓ |
| A-32 | **Concierge manager assigned** | Manager assigned | Self | **E+I** | ● | Info | Manager name + expected contact time | `/concierge/requests/{code}` | **✗ P1** (enum unused) |
| A-33 | Concierge quote sent | `notifyQuoteSent` (after call) | Self | E+I | ★ | Action | Verification phrase + quote amount + PayNow | (PayNow) | ✓ |
| A-34 | **Concierge LOA signature request** | `generateLoa` | Self | **E+I+S** (+ W opt-in) | ★ | Action | 72h valid signature link — SMS shortened URL only | `/loa/{token}` | **✗ P0** (enum unused) |
| A-35 | LOA reminder | 48h unsigned | Self | E+S | ★ | Urgent nudge | 72h expiry warning | `/loa/{token}` | ✗ P0 |
| A-36 | LOA proxy-upload confirmation | Manager uploads | Self | E | ★ | Legal notice | Upload fact + 7-day objection window | `mailto:support@…` | ✓ |
| A-37 | **Concierge licence-fee payment request** | viaConcierge + PENDING_PAYMENT | Self | **E+I** | ★ | Action | Manager name included + separate from A-17 body | `/applications/{id}/payment` | **✗ P0** (enum unused) |
| A-38 | **Concierge visit scheduled** | `scheduleVisit` | Self | **E+I+S** | ★ | Info critical | Date/address/manager info + iCal (.ics) attached | `/concierge/requests/{code}` | **✗ P0** |
| A-39 | Visit D-1 reminder | Scheduler 09:00 SGT | Self | **S** (+ W) | ● | Nudge | Tomorrow's visit + manager tel: link | `lk.sg/v/{code}` | ✗ P0 |
| A-40 | Visit arrival 30 min away | Manager triggers | Self | **S+W** | ● | Real-time | "Your manager is on the way" + contact | (tel:) | ✗ P1 |
| A-41 | Concierge visit completed (photos) | `uploadVisitPhotos` | Self | **E+I** | ● | Action confirm | On-site photos + work summary + completion confirmation request | `/concierge/requests/{code}/confirm` | ✗ P1 |
| A-42 | Concierge final completion | COMPLETED | Self | **E+I** (+ W) | ● | Info | Thank-you + NPS preview | `/concierge/requests/{code}` | **✗ P1** (enum unused) |
| A-43 | Concierge cancelled | Manager/Admin cancels | Self | **E+I** | ● | Info | Cancel reason + refund procedure | `/concierge/requests/{code}` | **✗ P1** (enum unused) |

### 2.5 SLD Order

| # | Event | Timing | Recipient | Channel | Severity | Purpose | Summary | CTA | Status |
|---|-------|--------|-----------|---------|:--------:|---------|---------|-----|:------:|
| A-44 | **SLD quote proposed** | `proposeQuote` | Self | **E+I** | ★ | Action | Amount / validity / PayNow | `/orders/sld/{id}` | **✗ P1** |
| A-45 | SLD quote reminder | D-3 / D-1 | Self | E (+ W D-1) | ● | Nudge | Validity expiry warning | `/orders/sld/{id}` | ✗ P1 |
| A-46 | **SLD drawing uploaded** | `uploadSld` | Self | **E+I** (+ W opt-in) | ● | Action confirm | Preview link + review request | `/orders/sld/{id}` | **✗ P1** |
| A-47 | SLD order completed | `markComplete` | Self | E+I | ● | Info | DXF/PDF attached + usage guide | `/orders/sld/{id}` | ✗ P2 |

### 2.6 Expired License Order (On-site visit)

| # | Event | Timing | Recipient | Channel | Severity | Purpose | Summary | CTA | Status |
|---|-------|--------|-----------|---------|:--------:|---------|---------|-----|:------:|
| A-48 | **Expired License quote proposed** | `proposeQuote` | Self | **E+I** | ★ | Action | Quote + available visit slots | `/orders/expired-licence/{id}` | **✗ P1** |
| A-49 | **Expired License visit scheduled** | `scheduleVisit` | Self | **E+I+S** | ★ | Info critical | iCal attached + manager info | `/orders/expired-licence/{id}` | **✗ P0** |
| A-50 | Expired License visit D-1 reminder | Scheduler | Self | **S** (+ W) | ● | Nudge | Tomorrow's visit confirmation | (short URL) | ✗ P0 |
| A-51 | Visit check-in | `checkIn` (manager) | Self | I | ○ | Info | "Manager has arrived" | `/orders/expired-licence/{id}` | ✗ P2 |
| A-52 | **Visit completed** | `uploadVisitPhotos` | Self | **E+I** | ● | Action confirm | Photos / diagnosis / completion confirmation | `/orders/expired-licence/{id}/confirm` | **✗ P1** |
| A-53 | Expired License final completion | Confirmation | Self | E+I | ● | Info | Thank-you + receipt | `/orders/expired-licence/{id}` | ✗ P2 |

### 2.7 Feedback

| # | Event | Timing | Recipient | Channel | Severity | Purpose | Summary | CTA | Status |
|---|-------|--------|-----------|---------|:--------:|---------|---------|-----|:------:|
| A-54 | NPS survey request | Completion D+3 | Self | E+I | ○ | Re-engage | 5-point 1-click — once only | `/feedback/{token}` | ✗ P2 |

---

## 3. LEW (Licensed Electrical Worker) — 12 Notifications

> **Experience principle**: Workflow efficiency focus. Important/Informational → **digest at 09:00 / 15:00 SGT**, Critical only real-time.

| # | Event | Timing | Channel | Severity | Digest | Purpose | Summary | Status |
|---|-------|--------|---------|:--------:|:------:|---------|---------|:------:|
| L-01 | **LEW registration approved** | `approveLew` | **E+I** | ★ | No | Info | Dashboard CTA + onboarding guide | **✗ P0** |
| L-02 | **LEW registration rejected** | `rejectLew` (reason required) | **E** | ★ | No | Action | Rejection reason + how to reapply | **✗ P0** |
| L-03 | Application assigned | I immediate / E digest with 10 min debounce | I + E(Digest) | ● | Yes | Work item | Application # / address / est. time | ∆ (no I) P1 |
| L-04 | Unassigned | `unassignLew` | **E+I** | ● | No | Info | Unassign reason | **✗ P1** |
| L-05 | Documents uploaded (review needed) | 15 min debounce | I + E(Digest) | ● | Yes | Work trigger | Uploaded docs list | ✓ |
| L-06 | Payment confirmed (work can start) | `confirmPayment` | **E+I** | ● | No | Work trigger | Site address + contact + ready state | ✓ |
| L-07 | Revision resubmission received | `resubmit` | I + E(Digest) | ● | Yes | Work resume | Resubmitted content | **✗ P1** |
| L-08 | **SLA warning (24h no action)** | Scheduler | **E+I** | ★ | No | Nudge | Pending item + deadline | **✗ P1** |
| L-09 | **SLA breach (48h+)** | Scheduler | **E+I** (+ Admin CC) | ★ | No | Escalation | Notify Admin simultaneously | ✗ P0 |
| L-10 | LEW Service Order events | Per state transition | E+I | ● | Yes (non-urgent) | Field work | Booking / changes / completion | **✗ P1** |
| L-11 | Field work scheduled (LEW → Applicant) | LEW enters date | (trigger for applicant send) | ● | No | Cross-notif | — | ✗ P1 |
| L-12 | **Daily closing summary** | Daily 18:00 SGT | E | ○ | (self) | Recap | N done today / M pending | ✗ P2 |

---

## 4. ADMIN — 10 Notifications

> **Experience principle**: Signal-to-noise is paramount. Normal flow → in-app log only. **SLA / errors / breach** → email push.

| # | Event | Timing | Channel | Severity | Purpose | Summary | Status |
|---|-------|--------|---------|:--------:|---------|---------|:------:|
| M-01 | **New Application received** | `createApplication` | I (+ optional E) | ● | Work queue | App # / kVA / assignment candidate | **✗ P0** |
| M-02 | **New LEW registration** | Signup with LEW role | **E+I** | ● | Approval work | Candidate info + credential summary | **✗ P0** |
| M-03 | New concierge request | `notifySubmitted` | E+I | ● | Work queue | PDPA note — subject excludes customer name | ✓ |
| M-04 | **Concierge 24h SLA breach** | Hourly scheduler | **E+I** | ★ | Escalation | Request code + Manager + elapsed hrs | **✗ P0** (enum unused) |
| M-05 | LEW SLA breach CC | Synced with L-09 | **E+I** | ★ | Escalation | Application + LEW info | ✗ P0 |
| M-06 | Payment / PayNow match failure | Matching scheduler | **E+I** | ★ | Anomaly | Applicant / amount / reference code | ✗ P1 |
| M-07 | Invoice auto-generation failure | `invoiceGenerationService` | I (+ E) | ● | Anomaly | Reason + retry outcome | ∆ |
| M-08 | **Data breach alert** | `DataBreachService` | **E+I** | ★ | Legal response | PDPA §26D 3-day notification obligation | ✗ P1 |
| M-09 | LEW license auto-expiry detected | Scheduler | E+I | ● | Admin | Expired LEW list | ✗ P1 |
| M-10 | Daily operations digest | Daily 09:00 SGT | E | ○ | Monitoring | Intake / completion / SLA metrics | ✗ P2 |

---

## 5. SYSTEM_ADMIN — 5 Notifications

| # | Event | Timing | Channel | Severity | Purpose | Summary | Status |
|---|-------|--------|---------|:--------:|---------|---------|:------:|
| S-01 | System failure (SMTP fail rate >5%) | Metrics threshold | **E** (+ optional Slack) | ★ | Ops alert | Recent N min fail rate + log link | ✗ P1 |
| S-02 | File encryption key load failure | App startup | **E** | ★ | Ops alert | Env name + failure cause | ✗ P1 |
| S-03 | AI Service long outage | Health-check scheduler | E | ● | Ops alert | Container state + git commit | ✗ P2 |
| S-04 | DB backup failure | Backup scheduler | **E** | ★ | Ops alert | Backup time + failure reason | ✗ P1 |
| S-05 | ADMIN M-* carbon copy (optional) | Each M-* event | E+I | ● | Auth backup | Same body | ✗ P2 |

---

## 6. SLD_MANAGER — 7 Notifications

> **Experience principle**: "What order do I work on now?" — queue processing. Digest-first.

| # | Event | Timing | Channel | Severity | Digest | Purpose | Status |
|---|-------|--------|---------|:--------:|:------:|---------|:------:|
| D-01 | **New SLD Order** | `createOrder` 15 min debounce | I + E(Digest) | ● | Yes | Work queue | **✗ P0** |
| D-02 | Assigned as manager | `assignManager` | **E+I** | ● | No | Work assignment | **✗ P1** |
| D-03 | **Payment complete (start work)** | `acceptQuote` → PAID | **E+I** | ● | No | Work trigger | **✗ P0** |
| D-04 | Quote rejected by applicant | `rejectQuote` | E+I | ● | Yes | Lost opportunity | **✗ P1** |
| D-05 | **Revision requested** | `requestRevision` (SLD) | **E+I** | ● | No | Work resume | **✗ P0** |
| D-06 | Applicant completion confirmed | `confirmCompletion` | I | ○ | Yes | Record only | **✗ P2** |
| D-07 | Daily queue summary | Daily 08:00 SGT | E | ○ | (self) | Queue visibility | ✗ P2 |

---

## 7. CONCIERGE_MANAGER — 9 Notifications

> **Experience principle**: Field-mode — highest WhatsApp usage. Visit-related immediate, others digest.

| # | Event | Timing | Channel | Severity | Digest | Purpose | Status |
|---|-------|--------|---------|:--------:|:------:|---------|:------:|
| C-01 | New concierge request | `notifySubmitted` | E+I | ● | No | Work queue | ✓ |
| C-02 | **Assigned as manager** | Manager designated | **E+I** | ● | No | Work assignment | **✗ P1** (enum unused) |
| C-03 | **24h first-contact SLA imminent/breach** | Scheduler | **E+I** | ★ | No | Escalation | **✗ P0** (enum unused) |
| C-04 | Applicant signed LOA | `LoaService.sign` | **E+I** | ● | No | Next-step trigger | **✗ P1** |
| C-05 | **Expired License Order received** | `createOrder` | **E+I** | ● | No | Work queue | **✗ P0** |
| C-06 | **Expired License revisit requested** | `requestRevisit` | **E+I** | ★ | No | Work resume | **✗ P0** |
| C-07 | Expired License applicant confirmed | `confirmCompletion` | I | ○ | Yes | Record | **✗ P2** |
| C-08 | **Daily visit summary** | Daily 08:00 SGT | E (+ optional W) | ○ | (self) | Field planning | ✗ P1 |
| C-09 | Visit arrival 30min trigger | Manager button | (triggers Applicant S+W) | ● | No | Real-time | ✗ P1 |

---

## 8. Scheduled / Reminder Notifications

| Scheduler | Period | Recipients | Linked IDs | Implementation |
|-----------|--------|-----------|-----------|----------------|
| LicenseExpiryScheduler | Daily | Applicant | A-24 D-90, A-25 D-60, A-26 D-30, A-27 D-7, A-28 D-1, A-29 EXPIRED, A-30 D+1 pitch | Separate flags (`expiryNotifiedAt90/60/30/7/1`, `expiredNotifiedAt`) |
| PaymentDueScheduler | Daily | Applicant | A-18 D-3, A-19 D-1 | Based on `Application.paymentDueAt` |
| RevisionReminderScheduler | Daily | Applicant | A-16 (D+2, D+5) | Based on `revisionRequestedAt`, max 2 |
| ConciergeSlaScheduler | Hourly | Admin + Concierge Manager | C-03 / M-04 | First `ConciergeNote` presence |
| LewSlaScheduler | Hourly | LEW + Admin | L-08 (24h), L-09 (48h) | Based on `assignedAt` |
| VisitReminderScheduler | Daily 09:00 SGT | Applicant | A-39, A-50 (D-1 visit) | Based on `ConciergeRequest/ExpiredLicenseOrder.visitAt` |
| LoaReminderScheduler | Hourly | Applicant | A-35 (48h unsigned) | Based on `Loa.generatedAt` |
| DigestScheduler | Daily 09:00 / 15:00 SGT | LEW, SLD_MANAGER, CONCIERGE_MANAGER | L-03, L-05, L-07, D-01, D-04, D-06, C-07 | `notification_digest_batch` bundling |
| DailySummaryScheduler | Daily 08:00 SGT | SLD_MANAGER, CONCIERGE_MANAGER | D-07, C-08 | Aggregates queue / in-progress / done |
| LewDailySummaryScheduler | Daily 18:00 SGT | LEW | L-12 | Today done / pending |
| AdminDailyDigestScheduler | Daily 09:00 SGT | ADMIN | M-10 | KPI snapshot |
| SystemHealthScheduler | Every 15 min | SYSTEM_ADMIN | S-01 (SMTP), S-03 (AI), S-04 (DB backup) | Threshold-based |

---

## 9. Digest Bundling Rules

| Role | Bundled events | Debounce | Send time (SGT) | Cap | Email subject |
|------|---------------|----------|-----------------|-----|---------------|
| LEW | L-03, L-05, L-07, L-10 (non-critical) | 10–15 min | 09:00 / 15:00 | 50 per batch | `[Digest] N review tasks today` |
| SLD_MANAGER | D-01, D-04, D-06 | 15 min | 09:00 / 15:00 | 30 per batch | `[Digest] SLD order queue` |
| CONCIERGE_MANAGER | C-07 + non-field events | 15 min | 15:00 | 20 per batch | `[Digest] Concierge workload` |
| ADMIN | M-03, M-07 (non-critical) | 30 min | 09:00 | — | `[Digest] Ops summary` |

**Critical exception**: ★ events bypass debounce and send immediately.

---

## 10. Category × Channel Opt-Out Matrix (Applicant default)

| Category | In-app | Email | SMS | WhatsApp | Example IDs |
|----------|:------:|:-----:|:---:|:--------:|-------------|
| SECURITY | 🔒 ON | 🔒 ON | optional | — | A-04, A-05 |
| STATUS | 🔒 ON | 🔒 ON | — | optional | A-08, A-10, A-23 |
| PAYMENT | 🔒 ON | 🔒 ON | optional | optional | A-17, A-19, A-20, A-37 |
| REMINDER | ON/OFF | ON/OFF | ON/OFF | ON/OFF | A-16, A-18, A-35, A-45 |
| VISIT | 🔒 ON | 🔒 ON | optional | optional | A-38, A-39, A-40, A-49, A-50 |
| REASSURANCE | ON/OFF | ON/OFF | — | — | A-21 |
| EXPIRY | 🔒 ON | 🔒 ON | — | optional | A-24–A-29 |
| MARKETING | — | OFF (default) | OFF (default) | OFF (default) | A-30 (borderline), newsletter |
| FEEDBACK | ON/OFF | ON/OFF | — | — | A-54 |

🔒 = Critical/Transactional, no opt-out. Complies with PDPA §13 / Spam Control Act.

---

## 11. Content Template — Common Elements

All templates follow this structure:

**Email**
- From: `LicenseKaki <noreply@licensekaki.sg>` (reserved address)
- Reply-To: by category (`support@`, `concierge@`, `billing@`)
- Subject: no sensitive data — reference code only, e.g. `[LicenseKaki] {event} · #{publicCode}`
- Body blocks: ① greeting ② 1-sentence headline ③ detail ④ **single primary CTA button** ⑤ secondary link (dashboard) ⑥ footer (address / opt-out link / anti-phishing)
- Footer opt-out: omitted on legally-required notifications

**SMS (≤160 chars)**
- Format: `[LicenseKaki] {action} {detail}. {shortUrl}`
- Example: `[LicenseKaki] Payment due 25 Apr. Pay: lk.sg/p/A1B2`
- No sensitive data — short URL routes to platform
- "Reply STOP to unsubscribe" required on marketing SMS

**WhatsApp (Business Template)**
- Only Meta-pre-approved templates
- Rich format: image/PDF + up to 3 buttons
- Outside the 24h conversation window, only templates work
- Two-way conversation is Phase 2 (Concierge Manager only)

**In-app**
- Title: i18n keyed by `type`
- Body: ≤100 char snippet
- Metadata: `referenceType`, `referenceId` required (for deep-link)
- Read tracking (`readAt`)

---

## 12. Build Priority Summary

### P0.5 — Infrastructure First (MUST precede P0)
- [ ] **SMS gateway** — Twilio or AWS SNS, `SmsService` interface mirroring `EmailService`
- [ ] **WhatsApp Business API** — Meta Cloud API or Twilio, `WhatsAppService` interface, pre-approve templates
- [ ] **User.phoneNumber column + JIT capture** — prompt during Expired License / LOA / visit flows
- [ ] **Preference Center data model** — `user_notification_preferences`, `notification_category`, `notification_severity`
- [ ] **Digest engine** — `notification_digest_batch` table + `DigestScheduler`
- [ ] **Quiet Hours queuing** — time-of-day check before dispatch + queuing logic
- [ ] **Idempotency key** — `idempotency_key` column + uniqueness index
- [ ] **notification_delivery log** — per-channel send/failure/delivery-report rows
- [ ] **iCal generator util** — for visit emails

### P0 — Legal / Payment / Security / SLA Critical (right after infra)
| Group | IDs |
|-------|-----|
| Application core | A-08 (receipt), A-17 (pay-req I), A-19 (D-1 SMS), A-20 (confirm I), A-22 (issued S+I) |
| Security | A-04 (pw-change notice) |
| LEW mgmt | L-01, L-02, L-09 (+ M-05) |
| Admin queue | M-01, M-02, M-04 |
| Expiry | A-27 (D-7), A-28 (D-1), A-29 (EXPIRED transition) |
| Concierge urgent | A-34 (LOA), A-35 (LOA reminder), A-37 (pay-req), A-38 (visit sched), A-39 (D-1 reminder) |
| Expired License | A-49 (visit sched), A-50 (D-1), C-05, C-06 |
| SLD | D-01, D-03, D-05 |

### P1 — Operational Improvements
- Application: A-10, A-11 (I boost), A-14 (S boost), A-15 (I boost), A-16, A-18, A-23, A-24–A-26
- LEW: L-03 (I), L-04, L-07, L-08, L-10, L-11
- Concierge: A-32, A-40, A-41, A-42, A-43, C-02, C-04, C-08, C-09
- SLD: A-44, A-45, A-46, D-02, D-04
- Expired License: A-48, A-52
- Admin: M-06, M-08, M-09
- SYSTEM_ADMIN: S-01, S-02, S-04

### P2 — UX Extras / Options
- A-02, A-05, A-09, A-21, A-30, A-47, A-51, A-53, A-54
- L-12, D-06, D-07, C-07, M-07, M-10, S-03, S-05
- User-facing Preference Center UI

---

## 13. Totals

| Role | Notification count |
|------|-------------------|
| APPLICANT | 54 |
| LEW | 12 |
| ADMIN | 10 |
| SYSTEM_ADMIN | 5 |
| SLD_MANAGER | 7 |
| CONCIERGE_MANAGER | 9 |
| **Grand total** | **97** (including overlaps & cross-role) |

**Currently implemented**: 22 full (✓), 6 partial (∆) — roughly **23%** of catalog
**New needed**: **69** (P0: 27 / P1: 30 / P2: 12)

---

## 14. Reference Documents

- PM spec (AC & technical requirements): [`notification-requirements.md`](./notification-requirements.md) (Korean)
- Strategy (channel / journey / benchmarks): [`notification-strategy.md`](./notification-strategy.md) (Korean)
- Korean catalog mirror: [`notification-catalog.md`](./notification-catalog.md)
- Concierge v1.5 PRD §6: `doc/Project Analysis/kaki-concierge-service-prd.md`
- LEW Service redesign: `doc/Project Analysis/lew-service-visit-redesign-spec.md`
- Current EmailService interface: `blue-light-backend/src/main/java/com/bluelight/backend/api/email/EmailService.java`
- Current NotificationType enum: `blue-light-backend/src/main/java/com/bluelight/backend/domain/notification/NotificationType.java`
- Reference Notifier pattern: `blue-light-backend/src/main/java/com/bluelight/backend/api/document/DocumentRequestNotifier.java`
