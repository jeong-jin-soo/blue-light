import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import { PageHeader } from '../components/ui/PageHeader';
import { LoadingSpinner } from '../components/ui/LoadingSpinner';
import { Pagination } from '../components/data/Pagination';
import { useToastStore } from '../stores/toastStore';
import { useNotificationStore } from '../stores/notificationStore';
import { useAuthStore } from '../stores/authStore';
import { getBasePath } from '../utils/routeUtils';
import notificationApi from '../api/notificationApi';
import type { AppNotification, NotificationType } from '../types';

/**
 * Phase 3 PR#3 — 알림 타입별 아이콘 (AC-N1~N3)
 */
const NOTIFICATION_ICON: Record<NotificationType, string> = {
  PAYMENT_CONFIRMED: '💳',
  // 결제 요청(A-17) → 신청자
  PAYMENT_REQUESTED: '💳',
  // 결제 증빙 업로드(A-55) → ADMIN
  PAYMENT_EVIDENCE_UPLOADED: '🧾',
  // 결제 확인 요청(A-56) → ADMIN
  PAYMENT_CONFIRMATION_REQUESTED: '💳',
  // LoA 폼 전달(A-57) → 신청자
  LOA_FORM_SENT: '📄',
  // 매니저 대리 LoA 업로드 확인 → 신청자
  CONCIERGE_LOA_UPLOAD_CONFIRM: '✍️',
  // EMA 제출 리마인더 / 반려 → LEW
  EMA_SUBMISSION_REMINDER_LEW: '⏰',
  EMA_REJECTED_LEW: '⚠️',
  // 컨시어지 접수 / 견적
  CONCIERGE_REQUEST_SUBMITTED: '🤝',
  CONCIERGE_QUOTE_SENT: '💰',
  // PR4 — ADMIN의 결제 확인 후 배정된 LEW에게 전달되는 Phase 2 시작 알림
  PAYMENT_CONFIRMED_LEW: '💳',
  DOCUMENT_REQUEST_CREATED: '🔔',
  DOCUMENT_REQUEST_FULFILLED: '📤',
  DOCUMENT_REQUEST_APPROVED: '✅',
  DOCUMENT_REQUEST_REJECTED: '⚠️',
  // Phase 5 — LEW kVA 확정 알림
  KVA_CONFIRMED: '💡',
  // PR-2 (kva-postpayment) — ADMIN의 결제 후 kVA 변경 → 배정 LEW 알림 (전구⚡ 변경 의미)
  KVA_ADJUSTED_BY_ADMIN_LEW: '⚡',
  // PR-3 (kva-postpayment) — LEW의 kVA 변경 요청 → ADMIN 알림 (전구⚡ 요청)
  KVA_ADJUSTMENT_REQUESTED_ADMIN: '⚡',
  // #5 — kVA 변경으로 배정 LEW 등급 초과 → ADMIN 경고 (재배정 필요)
  LEW_GRADE_MISMATCH_ADMIN: '⚠️',
  // PR-4 (kva-postpayment) — ADMIN의 settlement 마킹 → 배정 LEW 알림 (정산 영수증 🧾)
  KVA_ADJUSTMENT_SETTLED_LEW: '🧾',
  // PR-4 (admin-manual-email D4=B) — ADMIN 수동 이메일 동반 인앱 알림 (📧 봉투)
  ADMIN_MANUAL_EMAIL_NOTICE: '📧',
  // ★ Concierge 강화 + 별도 수금 PR-2 — 별도 수금 확인 (💰 돈주머니)
  MANUAL_PAYMENT_CONFIRMED_APPLICANT: '💰',
  // ★ PR-2 — 영수증 자동 발행 안내 (🧾 영수증)
  INVOICE_ISSUED_APPLICANT: '🧾',
  // ★ PR-3 — 컨시어지 LEW 배정 알림 (🤝 컨시어지)
  CONCIERGE_LEW_ASSIGNED_LEW: '🤝',
  // Application LEW 배정 알림 (자동/ADMIN 수동) — 📋 신청서
  APPLICATION_LEW_ASSIGNED_LEW: '📋',
  // LEW 배정 해제/재배정 → 떠나는 LEW
  APPLICATION_LEW_UNASSIGNED_LEW: '📤',
  // LEW 가입 승인/거절 → 본인
  LEW_APPROVED: '✅',
  LEW_REJECTED: '⚠️',
};

