// Application activity timeline — maps backend AuditAction → display label/icon.
// Mirrors com.bluelight.backend.domain.audit.AuditAction. Unmapped actions fall back via getActivityMeta.

export interface ActivityMeta {
  label: string;
  icon: string;
}

export const ACTIVITY_LABELS: Record<string, ActivityMeta> = {
  // Application lifecycle
  APPLICATION_CREATED: { label: 'Application created', icon: '📝' },
  APPLICATION_UPDATED: { label: 'Application updated', icon: '✏️' },
  APPLICATION_RESUBMITTED: { label: 'Application resubmitted', icon: '🔁' },
  APPLICATION_STATUS_CHANGE: { label: 'Status changed', icon: '🔀' },
  APPLICATION_REVISION_REQUESTED: { label: 'Revision requested', icon: '⏳' },
  APPLICATION_APPROVED: { label: 'Approved (payment requested)', icon: '✅' },
  APPLICATION_COMPLETED: { label: 'Application completed — licence issued', icon: '🏁' },
  APPLICATION_REOPENED: { label: 'Completed case reopened (admin)', icon: '🔓' },
  APPLICATION_VIEWED_BY_LEW: { label: 'Viewed by LEW', icon: '👀' },
  APPLICATION_PAYMENT_REQUESTED_BY_LEW: { label: 'Payment requested by LEW', icon: '💸' },

  // Automated (scheduler)
  LICENSE_EXPIRED: { label: 'Licence expired (automatic)', icon: '⌛' },
  LICENSE_EXPIRY_WARNING_SENT: { label: 'Expiry warning sent (automatic)', icon: '🔔' },

  // LEW assignment
  LEW_ASSIGNED: { label: 'LEW assigned', icon: '👷' },
  LEW_UNASSIGNED: { label: 'LEW unassigned', icon: '🚫' },

  // Payment
  PAYMENT_CONFIRMED: { label: 'Payment confirmed', icon: '💳' },
  MANUAL_PAYMENT_RECORDED: { label: 'Manual payment recorded', icon: '💰' },

  // Invoice
  INVOICE_GENERATED: { label: 'Invoice generated', icon: '🧾' },
  INVOICE_REGENERATED: { label: 'Invoice regenerated', icon: '🧾' },
  INVOICE_GENERATION_FAILED: { label: 'Invoice generation failed', icon: '⚠️' },

  // File
  FILE_UPLOADED: { label: 'File uploaded', icon: '📎' },
  FILE_DELETED: { label: 'File deleted', icon: '🗑️' },

  // Document request
  DOCUMENT_UPLOADED_VOLUNTARY: { label: 'Document uploaded (voluntary)', icon: '📎' },
  DOCUMENT_DELETED_VOLUNTARY: { label: 'Document deleted', icon: '🗑️' },
  DOCUMENT_REQUEST_CREATED: { label: 'Document request created', icon: '📋' },
  DOCUMENT_REQUEST_FULFILLED: { label: 'Document submitted', icon: '📥' },
  DOCUMENT_REQUEST_CANCELLED: { label: 'Document request cancelled', icon: '🚫' },

  // LoA
  LOA_SNAPSHOT_CREATED: { label: 'LoA snapshot created', icon: '📄' },
  LOA_FORM_SENT: { label: 'LoA form sent', icon: '📄' },
  LOA_APPLICANT_UPLOADED: { label: 'Applicant LoA uploaded', icon: '📄' },
  LOA_FINAL_UPLOADED: { label: 'Final LoA uploaded by LEW', icon: '📄' },
  LOA_ADMIN_REPLACED: { label: 'LoA file replaced (admin)', icon: '♻️' },

  // kVA
  KVA_CONFIRMED_BY_LEW: { label: 'kVA confirmed (LEW)', icon: '⚡' },
  KVA_OVERRIDDEN_BY_ADMIN: { label: 'kVA overridden (admin)', icon: '⚡' },
  KVA_OVERRIDE_POSTPAYMENT: { label: 'kVA changed after payment', icon: '⚡' },
  KVA_ADJUSTMENT_REQUESTED_BY_LEW: { label: 'kVA adjustment requested (LEW)', icon: '⚡' },
  KVA_LEW_REQUEST_RESOLVED_BY_OVERRIDE: { label: 'kVA request resolved (override)', icon: '⚡' },
  KVA_SETTLEMENT_MARKED: { label: 'kVA settlement processed', icon: '🧾' },
  KVA_SETTLEMENT_DENIED: { label: 'kVA settlement denied', icon: '⚠️' },

  // EMA ELISE submission tracking
  EMA_SUBMITTED: { label: 'EMA submitted', icon: '📤' },
  EMA_QUERY_RAISED: { label: 'EMA query raised', icon: '❓' },
  EMA_RESUBMITTED: { label: 'EMA resubmitted', icon: '🔁' },
  EMA_APPROVED: { label: 'EMA approved', icon: '✅' },
  EMA_REJECTED: { label: 'EMA rejected', icon: '⚠️' },
  EMA_WITHDRAWN: { label: 'EMA withdrawn', icon: '↩️' },
  EMA_DECISION_REVERTED: { label: 'EMA decision reverted', icon: '↩️' },
};

/** AuditAction string → label/icon. Undefined actions fall back to a prettified enum name. */
export function getActivityMeta(action: string): ActivityMeta {
  const found = ACTIVITY_LABELS[action];
  if (found) return found;
  return {
    label: action
      .toLowerCase()
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(' '),
    icon: '•',
  };
}
