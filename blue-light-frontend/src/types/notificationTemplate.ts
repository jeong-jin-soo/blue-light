/**
 * 알림 템플릿 관리 — 프론트 타입 정의 (PR-T6).
 *
 * 백엔드 DTO 와 1:1 정합:
 * - {@code NotificationTemplateListItemResponse}
 * - {@code NotificationTemplateDetailResponse}
 * - {@code NotificationTemplateDraftResponse}
 * - {@code CatalogEntryResponse}, {@code HistoryItemResponse}
 * - {@code TemplatePreviewRequest/Response}, {@code TemplateTestSendRequest/Response}
 *
 * 스펙: doc/Project Analysis/notification-template-manager-spec.md §6, §7
 */

export type NotificationChannel = 'IN_APP' | 'EMAIL' | 'SMS' | 'WHATSAPP';

export type NotificationCategory =
  | 'SECURITY'
  | 'STATUS'
  | 'PAYMENT'
  | 'REMINDER'
  | 'VISIT'
  | 'REASSURANCE'
  | 'EXPIRY'
  | 'MARKETING'
  | 'FEEDBACK'
  | 'OPS';

export type NotificationSeverity =
  | 'CRITICAL'
  | 'IMPORTANT'
  | 'INFORMATIONAL'
  | 'MARKETING';

export type TemplateDraftStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';

export type TemplateChangeType = 'CREATE' | 'PUBLISH' | 'ENABLE' | 'DISABLE' | 'ROLLBACK';

export interface NotificationTemplateListItem {
  templateSeq: number;
  templateCode: string;
  channel: NotificationChannel;
  locale: string;
  subject: string | null;
  enabled: boolean;
  version: number;
  catalogMetaKey: string | null;
  category: NotificationCategory | null;
  severity: NotificationSeverity | null;
  recipientRoles: string | null;
  updatedAt: string;
  updatedBy: number | null;
}

export interface NotificationTemplateDetail extends NotificationTemplateListItem {
  bodyText: string;
  variablesJson: string | null;
  providerTemplateName: string | null;
  createdAt: string;
}

export interface NotificationTemplateDraft {
  draftSeq: number;
  templateSeq: number | null;
  templateCode: string;
  channel: NotificationChannel;
  locale: string;
  subject: string | null;
  bodyText: string;
  variablesJson: string | null;
  providerTemplateName: string | null;
  category: NotificationCategory | null;
  severity: NotificationSeverity | null;
  recipientRoles: string | null;
  submittedBy: number;
  submittedAt: string;
  submissionNote: string | null;
  status: TemplateDraftStatus;
  reviewedBy: number | null;
  reviewedAt: string | null;
  reviewNote: string | null;
}

export interface CatalogEntry {
  catalogSeq: number;
  templateCode: string;
  allowedVariablesJson: string;
  defaultCategory: NotificationCategory;
  defaultSeverity: NotificationSeverity;
  defaultRecipientRoles: string;
  description: string | null;
  requiredTokensJson: string | null;
}

export interface HistoryItem {
  historySeq: number;
  templateSeq: number;
  changeType: TemplateChangeType;
  diffJson: string;
  beforeSnapshotJson: string;
  afterSnapshotJson: string;
  changeReason: string | null;
  actorUserSeq: number;
  actorIp: string | null;
  changedAt: string;
}

export interface LintIssue {
  ruleCode: string;
  severity: 'ERROR' | 'WARNING';
  message: string;
  field: string | null;
  detail: string | null;
}

export interface CreateDraftRequest {
  templateSeq: number | null;
  templateCode: string;
  channel: NotificationChannel;
  locale: string;
  subject: string | null;
  body: string;
  variablesJson: string | null;
  providerTemplateName: string | null;
  category: NotificationCategory | null;
  severity: NotificationSeverity | null;
  recipientRoles: string | null;
  submissionNote: string | null;
}

export interface UpdateDraftRequest {
  subject: string | null;
  body: string;
  variablesJson: string | null;
  providerTemplateName: string | null;
  category: NotificationCategory | null;
  severity: NotificationSeverity | null;
  recipientRoles: string | null;
  submissionNote: string | null;
}

export interface TemplatePreviewResponse {
  subject: string;
  body: string;
  charCount: number;
  smsSegments: number | null;
  missingKeys: string[];
  warnings: LintIssue[];
}

export interface TemplateTestSendResponse {
  outboxSeq: number;
  dailyQuotaUsed: number;
  dailyQuotaMax: number;
}

/** Lint 차단(400) 응답 body. */
export interface LintErrorBody {
  code: 'TEMPLATE_LINT_FAILED';
  message: string;
  lint: {
    errors: LintIssue[];
    warnings: LintIssue[];
  };
}