export default function NotificationsPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const { user } = useAuthStore();
  const { fetchUnreadCount } = useNotificationStore();
  const basePath = getBasePath(user?.role);

  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const fetchNotifications = useCallback(async () => {
    try {
      const data = await notificationApi.getNotifications(page, 20);
      setNotifications(data.content);
      setTotalPages(data.totalPages);
    } catch {
      toast.error('Failed to load notifications');
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    fetchNotifications();
  }, [fetchNotifications]);

  const handleMarkAsRead = async (n: AppNotification) => {
    if (!n.isRead && !n.read) {
      try {
        await notificationApi.markAsRead(n.notificationSeq);
        setNotifications((prev) =>
          prev.map((item) =>
            item.notificationSeq === n.notificationSeq ? { ...item, isRead: true, read: true } : item
          )
        );
        fetchUnreadCount();
      } catch { /* silent */ }
    }

    // ① 백엔드가 제공한 딥링크(linkUrl) 우선 — NotificationLinkResolver 가 수신자 역할 인지
    //    상대경로 + 섹션 해시(#payment/#loa/#documents/#kva/#ema/#receipts)를 생성한다.
    //    클릭 시 처리 화면의 해당 위치로 바로 이동(섹션 스크롤/탭 선택은 대상 페이지가 처리).
    if (n.linkUrl) {
      navigate(n.linkUrl);
      return;
    }

    // ② linkUrl 미설정(레거시 행) — type/reference 기반 fallback 라우팅.
    // Phase 3: DOCUMENT_REQUEST notifications reference_type='DOCUMENT_REQUEST',
    //         reference_id=document_request_id. 백엔드가 metadata.applicationSeq를 같이
    //         싣지 않는 한 현재는 일반 APPLICATION 라우팅 fallback만 수행.
    if (n.referenceType === 'APPLICATION' && n.referenceId) {
      // PR4: PAYMENT_CONFIRMED_LEW 는 항상 LEW 워크스페이스로 deeplink.
      // PR-2 (kva-postpayment): KVA_ADJUSTED_BY_ADMIN_LEW 도 LEW 워크스페이스로 동일 패턴.
      // (수신자가 LEW 인 알림이므로 user.role 기준 basePath 와도 일치하지만,
      //  type이 곧 라우트를 의미하도록 명시적으로 처리.)
      if (
        n.type === 'PAYMENT_CONFIRMED_LEW' ||
        n.type === 'KVA_ADJUSTED_BY_ADMIN_LEW' ||
        // PR-4: settlement 마킹 알림 — 수신자가 LEW 이므로 LEW 워크스페이스로.
        n.type === 'KVA_ADJUSTMENT_SETTLED_LEW' ||
        // LEW 배정 알림 — 수신자가 LEW 이므로 LEW 워크스페이스 신청 상세로.
        n.type === 'APPLICATION_LEW_ASSIGNED_LEW'
      ) {
        navigate(`/lew/applications/${n.referenceId}`);
      } else if (n.type === 'KVA_ADJUSTMENT_REQUESTED_ADMIN') {
        // PR-3: 수신자가 ADMIN — admin 워크스페이스의 신청 상세로 이동.
        navigate(`/admin/applications/${n.referenceId}`);
      } else {
        // ★ Concierge 강화 PR-2 (MANUAL_PAYMENT_CONFIRMED_APPLICANT, INVOICE_ISSUED_APPLICANT)
        //   — 수신자가 APPLICANT 이므로 basePath('/applicant/applications' or '/dashboard'-aware)
        //   기준 라우팅. 영수증 알림은 #receipts 해시로 영수증 카드까지 자동 스크롤(있을 때).
        if (n.type === 'INVOICE_ISSUED_APPLICANT') {
          navigate(`${basePath}/applications/${n.referenceId}#receipts`);
        } else {
          // PR-4 (ADMIN_MANUAL_EMAIL_NOTICE), PR-2 (MANUAL_PAYMENT_CONFIRMED_APPLICANT) 등
          // 수신자 role 의 워크스페이스 (APPLICANT → /applications, LEW → /lew/applications) 로 이동.
          navigate(`${basePath}/applications/${n.referenceId}`);
        }
      }
    } else if (n.referenceType === 'CONCIERGE_REQUEST' && n.referenceId) {
      // ★ Concierge 강화 PR-2/PR-3 — referenceType=CONCIERGE_REQUEST.
      // - CONCIERGE_LEW_ASSIGNED_LEW (수신자=LEW) → LEW 컨시어지 페이지
      // - MANUAL_PAYMENT_CONFIRMED_APPLICANT / INVOICE_ISSUED_APPLICANT (수신자=APPLICANT)
      //   → 컨시어지 신청자에게 본인 컨시어지 상세 페이지가 별도 없으므로(향후 별도 PR)
      //     안전한 fallback: notifications 페이지에 머무르고 message 만 갱신 (단순 dismiss).
      //     향후 applicant 용 컨시어지 상세 페이지가 생기면 활성화.
      if (n.type === 'CONCIERGE_LEW_ASSIGNED_LEW') {
        navigate(`/lew/concierge-requests/${n.referenceId}`);
      }
      // 그 외 알림 타입은 단순 마킹만 — 향후 PR 에서 라우팅 활성화.
    } else if (n.referenceType === 'MANUAL_EMAIL') {
      // PR-4 (ADMIN_MANUAL_EMAIL_NOTICE) 의 relatedApplication 미지정 케이스 — 단순 dismiss.
      // 향후 manual-email 상세 페이지가 생기면 deeplink 활성화 가능. 현재는 마킹만.
    } else if (n.referenceType === 'DOCUMENT_REQUEST' && n.referenceId) {
      // PR#4에서 referenceType=APPLICATION + metadata로 정규화 예정.
      // 임시: 알림 message에서 applicationSeq 파싱 불가 → 알림 목록 유지.
      // (스펙상 deep link는 `/applications/:appId#doc-req-:id`)
    }
  };

  const handleMarkAllAsRead = async () => {
    try {
      await notificationApi.markAllAsRead();
      setNotifications((prev) => prev.map((n) => ({ ...n, isRead: true, read: true })));
      fetchUnreadCount();
      toast.success('All notifications marked as read');
    } catch {
      toast.error('Failed to mark all as read');
    }
  };

  const formatTime = (dateStr: string) => {
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMin / 60);
    const diffDays = Math.floor(diffHours / 24);

    if (diffMin < 1) return 'Just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    if (diffHours < 24) return `${diffHours}h ago`;
    if (diffDays < 7) return `${diffDays}d ago`;
    return date.toLocaleDateString();
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading notifications..." />
      </div>
    );
  }

  return (
    <div className="max-w-7xl mx-auto space-y-6">
      <PageHeader
        title="Notifications"
        actions={
          notifications.length > 0 ? (
            <Button variant="ghost" size="sm" onClick={handleMarkAllAsRead}>
              Mark all as read
            </Button>
          ) : undefined
        }
      />

      {notifications.length === 0 ? (
        <Card>
          <div className="text-center py-12">
            <svg className="w-12 h-12 mx-auto text-gray-300 mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
            </svg>
            <p className="text-sm text-gray-500">No notifications yet</p>
          </div>
        </Card>
      ) : (
        <div className="space-y-2">
          {notifications.map((n) => {
            const isUnread = !n.isRead && !n.read;
            return (
              <button
                key={n.notificationSeq}
                onClick={() => handleMarkAsRead(n)}
                className={`w-full text-left p-4 rounded-lg border transition-colors cursor-pointer ${
                  isUnread
                    ? 'bg-blue-50 border-blue-200 hover:bg-blue-100'
                    : 'bg-white border-gray-200 hover:bg-gray-50'
                }`}
              >
                <div className="flex items-start gap-3">
                  <div className={`mt-1 w-2 h-2 rounded-full flex-shrink-0 ${isUnread ? 'bg-blue-500' : 'bg-transparent'}`} />
                  <span className="text-lg flex-shrink-0 leading-none mt-0.5" aria-hidden>
                    {NOTIFICATION_ICON[n.type] ?? '🔔'}
                  </span>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between gap-2">
                      <p className={`text-sm ${isUnread ? 'font-semibold text-gray-900' : 'font-medium text-gray-700'}`}>
                        {n.title}
                      </p>
                      <span className="text-xs text-gray-400 flex-shrink-0">{formatTime(n.createdAt)}</span>
                    </div>
                    <p className="text-sm text-gray-500 mt-0.5">{n.message}</p>
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      )}

      {totalPages > 1 && (
        <Pagination page={page} totalPages={totalPages} onPageChange={setPage} />
      )}
    </div>
  );
}
