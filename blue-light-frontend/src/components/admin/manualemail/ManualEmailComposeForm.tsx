import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button } from '../../ui/Button';
import { Input } from '../../ui/Input';
import { Textarea } from '../../ui/Textarea';
import { Select } from '../../ui/Select';
import { ConfirmDialog } from '../../ui/ConfirmDialog';
import { useToastStore } from '../../../stores/toastStore';
import { useAuthStore } from '../../../stores/authStore';
import {
  getManualEmailCategorySuggestions,
  sendManualEmail,
} from '../../../api/adminManualEmailApi';
import adminApi from '../../../api/adminApi';
import { SystemUserPicker } from './RecipientPicker';
import { ApplicationPicker } from './ApplicationPicker';
import { ManualEmailPreviewModal } from './ManualEmailPreviewModal';
import type {
  RecipientType,
  SendManualEmailRequest,
} from '../../../types/manualEmail';
import type { AdminApplication, User } from '../../../types';

/**
 * ADMIN 수동 이메일 Compose 폼 (PR-3).
 *
 * <p>스펙: doc/Project Analysis/admin-manual-email-spec.md §7.2.1.</p>
 *
 * <h3>주요 동작</h3>
 * <ul>
 *   <li>Recipient type radio (APPLICANT / LEW / EXTERNAL / MULTI) — 선택에 따라 입력 영역 변형.</li>
 *   <li>Recipients chip — 추가/제거. MULTI 합계 2~100 검증.</li>
 *   <li>Subject 200자 / Body 50,000자 카운터.</li>
 *   <li>Preview 버튼 → POST /preview → 모달 (백엔드가 본문 자동 escape + 자동 푸터 부착).</li>
 *   <li>Send 버튼 → 확인 다이얼로그 → POST → toast → onSent 콜백 (History 탭으로 전환).</li>
 *   <li>409 MANUAL_EMAIL_DUPLICATE_SUSPECTED → "Same email was sent recently. Send anyway?" confirm
 *       → forceDuplicate=true 로 단 1회 재호출 (무한 루프 금지).</li>
 * </ul>
 */

interface ManualEmailComposeFormProps {
  /** 발송 성공 시 — 부모(History 탭 자동 이동 + quota 갱신). */
  onSent: () => void;
}

const SUBJECT_MAX = 200;
const BODY_MAX = 50_000;
const MULTI_MIN = 2;
const MULTI_MAX = 100;

/**
 * 카테고리 추천값 폴백 — PR-4 부터 system_settings 에서 로드.
 *
 * 설정 우선 원칙 예외: API 실패/네트워크 단절 시 UI 가 fallback 으로 사용.
 *   사유: 백엔드 미응답 시에도 ADMIN 이 발송 자체는 가능해야 함 (자유 입력 허용 + 추천만 폴백).
 *   백엔드 ManualEmailSettings.DEFAULT_CATEGORY_SUGGESTIONS 와 동일 4개로 양쪽 일치.
 */
const CATEGORY_SUGGESTIONS_FALLBACK = ['PAYMENT_NOTICE', 'MAINTENANCE', 'INFO', 'MISC'];

interface MultiUserChip {
  userSeq: number;
  email: string;
  name: string;
  role: 'APPLICANT' | 'LEW';
}

