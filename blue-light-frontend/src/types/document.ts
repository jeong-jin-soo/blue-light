/**
 * Phase 2 — Document Management Types
 * 백엔드 DTO (`DocumentTypeDto`, `DocumentRequestDto`, `VoluntaryUploadResponse`)와 1:1 매칭.
 */

/**
 * DocumentRequest 상태 (백엔드 `DocumentRequestStatus` enum과 일치)
 */
export type DocumentRequestStatus =
  | 'REQUESTED'
  | 'UPLOADED'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED';

/**
 * Document Type 카탈로그 row (GET /api/document-types)
 */
export interface DocumentType {
  code: string;
  labelEn: string;
  labelKo: string;
  description?: string;
  helpText?: string;
  /** CSV (e.g. "application/pdf,image/png,image/jpeg") */
  acceptedMime: string;
  maxSizeMb: number;
  templateUrl?: string;
  exampleImageUrl?: string;
  /** JSON 문자열 */
  requiredFields?: string;
  iconEmoji?: string;
  displayOrder: number;
}

/**
 * 신청서 단위 DocumentRequest (GET /api/applications/{id}/document-requests)
 */
export interface DocumentRequest {
  id: number;
  applicationSeq: number;
  documentTypeCode: string;
  customLabel?: string;
  lewNote?: string;
  status: DocumentRequestStatus;

  /** fulfilledFile 요약 (서버 DTO 필드명 그대로) */
  fulfilledFileSeq?: number;
  fulfilledFilename?: string;
  fulfilledFileSize?: number;

  requestedAt?: string;
  fulfilledAt?: string;
  reviewedAt?: string;
  rejectionReason?: string;
  createdAt: string;
}

/**
 * 자발적 업로드 응답 (POST /api/applications/{id}/documents)
 */
export interface VoluntaryUploadResponse {
  documentRequestId: number;
  documentSeq: number;
  status: DocumentRequestStatus;
  documentTypeCode: string;
  customLabel?: string;
  fileName: string;
  sizeBytes: number;
}

/**
 * 자발적 업로드 페이로드
 */
export interface VoluntaryUploadPayload {
  documentTypeCode: string;
  customLabel?: string;
  file: File;
}
