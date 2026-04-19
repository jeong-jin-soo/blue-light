import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
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
  DOCUMENT_REQUEST_CREATED: '🔔',
  DOCUMENT_REQUEST_FULFILLED: '📤',
  DOCUMENT_REQUEST_APPROVED: '✅',
  DOCUMENT_REQUEST_REJECTED: '⚠️',
  // Phase 5 — LEW kVA 확정 알림
  KVA_CONFIRMED: '💡',
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

    // Navigate to referenced entity
    // Phase 3: DOCUMENT_REQUEST notifications reference_type='DOCUMENT_REQUEST',
    //         reference_id=document_request_id. 백엔드가 metadata.applicationSeq를 같이
    //         싣지 않는 한 현재는 일반 APPLICATION 라우팅 fallback만 수행.
    if (n.referenceType === 'APPLICATION' && n.referenceId) {
      navigate(`${basePath}/applications/${n.referenceId}`);
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
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl sm:text-2xl font-bold text-gray-800">Notifications</h1>
        {notifications.length > 0 && (
          <Button variant="ghost" size="sm" onClick={handleMarkAllAsRead}>
            Mark all as read
          </Button>
        )}
      </div>

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
