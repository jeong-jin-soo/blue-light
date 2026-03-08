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
  activeToolDescription: string | null;
  isToolCompleted: boolean;

  // Actions
  sendMessage: (sldOrderSeq: number, content: string, attachedFileSeq?: number) => Promise<void>;
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
  activeToolDescription: null,
  isToolCompleted: false,

  sendMessage: async (sldOrderSeq: number, content: string, attachedFileSeq?: number) => {
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
      activeToolDescription: null,
      isToolCompleted: false,
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
                activeToolDescription: null,
                isToolCompleted: false,
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
                activeToolDescription: null,
                isToolCompleted: false,
              };
            }
          });
        },

        onToolStart: (tool, description) => {
          set({ activeToolName: tool, activeToolDescription: description || null, isToolCompleted: false, isLoading: false });
        },

        onToolResult: (_tool, summary) => {
          // Keep tool indicator visible with result summary until next tool_start or token
          // This prevents showing "..." dots during LLM thinking time (several seconds)
          set((s) => ({
            activeToolDescription: summary || s.activeToolDescription,
            isToolCompleted: true,
          }));
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
            activeToolDescription: null,
            isToolCompleted: false,
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
                activeToolDescription: null,
                isToolCompleted: false,
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
              activeToolDescription: null,
              isToolCompleted: false,
            };
          });
        },
      }, attachedFileSeq);
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
        activeToolDescription: null,
        isToolCompleted: false,
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
        isLoading: false,
        isStreaming: false,
        svgPreview: null,
        generatedFileId: null,
        activeToolName: null,
        activeToolDescription: null,
        isToolCompleted: false,
      });
    } catch {
      // API 실패 시 로컬 상태 유지 (서버와 불일치 방지)
      console.warn('[SLD-Order-Chat] Failed to reset chat on server, keeping local state.');
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
      activeToolDescription: null,
      isToolCompleted: false,
    }),

  setSvgPreview: (svg: string | null) => set({ svgPreview: svg }),
}));
