import { create } from 'zustand';
import type { ChatMessage } from '../types';
import { sendChatMessage } from '../api/chatApi';

interface ChatState {
  messages: ChatMessage[];
  isOpen: boolean;
  isLoading: boolean;
  suggestedQuestions: string[];
  hasUnread: boolean;

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

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  isOpen: false,
  isLoading: false,
  suggestedQuestions: DEFAULT_SUGGESTIONS,
  hasUnread: false,

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
    set((s) => ({ messages: [...s.messages, userMsg], isLoading: true, suggestedQuestions: [] }));

    try {
      // 최근 10개 메시지를 컨텍스트로 전송
      const history = get()
        .messages.slice(-10)
        .map((m) => ({
          role: m.role === 'user' ? 'user' : 'model',
          content: m.content,
        }));

      const response = await sendChatMessage({ message: content, history });

      const assistantMsg: ChatMessage = {
        id: String(++messageId),
        role: 'assistant',
        content: response.message,
        timestamp: new Date(),
      };

      set((s) => ({
        messages: [...s.messages, assistantMsg],
        isLoading: false,
        suggestedQuestions: response.suggestedQuestions ?? [],
        hasUnread: !s.isOpen,
      }));
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
        suggestedQuestions: DEFAULT_SUGGESTIONS,
      }));
    }
  },

  clearMessages: () =>
    set({ messages: [], suggestedQuestions: DEFAULT_SUGGESTIONS }),
}));
