/**
 * Shared utilities for Application detail pages
 * (ApplicationDetailPage & AdminApplicationDetailPage)
 */

import type { EmaSubmissionStatus, FileInfo, FileType } from '../types';
import type { BadgeVariant } from '../components/ui/Badge';

/** Status steps displayed in StepTracker */
export const STATUS_STEPS = [
  { label: 'Submitted', description: 'Application submitted for review' },
  { label: 'Reviewed', description: 'LEW review completed' },
  { label: 'Paid', description: 'Payment confirmed' },
  { label: 'In Progress', description: 'Under processing' },
  { label: 'Completed', description: 'Licence issued' },
];

/** Map application status → StepTracker currentStep index */
export function getStatusStep(status: string): number {
  switch (status) {
    case 'PENDING_REVIEW': return 0;
    case 'REVISION_REQUESTED': return 0;
    case 'PENDING_PAYMENT': return 1;
    case 'PAID': return 2;
    case 'IN_PROGRESS': return 3;
    case 'COMPLETED': return 5;
    case 'EXPIRED': return -1;
    default: return 0;
  }
}

/** Format file size in human-readable form */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Format file type enum to display label */
export function formatFileType(type: string): string {
  switch (type) {
    case 'DRAWING_SLD': return 'SLD';
    case 'OWNER_AUTH_LETTER': return 'Appointment';
    case 'SITE_PHOTO': return 'Photo';
    case 'REPORT_PDF': return 'Report';
    case 'LICENSE_PDF': return 'Licence';
    case 'SP_ACCOUNT_DOC': return 'SP Account';
    case 'SKETCH_SLD': return 'Sketch';
    case 'EMA_ACK': return 'EMA Receipt';
    default: return type;
  }
}

// ============================================
// EMA Submission Tracking (ema-submission-tracking-spec.md §8)
// ============================================

/** EMA 상태 → 짧은 표시 라벨 (영문 UI 일관). */
export function formatEmaStatus(status?: EmaSubmissionStatus | null): string {
  switch (status) {
    case 'NOT_SUBMITTED': return 'Not submitted';
    case 'SUBMITTED': return 'Submitted';
    case 'QUERY_RAISED': return 'Query raised';
    case 'RESUBMITTED': return 'Resubmitted';
    case 'APPROVED': return 'Approved';
    case 'REJECTED': return 'Rejected';
    case 'WITHDRAWN': return 'Withdrawn';
    default: return '—';
  }
}

/**
 * EMA 상태 → Badge variant (스펙 §8.1 배지 규칙).
 * SUBMITTED/RESUBMITTED→info, QUERY_RAISED→warning, APPROVED→success,
 * REJECTED/WITHDRAWN→error(=danger), NOT_SUBMITTED→gray.
 */
export function getEmaStatusBadge(status?: EmaSubmissionStatus | null): BadgeVariant {
  switch (status) {
    case 'SUBMITTED':
    case 'RESUBMITTED': return 'info';
    case 'QUERY_RAISED': return 'warning';
    case 'APPROVED': return 'success';
    case 'REJECTED':
    case 'WITHDRAWN': return 'error';
    default: return 'gray';
  }
}

/** "진행 중(정체 후보)" EMA 상태 — ADMIN 모니터링에서 정체 건 식별에 사용. */
export function isEmaInFlight(status?: EmaSubmissionStatus | null): boolean {
  return status === 'SUBMITTED' || status === 'QUERY_RAISED' || status === 'RESUBMITTED';
}

/** Map file type to Badge variant */
export function getFileTypeBadge(type: string): 'primary' | 'info' | 'success' | 'gray' {
  switch (type) {
    case 'DRAWING_SLD': return 'primary';
    case 'OWNER_AUTH_LETTER': return 'info';
    case 'SITE_PHOTO': return 'info';
    case 'SP_ACCOUNT_DOC': return 'info';
    case 'REPORT_PDF': return 'gray';
    case 'LICENSE_PDF': return 'success';
    case 'SKETCH_SLD': return 'info';
    default: return 'gray';
  }
}

// ============================================
// File Upload Constants
// ============================================

