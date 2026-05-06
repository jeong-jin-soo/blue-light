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

  /**
   * 재업로드 시 이전 파일 seq (Phase 3 PR#1 DTO 확장). AC-AU4.
   */
  previousFileSeq?: number;
}

// ─────────────────────────────────────────────
// Phase 3 — LEW 서류 요청 API 타입
// ─────────────────────────────────────────────

/**
 * 배치 생성 요청 1건 (POST /api/admin/applications/{id}/document-requests)
 */
export interface CreateDocumentRequestItem {
  documentTypeCode: string;
  /** OTHER 일 때 필수. */
  customLabel?: string;
  /** 신청자에게 보낼 메모 (optional, max 1000자). */
  lewNote?: string;
}

/**
 * 배치 생성 응답 1건.
 */
export interface CreateDocumentRequestCreated {
  id: number;
  documentTypeCode: string;
  customLabel?: string;
  status: DocumentRequestStatus;
}

/**
 * 배치 생성 응답 (201).
 */
export interface CreateDocumentRequestsResponse {
  created: CreateDocumentRequestCreated[];
}

/**
 * Approve/Reject/Cancel 공통 응답.
 */
export interface DocumentRequestDecisionResponse {
  id: number;
  status: DocumentRequestStatus;
  reviewedAt?: string;
  rejectionReason?: string;
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
