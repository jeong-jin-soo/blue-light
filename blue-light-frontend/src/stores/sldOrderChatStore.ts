import { create } from 'zustand';
import type { SldChatMessage } from '../types';
import { sldOrderStreamChat, sldOrderLoadHistory, sldOrderResetChat } from '../api/sldOrderChatApi';

interface SldOrderChatState {
  messages: SldChatMessage[];
  isLoading: boolean;
  isStreaming: boolean;
  svgPreview: string | null;
  generatedFileId: string | null;
  activeToolName: string | null;

  // Actions
  sendMessage: (sldOrderSeq: number, content: string) => Promise<void>;
  loadHistory: (sldOrderSeq: number) => Promise<void>;
  resetChat: (sldOrderSeq: number) => Promise<void>;
  clearState: () => void;
  setSvgPreview: (svg: string | null) => void;
}

let messageSeq = 0;

export const useSldOrderChatStore = create<SldOrderChatState>((set, _get) => ({
  messages: [],
  isLoading: false,
  isStreaming: false,
  svgPreview: null,
  generatedFileId: null,
  activeToolName: null,

  sendMessage: async (sldOrderSeq: number, content: string) => {
    // Show user message immediately
    const userMsg: SldChatMessage = {
      sldChatMessageSeq: --messageSeq,
      applicationSeq: sldOrderSeq, // reuse field name from shared type
      role: 'user',
      content,
      createdAt: new Date().toISOString(),
    };

    const assistantMsgSeq = --messageSeq;

    set((s) => ({
      messages: [...s.messages, userMsg],
      isLoading: true,
      isStreaming: false,
      activeToolName: null,
    }));

    try {
      await sldOrderStreamChat(sldOrderSeq, content, {
        onToken: (text) => {
          set((s) => {
            const lastMsg = s.messages[s.messages.length - 1];

            if (lastMsg && lastMsg.sldChatMessageSeq === assistantMsgSeq) {
              // Append token to existing streaming message
              const updated = [...s.messages];
              updated[updated.length - 1] = {
                ...lastMsg,
                content: lastMsg.content + text,
              };
              return {
                messages: updated,
                isLoading: false,
                isStreaming: true,
                activeToolName: null,
              };
            } else {
              // First token: create assistant message
              return {
                messages: [
                  ...s.messages,
                  {
                    sldChatMessageSeq: assistantMsgSeq,
                    applicationSeq: sldOrderSeq,
                    role: 'assistant' as const,
                    content: text,
                    createdAt: new Date().toISOString(),
                  },
                ],
                isLoading: false,
                isStreaming: true,
                activeToolName: null,
              };
            }
          });
        },

        onToolStart: (tool) => {
          set({ activeToolName: tool, isLoading: false });
        },

        onToolResult: (_tool, _summary) => {
          set({ activeToolName: null });
        },

        onSldPreview: (svg) => {
          set({ svgPreview: svg });
        },

        onFileGenerated: (fileId) => {
          set({ generatedFileId: fileId });
        },

        onDone: (_fullMessage) => {
          set({
            isStreaming: false,
            isLoading: false,
            activeToolName: null,
          });
        },

        onError: (errorText) => {
          set((s) => {
            const hasStreamingMsg = s.messages.some(
              (m) => m.sldChatMessageSeq === assistantMsgSeq,
            );
            if (hasStreamingMsg) {
              return {
                isLoading: false,
                isStreaming: false,
                activeToolName: null,
              };
            }
            return {
              messages: [
                ...s.messages,
                {
                  sldChatMessageSeq: assistantMsgSeq,
                  applicationSeq: sldOrderSeq,
                  role: 'assistant' as const,
                  content: errorText,
                  createdAt: new Date().toISOString(),
                },
              ],
              isLoading: false,
              isStreaming: false,
              activeToolName: null,
            };
          });
        },
      });
    } catch {
      set((s) => ({
        messages: [
          ...s.messages,
          {
            sldChatMessageSeq: --messageSeq,
            applicationSeq: sldOrderSeq,
            role: 'assistant' as const,
            content: 'Sorry, the AI service is currently unavailable. Please try again later.',
            createdAt: new Date().toISOString(),
          },
        ],
        isLoading: false,
        isStreaming: false,
        activeToolName: null,
      }));
    }
  },

  loadHistory: async (sldOrderSeq: number) => {
    try {
      const history = await sldOrderLoadHistory(sldOrderSeq);
      set({ messages: history });
    } catch {
      // Keep empty state on history load failure
    }
  },

  resetChat: async (sldOrderSeq: number) => {
    try {
      await sldOrderResetChat(sldOrderSeq);
      set({
        messages: [],
        svgPreview: null,
        generatedFileId: null,
        activeToolName: null,
      });
    } catch {
      // Clear local state even on failure
      set({
        messages: [],
        svgPreview: null,
        generatedFileId: null,
        activeToolName: null,
      });
    }
  },

  clearState: () =>
    set({
      messages: [],
      isLoading: false,
      isStreaming: false,
      svgPreview: null,
      generatedFileId: null,
      activeToolName: null,
    }),

  setSvgPreview: (svg: string | null) => set({ svgPreview: svg }),
}));
