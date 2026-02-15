import { useChatStore } from '../../stores/chatStore';

/**
 * 플로팅 챗 버블 — 화면 우하단 고정
 */
export default function ChatBubble() {
  const { isOpen, toggleChat, hasUnread } = useChatStore();

  return (
    <button
      onClick={toggleChat}
      className="fixed bottom-6 right-6 z-50 w-14 h-14 rounded-full bg-primary text-white
                 shadow-lg hover:bg-primary-hover transition-all duration-200
                 flex items-center justify-center cursor-pointer"
      aria-label={isOpen ? 'Close chat' : 'Open chat assistant'}
    >
      {isOpen ? (
        // X 아이콘
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
        </svg>
      ) : (
        // 챗 아이콘
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
          />
        </svg>
      )}

      {/* 미읽음 알림 */}
      {hasUnread && !isOpen && (
        <span className="absolute -top-1 -right-1 w-4 h-4 bg-error rounded-full border-2 border-white" />
      )}
    </button>
  );
}
