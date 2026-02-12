/**
 * Shared utilities for Application detail pages
 * (ApplicationDetailPage & AdminApplicationDetailPage)
 */

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
    default: return type;
  }
}

/** Map file type to Badge variant */
export function getFileTypeBadge(type: string): 'primary' | 'info' | 'success' | 'gray' {
  switch (type) {
    case 'DRAWING_SLD': return 'primary';
    case 'OWNER_AUTH_LETTER': return 'info';
    case 'SITE_PHOTO': return 'info';
    case 'REPORT_PDF': return 'gray';
    case 'LICENSE_PDF': return 'success';
    default: return 'gray';
  }
}