interface MultiExternalChip {
  email: string;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function ManualEmailComposeForm({ onSent }: ManualEmailComposeFormProps) {
  const toast = useToastStore();
  const { user } = useAuthStore();
  const [searchParams, setSearchParams] = useSearchParams();

  const [recipientType, setRecipientType] = useState<RecipientType>('APPLICANT');
  // 단일 시스템 사용자 (APPLICANT/LEW)
  const [singleUser, setSingleUser] = useState<User | null>(null);
  // EXTERNAL 단일 이메일
  const [externalEmail, setExternalEmail] = useState('');
  // MULTI: 시스템 사용자 chip
  const [multiUsers, setMultiUsers] = useState<MultiUserChip[]>([]);
  // MULTI: 외부 이메일 chip
  const [multiExternals, setMultiExternals] = useState<MultiExternalChip[]>([]);
  const [externalDraft, setExternalDraft] = useState('');

  const [relatedApplication, setRelatedApplication] = useState<AdminApplication | null>(null);
  const [subject, setSubject] = useState('');
  const [bodyText, setBodyText] = useState('');
  const [categoryTag, setCategoryTag] = useState('');
  // PR-4 (D4=B): 시스템 사용자 수신자에게 인앱 알림 동반 — 기본 ON.
  const [alsoCreateInApp, setAlsoCreateInApp] = useState<boolean>(true);

  // PR-4 (§13.3): system_settings 에서 추천 카테고리 로드.
  const [categorySuggestions, setCategorySuggestions] = useState<string[]>(
    CATEGORY_SUGGESTIONS_FALLBACK
  );

  const [submitting, setSubmitting] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [duplicateConfirmOpen, setDuplicateConfirmOpen] = useState(false);
  const [duplicateMessage, setDuplicateMessage] = useState<string>('');
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  // ── PR-4: 마운트 시 카테고리 추천 로드 (system_settings) + URL prefill ───
  useEffect(() => {
    let cancelled = false;
    getManualEmailCategorySuggestions()
      .then((list) => {
        if (cancelled) return;
        if (list.length > 0) setCategorySuggestions(list);
      })
      .catch(() => {
        // API 실패 — fallback 유지. 자유 입력은 항상 허용되므로 발송 자체에 영향 없음.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // ── PR-4: URL query 기반 prefill — 신청 상세 "Send manual email" 진입점에서 활용 ───
  // 지원 파라미터:
  //   ?related={applicationSeq} — 신청 컨텍스트 자동 채움
  //   &recipientType=APPLICANT|LEW|EXTERNAL|MULTI — recipient type
  //   &recipientUserSeq={userSeq} — APPLICANT/LEW 단일 수신자 (시스템 사용자 lookup)
  // 한 번 prefill 한 후 query 는 제거 — 재마운트/뒤로가기 시 중복 적용 방지.
  useEffect(() => {
    const related = searchParams.get('related');
    const rtParam = searchParams.get('recipientType');
    const userSeqParam = searchParams.get('recipientUserSeq');
    if (!related && !rtParam && !userSeqParam) return;

    let cancelled = false;
    const run = async () => {
      try {
        // 1) related application — 신청 정보로 컨텍스트 + 신청자 정본 확보.
        let appLoaded: AdminApplication | null = null;
        if (related && /^\d+$/.test(related)) {
          try {
            appLoaded = await adminApi.getApplication(Number(related));
            if (!cancelled && appLoaded) setRelatedApplication(appLoaded);
          } catch {
            // 신청 조회 실패 — 무시 (사용자가 직접 선택 가능).
          }
        }

        // 2) recipientType prefill.
        let nextType: RecipientType = 'APPLICANT';
        if (rtParam === 'APPLICANT' || rtParam === 'LEW' || rtParam === 'EXTERNAL' || rtParam === 'MULTI') {
          nextType = rtParam;
        }
        if (!cancelled) setRecipientType(nextType);

        // 3) recipientUserSeq — APPLICANT/LEW 단일 prefill. 신청 상세에서 진입한 케이스
        //    (recipientUserSeq=appLoaded.userSeq) 면 application 의 user 정보를 그대로 쓴다.
        if (
          (nextType === 'APPLICANT' || nextType === 'LEW') &&
          userSeqParam &&
          /^\d+$/.test(userSeqParam)
        ) {
          const seq = Number(userSeqParam);
          if (appLoaded && appLoaded.userSeq === seq) {
            // 신청자 prefill — application 응답의 user* 필드로 User 객체 합성.
            const synthesized: User = {
              userSeq: appLoaded.userSeq,
              email: appLoaded.userEmail,
              firstName: appLoaded.userFirstName,
              lastName: appLoaded.userLastName,
              role: nextType,
            } as User;
            if (!cancelled) setSingleUser(synthesized);
          } else {
            // application 컨텍스트 없는 직접 user_seq 진입 — myProfile 등 다른 lookup 도구가 없어
            // SystemUserPicker 의 결과를 기다리지 않고 user 정본 합성이 어렵다. PR-4 범위:
            // 신청 상세 진입을 1차 유스케이스로 두고, 외부 user_seq 직접 진입은 미지원.
            // (사용자가 직접 검색 박스에 이메일을 다시 검색하면 됨.)
            // → 별도 lookup 호출 회피로 단순성 유지.
            if (!cancelled) {
              toast.info('Search and select the recipient — pre-fill is supported only from application context.');
            }
          }
        }
      } finally {
        // prefill 1회 후 query 제거 — replace=true 로 history 미오염.
        if (!cancelled) {
          const next = new URLSearchParams(searchParams);
          next.delete('related');
          next.delete('recipientType');
          next.delete('recipientUserSeq');
          setSearchParams(next, { replace: true });
        }
      }
    };
    run();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Validation ────────────────────────────────────────────
  const recipientCount = useMemo(() => {
    if (recipientType === 'APPLICANT' || recipientType === 'LEW') return singleUser ? 1 : 0;
    if (recipientType === 'EXTERNAL') return externalEmail.trim() ? 1 : 0;
    // MULTI
    return multiUsers.length + multiExternals.length;
  }, [recipientType, singleUser, externalEmail, multiUsers, multiExternals]);

  const formErrors = useMemo(() => {
    const errors: Record<string, string> = {};
    if (recipientType === 'APPLICANT' || recipientType === 'LEW') {
      if (!singleUser) errors.recipient = `Please select a ${recipientType.toLowerCase()} recipient`;
    } else if (recipientType === 'EXTERNAL') {
      if (!externalEmail.trim()) errors.recipient = 'Please enter a recipient email';
      else if (!EMAIL_RE.test(externalEmail.trim()))
        errors.recipient = 'Recipient email format is invalid';
    } else if (recipientType === 'MULTI') {
      if (recipientCount < MULTI_MIN) errors.recipient = `Please add at least ${MULTI_MIN} recipients`;
      else if (recipientCount > MULTI_MAX)
        errors.recipient = `Maximum ${MULTI_MAX} recipients per dispatch`;
    }
    if (!subject.trim()) errors.subject = 'Subject is required';
    else if (subject.length > SUBJECT_MAX) errors.subject = `Subject exceeds ${SUBJECT_MAX} characters`;
    if (!bodyText.trim()) errors.bodyText = 'Body is required';
    else if (bodyText.length > BODY_MAX) errors.bodyText = `Body exceeds ${BODY_MAX} characters`;
    return errors;
  }, [recipientType, singleUser, externalEmail, recipientCount, subject, bodyText]);

  const isValid = Object.keys(formErrors).length === 0;

  // ── Builder ───────────────────────────────────────────────
  const buildPayload = (): SendManualEmailRequest => {
    const base: SendManualEmailRequest = {
      recipientType,
      subject: subject.trim(),
      bodyText,
      ...(relatedApplication ? { relatedApplicationSeq: relatedApplication.applicationSeq } : {}),
      ...(categoryTag.trim() ? { categoryTag: categoryTag.trim() } : {}),
      // PR-4 (D4=B): EXTERNAL 단일 발송에는 인앱 동반이 무의미하지만, 백엔드가 자동 스킵하므로
      // 일관된 payload 형태로 전송. UI 에서는 EXTERNAL 시 체크박스 disabled.
      alsoCreateInAppNotification: alsoCreateInApp,
    };
    if (recipientType === 'APPLICANT' || recipientType === 'LEW') {
      return { ...base, recipientUserSeq: singleUser?.userSeq ?? null };
    }
    if (recipientType === 'EXTERNAL') {
      return { ...base, recipientEmail: externalEmail.trim() };
    }
    // MULTI
    return {
      ...base,
      recipientUserSeqs: multiUsers.map((u) => u.userSeq),
      recipientEmails: multiExternals.map((e) => e.email),
    };
  };

  // EXTERNAL 단일 발송에는 시스템 사용자가 없어 인앱 동반이 적용되지 않는다 — 체크박스 disabled.
  const inAppCheckboxDisabled = recipientType === 'EXTERNAL';

  // ── Recipient handlers ────────────────────────────────────
  const handleSingleSelect = (u: User) => {
    setSingleUser(u);
    setFieldErrors((prev) => ({ ...prev, recipient: '' }));
  };

  const handleAddMultiUser = (u: User) => {
    if (u.role !== 'APPLICANT' && u.role !== 'LEW') {
      toast.warning('Only APPLICANT or LEW users can be added');
      return;
    }
    if (multiUsers.length + multiExternals.length >= MULTI_MAX) {
      toast.warning(`Maximum ${MULTI_MAX} recipients per dispatch`);
      return;
    }
    // role narrowing 후 명시적 타입 — TS 가 setState callback 인자에서 좁히지 못하므로.
    const chip: MultiUserChip = {
      userSeq: u.userSeq,
      email: u.email,
      name: [u.firstName, u.lastName].filter(Boolean).join(' '),
      role: u.role,
    };
    setMultiUsers((prev) => [...prev, chip]);
  };

  const handleRemoveMultiUser = (userSeq: number) =>
    setMultiUsers((prev) => prev.filter((c) => c.userSeq !== userSeq));

  const handleAddExternalChip = () => {
    const email = externalDraft.trim();
    if (!email) return;
    if (!EMAIL_RE.test(email)) {
      toast.error('Invalid email format');
      return;
    }
    if (
      multiExternals.some((c) => c.email.toLowerCase() === email.toLowerCase()) ||
      multiUsers.some((c) => c.email.toLowerCase() === email.toLowerCase())
    ) {
      toast.warning('Email already added');
      setExternalDraft('');
      return;
    }
    if (multiUsers.length + multiExternals.length >= MULTI_MAX) {
      toast.warning(`Maximum ${MULTI_MAX} recipients per dispatch`);
      return;
    }
    setMultiExternals((prev) => [...prev, { email }]);
    setExternalDraft('');
  };

  const handleRemoveExternalChip = (email: string) =>
    setMultiExternals((prev) => prev.filter((c) => c.email !== email));

  // ── Type switch — 입력 초기화 ───────────────────────────────
  const handleTypeChange = (next: RecipientType) => {
    setRecipientType(next);
    setSingleUser(null);
    setExternalEmail('');
    setMultiUsers([]);
    setMultiExternals([]);
    setExternalDraft('');
    setFieldErrors({});
  };

  const resetForm = () => {
    setRecipientType('APPLICANT');
    setSingleUser(null);
    setExternalEmail('');
    setMultiUsers([]);
    setMultiExternals([]);
    setExternalDraft('');
    setRelatedApplication(null);
    setSubject('');
    setBodyText('');
    setCategoryTag('');
    setAlsoCreateInApp(true);  // PR-4: 기본 ON 으로 복원.
    setFieldErrors({});
  };

  // ── Send / 409 handling ───────────────────────────────────
  const performSend = async (forceDuplicate: boolean) => {
    if (!isValid) {
      setFieldErrors(formErrors);
      return;
    }
    setSubmitting(true);
    try {
      const resp = await sendManualEmail(buildPayload(), { forceDuplicate });
      if (resp.dispatchStatus === 'SENT') {
        toast.success(`Email queued — ${resp.sentCount} recipient(s)`);
      } else if (resp.dispatchStatus === 'PARTIAL_FAILED') {
        toast.warning(
          `Partially sent — ${resp.sentCount} delivered, ${resp.failedCount} failed. See history for details.`
        );
      } else if (resp.dispatchStatus === 'FAILED') {
        toast.error(`Email queued — delivery failed for ${resp.failedCount} recipient(s).`);
      } else {
        toast.success('Email queued — delivery in progress. Check History for status.');
      }
      resetForm();
      onSent();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      if (e.code === 'MANUAL_EMAIL_DUPLICATE_SUSPECTED') {
        // 사용자 confirm 후 단 1회만 forceDuplicate=true 재호출
        setDuplicateMessage(
          e.message ||
            'Same email was sent within the past 30 seconds. Send anyway?'
        );
        setDuplicateConfirmOpen(true);
      } else if (e.code === 'MANUAL_EMAIL_DAILY_CAP_EXCEEDED') {
        // PR-4 (D5=B): cap 초과 — toast + onSent 콜백으로 부모가 quota 갱신.
        toast.error(e.message || 'Daily manual email cap reached. Try again after midnight SGT.');
        // 부모가 quota 를 새로 가져와 한도 표시를 갱신할 수 있도록 onSent 알림 (실제 발송은 안 됨).
        onSent();
      } else if (e.code === 'MULTI_REQUIRES_AT_LEAST_TWO_RECIPIENTS') {
        setFieldErrors({ recipient: `Please add at least ${MULTI_MIN} recipients` });
      } else if (e.code === 'MULTI_EXCEEDS_MAX_RECIPIENTS') {
        setFieldErrors({ recipient: `Maximum ${MULTI_MAX} recipients per dispatch` });
      } else if (e.code === 'RECIPIENT_ROLE_MISMATCH') {
        setFieldErrors({
          recipient: e.message || 'Recipient role does not match the selected type',
        });
      } else if (e.code === 'VALIDATION_ERROR') {
        toast.error(e.message || 'Validation failed');
      } else {
        toast.error(e.message || 'Failed to send email');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleSendClick = () => {
    setFieldErrors(formErrors);
    if (!isValid) return;
    setConfirmOpen(true);
  };

  const handleConfirmSend = async () => {
    setConfirmOpen(false);
    await performSend(false);
  };

  const handleConfirmDuplicateSend = async () => {
    setDuplicateConfirmOpen(false);
    await performSend(true);
  };

  const handlePreviewClick = () => {
    setFieldErrors(formErrors);
    if (!isValid) return;
    setPreviewOpen(true);
  };

  // ── UI ────────────────────────────────────────────────────
  return (
    <div className="bg-white rounded-lg border border-gray-200 p-4 sm:p-6 space-y-5">
      {/* Recipient type */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Recipient type <span className="text-error-500">*</span>
        </label>
        <div className="flex flex-wrap gap-3">
          {(['APPLICANT', 'LEW', 'EXTERNAL', 'MULTI'] as RecipientType[]).map((rt) => (
            <label key={rt} className="inline-flex items-center gap-2 cursor-pointer text-sm">
              <input
                type="radio"
                name="recipientType"
                value={rt}
                checked={recipientType === rt}
                onChange={() => handleTypeChange(rt)}
                className="text-primary"
              />
              <span>{rt}</span>
            </label>
          ))}
        </div>
      </div>

      {/* Recipients */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Recipients <span className="text-error-500">*</span>
        </label>

        {(recipientType === 'APPLICANT' || recipientType === 'LEW') && (
          <div className="space-y-2">
            {singleUser ? (
              <div className="flex items-center justify-between gap-3 px-3 py-2 bg-blue-50 border border-blue-200 rounded-md">
                <div className="text-sm text-blue-900 min-w-0 flex-1">
                  <span className="font-medium">{singleUser.email}</span>
                  <span className="ml-2 text-blue-700 text-xs">
                    {[singleUser.firstName, singleUser.lastName].filter(Boolean).join(' ')} ({singleUser.role})
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => setSingleUser(null)}
                  className="text-sm text-blue-700 hover:text-blue-900"
                >
                  Clear
                </button>
              </div>
            ) : (
              <SystemUserPicker
                roleFilter={recipientType}
                excludeUserSeqs={[]}
                onSelect={handleSingleSelect}
                placeholder={`Search ${recipientType.toLowerCase()} by email or name…`}
              />
            )}
          </div>
        )}

        {recipientType === 'EXTERNAL' && (
          <Input
            type="email"
            value={externalEmail}
            onChange={(e) => setExternalEmail(e.target.value)}
            placeholder="partner@example.com"
            error={fieldErrors.recipient}
          />
        )}

        {recipientType === 'MULTI' && (
          <div className="space-y-3">
            <div>
              <div className="text-xs text-gray-500 mb-1">Add system users (APPLICANT or LEW)</div>
              <SystemUserPicker
                roleFilter={null}
                excludeUserSeqs={multiUsers.map((c) => c.userSeq)}
                onSelect={handleAddMultiUser}
              />
            </div>
            <div>
              <div className="text-xs text-gray-500 mb-1">Add external emails</div>
              <div className="flex gap-2">
                <Input
                  type="email"
                  value={externalDraft}
                  onChange={(e) => setExternalDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      handleAddExternalChip();
                    }
                  }}
                  placeholder="external@example.com"
                  className="flex-1"
                />
                <Button type="button" variant="outline" size="sm" onClick={handleAddExternalChip}>
                  Add
                </Button>
              </div>
            </div>

            {/* Chips */}
            {(multiUsers.length > 0 || multiExternals.length > 0) && (
              <div className="flex flex-wrap gap-2 pt-1">
                {multiUsers.map((c) => (
                  <span
                    key={`u-${c.userSeq}`}
                    className="inline-flex items-center gap-1.5 px-2 py-1 rounded-full bg-blue-50 text-blue-800 text-xs border border-blue-200"
                  >
                    <span className="font-medium">{c.email}</span>
                    <span className="text-blue-600">({c.role})</span>
                    <button
                      type="button"
                      onClick={() => handleRemoveMultiUser(c.userSeq)}
                      className="ml-1 text-blue-700 hover:text-red-600"
                      aria-label={`Remove ${c.email}`}
                    >
                      ×
                    </button>
                  </span>
                ))}
                {multiExternals.map((c) => (
                  <span
                    key={`e-${c.email}`}
                    className="inline-flex items-center gap-1.5 px-2 py-1 rounded-full bg-gray-100 text-gray-800 text-xs border border-gray-200"
                  >
                    <span className="font-medium">{c.email}</span>
                    <span className="text-gray-500">(EXTERNAL)</span>
                    <button
                      type="button"
                      onClick={() => handleRemoveExternalChip(c.email)}
                      className="ml-1 text-gray-700 hover:text-red-600"
                      aria-label={`Remove ${c.email}`}
                    >
                      ×
                    </button>
                  </span>
                ))}
              </div>
            )}

            <div className="text-xs text-gray-500">
              {recipientCount} of {MULTI_MAX} recipients
              {recipientCount < MULTI_MIN && (
                <span className="ml-2 text-amber-700">(at least {MULTI_MIN} required)</span>
              )}
            </div>
          </div>
        )}

        {fieldErrors.recipient && recipientType !== 'EXTERNAL' && (
          <p className="mt-1 text-sm text-error-600">{fieldErrors.recipient}</p>
        )}
      </div>

      {/* Related application */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Related application (optional)
        </label>
        <ApplicationPicker selected={relatedApplication} onSelect={setRelatedApplication} />
      </div>

      {/* Category tag */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-2">
          Category tag (optional)
        </label>
        <div className="flex gap-2">
          <div className="w-44">
            <Select
              value={categorySuggestions.includes(categoryTag) ? categoryTag : ''}
              onChange={(e) => setCategoryTag(e.target.value)}
              options={[
                { value: '', label: '(none)' },
                ...categorySuggestions.map((c) => ({ value: c, label: c })),
              ]}
            />
          </div>
          <Input
            type="text"
            value={categoryTag}
            onChange={(e) => setCategoryTag(e.target.value)}
            placeholder="Or type a custom tag…"
            maxLength={50}
            className="flex-1"
          />
        </div>
      </div>

      {/* Subject */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">
          Subject <span className="text-error-500">*</span>
        </label>
        <Input
          value={subject}
          onChange={(e) => setSubject(e.target.value.slice(0, SUBJECT_MAX))}
          placeholder="e.g. Payment confirmation delayed"
          error={fieldErrors.subject}
        />
        <div className={`mt-1 text-xs text-right ${subject.length === SUBJECT_MAX ? 'text-error-600' : 'text-gray-400'}`}>
          {subject.length} / {SUBJECT_MAX}
        </div>
      </div>

      {/* Body */}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1.5">
          Body (plain text) <span className="text-error-500">*</span>
        </label>
        <Textarea
          value={bodyText}
          onChange={(e) => setBodyText(e.target.value.slice(0, BODY_MAX))}
          placeholder="Plain text only — HTML will not be rendered. The system will append a header (manual notice) and footer (sender identity + anti-phishing notice) automatically."
          rows={10}
          error={fieldErrors.bodyText}
          className="font-mono"
        />
        <div className={`mt-1 text-xs text-right ${bodyText.length === BODY_MAX ? 'text-error-600' : 'text-gray-400'}`}>
          {bodyText.length} / {BODY_MAX}
        </div>
      </div>

      {/* PR-4 (D4=B): 인앱 알림 동반 옵션 — 시스템 사용자 수신자에게만 적용. */}
      <div className="pt-1">
        <label
          className={`inline-flex items-start gap-2 text-sm cursor-pointer ${
            inAppCheckboxDisabled ? 'cursor-not-allowed opacity-60' : ''
          }`}
        >
          <input
            type="checkbox"
            checked={alsoCreateInApp}
            onChange={(e) => setAlsoCreateInApp(e.target.checked)}
            disabled={inAppCheckboxDisabled}
            className="mt-0.5 text-primary"
          />
          <span>
            <span className="text-gray-800">Also create in-app notification for system users</span>
            <span className="block text-xs text-gray-500 mt-0.5">
              {inAppCheckboxDisabled
                ? 'In-app notification is only sent to system users (APPLICANT/LEW). EXTERNAL recipients receive email only.'
                : 'Recommended — recipients see the email subject as a card on their dashboard.'}
            </span>
          </span>
        </label>
      </div>

      {/* Actions */}
      <div className="flex justify-end gap-3 pt-2 border-t border-gray-100">
        <Button variant="outline" onClick={handlePreviewClick} disabled={submitting}>
          Preview
        </Button>
        <Button onClick={handleSendClick} disabled={submitting} loading={submitting}>
          Send
        </Button>
      </div>

      {/* Preview modal */}
      <ManualEmailPreviewModal
        isOpen={previewOpen}
        onClose={() => setPreviewOpen(false)}
        onEdit={() => setPreviewOpen(false)}
        payload={previewOpen ? buildPayload() : null}
      />

      {/* Send confirm dialog */}
      <ConfirmDialog
        isOpen={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={handleConfirmSend}
        title={`Send to ${recipientCount} recipient${recipientCount === 1 ? '' : 's'}?`}
        message={`Once sent, emails cannot be recalled. Sender identity (${user?.email}) will be visible in the footer.`}
        confirmLabel="Send"
        cancelLabel="Cancel"
      />

      {/* Duplicate (409) dialog — single retry, no infinite loop */}
      <ConfirmDialog
        isOpen={duplicateConfirmOpen}
        onClose={() => setDuplicateConfirmOpen(false)}
        onConfirm={handleConfirmDuplicateSend}
        title="Possible duplicate"
        message={duplicateMessage || 'Same email was sent within the past 30 seconds. Send anyway?'}
        confirmLabel="Send anyway"
        cancelLabel="Cancel"
        variant="danger"
      />
    </div>
  );
}
