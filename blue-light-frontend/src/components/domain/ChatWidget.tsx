import { useChatStore } from '../../stores/chatStore';
import ChatBubble from './ChatBubble';
import ChatWindow from './ChatWindow';

/**
 * 챗봇 위젯 — ChatBubble + ChatWindow 래퍼
 * App.tsx에서 마운트하여 모든 페이지에서 표시
 */
export default function ChatWidget() {
  const isOpen = useChatStore((s) => s.isOpen);

  return (
    <>
      {isOpen && <ChatWindow />}
      <ChatBubble />
    </>
  );
}