/** Maximum upload size in MB */
export const MAX_UPLOAD_SIZE_MB = 10;
/** ELISE soft warning threshold in MB */
export const WARN_UPLOAD_SIZE_MB = 2;
/** Accepted file extensions for upload inputs */
export const ALLOWED_UPLOAD_EXTENSIONS = '.pdf,.jpg,.jpeg,.png,.dwg,.dxf,.dgn,.tif,.tiff,.gif,.zip';

// ============================================
// Document Category Grouping
// ============================================

export interface DocumentCategory {
  key: string;
  label: string;
  icon: string;
  fileTypes: FileType[];
  bgColor: string;
  borderColor: string;
  headerColor: string;
  /** Only these categories are shown to applicants as upload targets */
  applicantUpload: boolean;
  /** Only these categories are shown to admins as upload targets */
  adminUpload: boolean;
}

export const DOCUMENT_CATEGORIES: DocumentCategory[] = [
  {
    key: 'sld',
    label: 'SLD Drawing',
    icon: '📐',
    fileTypes: ['DRAWING_SLD', 'SKETCH_SLD'],
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-200',
    headerColor: 'text-blue-800',
    applicantUpload: true,
    adminUpload: false,
  },
  {
    key: 'loa',
    label: 'Letter of Appointment (LOA)',
    icon: '📝',
    fileTypes: ['OWNER_AUTH_LETTER'],
    bgColor: 'bg-purple-50',
    borderColor: 'border-purple-200',
    headerColor: 'text-purple-800',
    applicantUpload: true,  // Only for RENEWAL — NEW applications auto-generate LOA via LEW
    adminUpload: true,
  },
  {
    key: 'sp_account',
    label: 'SP Account Document',
    icon: '🏢',
    fileTypes: ['SP_ACCOUNT_DOC'],
    bgColor: 'bg-teal-50',
    borderColor: 'border-teal-200',
    headerColor: 'text-teal-800',
    applicantUpload: true,
    adminUpload: false,
  },
  {
    key: 'photo',
    label: 'Main Breaker Box Photo',
    icon: '📷',
    fileTypes: ['SITE_PHOTO'],
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-200',
    headerColor: 'text-amber-800',
    applicantUpload: true,
    adminUpload: false,
  },
  {
    key: 'licence',
    label: 'Licence Documents',
    icon: '📋',
    fileTypes: ['LICENSE_PDF', 'REPORT_PDF'],
    bgColor: 'bg-emerald-50',
    borderColor: 'border-emerald-200',
    headerColor: 'text-emerald-800',
    applicantUpload: false,
    adminUpload: true,
  },
  {
    key: 'ema',
    label: 'EMA Submission',
    icon: '🏛️',
    fileTypes: ['EMA_ACK'],
    bgColor: 'bg-indigo-50',
    borderColor: 'border-indigo-200',
    headerColor: 'text-indigo-800',
    applicantUpload: false,
    adminUpload: true,
  },
];

/** Group files by document category key */
export function groupFilesByCategory(files: FileInfo[]): Record<string, FileInfo[]> {
  const grouped: Record<string, FileInfo[]> = {};
  for (const cat of DOCUMENT_CATEGORIES) {
    grouped[cat.key] = files.filter((f) => cat.fileTypes.includes(f.fileType));
  }
  // Uncategorized files
  const allCategorizedTypes = DOCUMENT_CATEGORIES.flatMap((c) => c.fileTypes);
  const uncategorized = files.filter((f) => !allCategorizedTypes.includes(f.fileType));
  if (uncategorized.length > 0) {
    grouped['other'] = uncategorized;
  }
  return grouped;
}

/** Image extensions supported for thumbnail preview */
const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp'];

/** Get the file extension from a filename (lowercase) */
export function getFileExtension(filename: string): string {
  return filename.split('.').pop()?.toLowerCase() || '';
}

/** Check if a filename is a previewable image */
export function isImageFile(filename: string): boolean {
  return IMAGE_EXTENSIONS.includes(getFileExtension(filename));
}

/** Check if a filename is a PDF */
export function isPdfFile(filename: string): boolean {
  return getFileExtension(filename) === 'pdf';
}
