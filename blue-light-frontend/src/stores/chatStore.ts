import { create } from 'zustand';
import type { ChatMessage } from '../types';
import { sendChatMessageStream } from '../api/chatApi';

interface ChatState {
  messages: ChatMessage[];
  isOpen: boolean;
  isLoading: boolean;
  isStreaming: boolean;
  suggestedQuestions: string[];
  hasUnread: boolean;
  sessionId: string;

  toggleChat: () => void;
  openChat: () => void;
  closeChat: () => void;
  sendMessage: (content: string) => Promise<void>;
  clearMessages: () => void;
}

const DEFAULT_SUGGESTIONS = [
  'How do I apply for a new EMA licence?',
  'What documents do I need to submit?',
  'How is the pricing determined?',
];

let messageId = 0;
const generateSessionId = () => crypto.randomUUID();

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  isOpen: false,
  isLoading: false,
  isStreaming: false,
  suggestedQuestions: DEFAULT_SUGGESTIONS,
  hasUnread: false,
  sessionId: generateSessionId(),

  toggleChat: () => set((s) => ({ isOpen: !s.isOpen, hasUnread: false })),
  openChat: () => set({ isOpen: true, hasUnread: false }),
  closeChat: () => set({ isOpen: false }),

  sendMessage: async (content: string) => {
    const userMsg: ChatMessage = {
      id: String(++messageId),
      role: 'user',
      content,
      timestamp: new Date(),
    };

    const assistantMsgId = String(++messageId);

    set((s) => ({
      messages: [...s.messages, userMsg],
      isLoading: true,
      isStreaming: false,
      suggestedQuestions: [],
    }));

    try {
      const { sessionId } = get();
      // 최근 10개 메시지를 컨텍스트로 전송
      const history = get()
        .messages.slice(-10)
        .map((m) => ({
          role: m.role === 'user' ? 'user' : 'model',
          content: m.content,
        }));

      await sendChatMessageStream(
        { message: content, sessionId, history },
        {
          onToken: (text) => {
            set((s) => {
              const lastMsg = s.messages[s.messages.length - 1];

              if (lastMsg && lastMsg.id === assistantMsgId) {
                // 기존 스트리밍 메시지에 토큰 추가
                const updatedMessages = [...s.messages];
                updatedMessages[updatedMessages.length - 1] = {
                  ...lastMsg,
                  content: lastMsg.content + text,
                };
                return {
                  messages: updatedMessages,
                  isLoading: false,
                  isStreaming: true,
                };
              } else {
                // 첫 토큰: assistant 메시지 생성
                return {
                  messages: [
                    ...s.messages,
                    {
                      id: assistantMsgId,
                      role: 'assistant' as const,
                      content: text,
                      timestamp: new Date(),
                    },
                  ],
                  isLoading: false,
                  isStreaming: true,
                };
              }
            });
          },
          onDone: (_fullMessage, suggestedQuestions) => {
            set((s) => ({
              isStreaming: false,
              suggestedQuestions:
                suggestedQuestions.length > 0 ? suggestedQuestions : DEFAULT_SUGGESTIONS,
              hasUnread: !s.isOpen,
            }));
          },
          onError: (errorText) => {
            set((s) => {
              const hasStreamingMsg = s.messages.some((m) => m.id === assistantMsgId);
              if (hasStreamingMsg) {
                // 이미 스트리밍 시작됨 → 현재까지의 내용 유지
                return {
                  isLoading: false,
                  isStreaming: false,
                  suggestedQuestions: DEFAULT_SUGGESTIONS,
                };
              }
              // 스트리밍 시작 전 에러 → 에러 메시지 표시
              return {
                messages: [
                  ...s.messages,
                  {
                    id: assistantMsgId,
                    role: 'assistant' as const,
                    content: errorText,
                    timestamp: new Date(),
                  },
                ],
                isLoading: false,
                isStreaming: false,
                suggestedQuestions: DEFAULT_SUGGESTIONS,
              };
            });
          },
        },
      );
    } catch {
      const errorMsg: ChatMessage = {
        id: String(++messageId),
        role: 'assistant',
        content: 'Sorry, I am unable to respond right now. Please try again later.',
        timestamp: new Date(),
      };
      set((s) => ({
        messages: [...s.messages, errorMsg],
        isLoading: false,
        isStreaming: false,
        suggestedQuestions: DEFAULT_SUGGESTIONS,
      }));
    }
  },

  clearMessages: () =>
    set({
      messages: [],
      suggestedQuestions: DEFAULT_SUGGESTIONS,
      sessionId: generateSessionId(),
    }),
}));
