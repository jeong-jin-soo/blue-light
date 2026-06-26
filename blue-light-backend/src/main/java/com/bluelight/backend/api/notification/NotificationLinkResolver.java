package com.bluelight.backend.api.notification;

import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.UserRole;

/**
 * 인앱 알림 딥링크(linkUrl) 단일 해석기 (Single Source of Truth).
 *
 * <p>알림을 클릭했을 때 수신자가 "해당 건을 처리할 수 있는 화면의 해당 위치"로 이동하도록,
 * (알림 타입, referenceType, referenceId, 수신자 역할) 을 받아 프론트 라우트 상대경로 +
 * 섹션 해시를 만든다. 이메일 CTA(ctaUrl)와 동일한 의미의 상대경로이며, 인앱은 프론트가
 * 이 값으로 바로 {@code navigate} 한다.</p>
 *
 * <h2>역할별 베이스 경로</h2>
 * <ul>
 *   <li>APPLICANT → {@code /applications/{id}}</li>
 *   <li>LEW → {@code /lew/applications/{id}}</li>
 *   <li>ADMIN / SYSTEM_ADMIN → {@code /admin/applications/{id}}</li>
 * </ul>
 *
 * <h2>섹션 해시</h2>
 * <p>신청자 상세 페이지는 {@code id} 앵커로 스크롤, LEW 검토 페이지는 동일 해시로 탭을 선택한다
 * ({@code #documents/#kva/#loa/#ema}). 적절한 타깃이 없으면 {@code null} 을 반환하며,
 * 프론트는 이때 기존 fallback 라우팅(또는 단순 읽음 처리)으로 동작한다.</p>
 */
public final class NotificationLinkResolver {

    private NotificationLinkResolver() {}

    public static String resolve(NotificationType type, String referenceType,
                                 Long referenceId, UserRole recipientRole) {
        if (referenceId == null || referenceType == null) {
            return null;
        }
        switch (referenceType) {
            case "APPLICATION": {
                String hash = hashFor(type);
                // LEW 의 탭 해시(#documents/#kva/#sld/#loa/#ema)는 검토 화면(/review)에서만 탭 선택된다.
                // 상세 화면(/lew/applications/{id})에는 탭이 없어 해시가 무시되므로 /review 로 보낸다.
                if (recipientRole == UserRole.LEW && !hash.isEmpty()) {
                    return "/lew/applications/" + referenceId + "/review" + hash;
                }
                return basePath(recipientRole) + "/applications/" + referenceId + hash;
            }
            case "CONCIERGE_REQUEST":
                // 컨시어지: 현재 LEW 전용 상세 페이지만 존재. 신청자/매니저용은 향후 PR.
                if (recipientRole == UserRole.LEW) {
                    return "/lew/concierge-requests/" + referenceId;
                }
                return null;
            default:
                // DOCUMENT_REQUEST / MANUAL_EMAIL 등은 호출부에서 referenceType=APPLICATION 으로
                // 정규화하거나(권장) linkUrl 미설정 → 프론트 fallback.
                return null;
        }
    }

    private static String basePath(UserRole role) {
        if (role == UserRole.LEW) {
            return "/lew";
        }
        if (role == UserRole.ADMIN || role == UserRole.SYSTEM_ADMIN || role == UserRole.CONCIERGE_MANAGER) {
            return "/admin";
        }
        // APPLICANT (및 그 외) — 신청자 워크스페이스는 prefix 없음.
        return "";
    }

    /** 타입별 섹션 앵커. 신청자=스크롤 id / LEW=탭 key 와 동일 문자열. 없으면 빈 문자열. */
    private static String hashFor(NotificationType type) {
        switch (type) {
            // 결제 — 신청자/관리자 결제 섹션, 처리(증빙 업로드/확인) 위치.
            case PAYMENT_REQUESTED:
            case PAYMENT_CONFIRMED:
            case PAYMENT_EVIDENCE_UPLOADED:
            case PAYMENT_CONFIRMATION_REQUESTED:
            case MANUAL_PAYMENT_CONFIRMED_APPLICANT:
            case SLD_FEE_ADDED_APPLICANT:
            case SLD_FEE_SETTLEMENT_PENDING_ADMIN:
                return "#payment";
            // 영수증 카드.
            case INVOICE_ISSUED_APPLICANT:
                return "#receipts";
            // LoA 폼 다운로드/서명 업로드/최종본 위치.
            case LOA_FORM_SENT:
            case CONCIERGE_LOA_UPLOAD_CONFIRM:
                return "#loa";
            // 서류 요청 워크플로 — 신청자=요청 카드 영역, LEW=Documents 탭.
            case DOCUMENT_REQUEST_CREATED:
            case DOCUMENT_REQUEST_FULFILLED:
            case DOCUMENT_REQUEST_APPROVED:
            case DOCUMENT_REQUEST_REJECTED:
                return "#documents";
            // kVA — LEW kVA 탭.
            case KVA_ADJUSTED_BY_ADMIN_LEW:
            case KVA_ADJUSTMENT_REQUESTED_ADMIN:
            case KVA_ADJUSTMENT_SETTLED_LEW:
                return "#kva";
            // EMA 제출 — LEW EMA 탭.
            case EMA_SUBMISSION_REMINDER_LEW:
            case EMA_REJECTED_LEW:
                return "#ema";
            // SLD 미제출 리마인더 — LEW SLD 탭.
            case SLD_SUBMISSION_REMINDER_LEW:
                return "#sld";
            default:
                // KVA_CONFIRMED, PAYMENT_CONFIRMED_LEW, APPLICATION_LEW_ASSIGNED_LEW,
                // ADMIN_MANUAL_EMAIL_NOTICE 등은 페이지 상단(기본 위치)으로.
                return "";
        }
    }
}
