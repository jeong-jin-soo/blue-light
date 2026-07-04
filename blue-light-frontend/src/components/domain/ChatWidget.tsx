import { useChatStore } from '../../stores/chatStore';
import { useAuthStore } from '../../stores/authStore';
import ChatBubble from './ChatBubble';
import ChatWindow from './ChatWindow';

/**
 * 챗봇 위젯 — ChatBubble + ChatWindow 래퍼
 * App.tsx에서 마운트. 로그인 사용자에게만 표시한다 —
 * 공개 페이지(랜딩·서비스 상세)의 문의 채널은 WhatsApp 플로팅 버튼 하나로 일원화(WhatsApp 퍼스트 개편).
 */
export default function ChatWidget() {
  const isOpen = useChatStore((s) => s.isOpen);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  if (!isAuthenticated) return null;

  return (
    <>
      {isOpen && <ChatWindow />}
      <ChatBubble />
    </>
  );
}
