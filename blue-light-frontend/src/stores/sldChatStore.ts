import { create } from 'zustand';
import type { SldChatMessage } from '../types';
import { sendSldChatStream, getSldChatHistory, resetSldChat } from '../api/sldChatApi';

interface SldChatState {
  messages: SldChatMessage[];
  isLoading: boolean;
  isStreaming: boolean;
  svgPreview: string | null;
  generatedFileId: string | null;
  activeToolName: string | null;
  activeToolDescription: string | null;
  isToolCompleted: boolean;

  // Actions
  sendMessage: (applicationId: number, content: string) => Promise<void>;
  loadHistory: (applicationId: number) => Promise<void>;
  resetChat: (applicationId: number) => Promise<void>;
  clearState: () => void;
  setSvgPreview: (svg: string | null) => void;
}

let messageSeq = 0;

export const useSldChatStore = create<SldChatState>((set, _get) => ({
  messages: [],
  isLoading: false,
  isStreaming: false,
  svgPreview: null,
  generatedFileId: null,
  activeToolName: null,
  activeToolDescription: null,
  isToolCompleted: false,

  sendMessage: async (applicationId: number, content: string) => {
    // 사용자 메시지 즉시 표시
    const userMsg: SldChatMessage = {
      sldChatMessageSeq: --messageSeq, // 임시 음수 ID
      applicationSeq: applicationId,
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
      await sendSldChatStream(applicationId, content, {
        onToken: (text) => {
          set((s) => {
            const lastMsg = s.messages[s.messages.length - 1];

            if (lastMsg && lastMsg.sldChatMessageSeq === assistantMsgSeq) {
              // 기존 스트리밍 메시지에 토큰 추가
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
              // 첫 토큰: assistant 메시지 생성
              return {
                messages: [
                  ...s.messages,
                  {
                    sldChatMessageSeq: assistantMsgSeq,
                    applicationSeq: applicationId,
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
                  applicationSeq: applicationId,
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
      });
    } catch {
      set((s) => ({
        messages: [
          ...s.messages,
          {
            sldChatMessageSeq: --messageSeq,
            applicationSeq: applicationId,
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

  loadHistory: async (applicationId: number) => {
    try {
      const history = await getSldChatHistory(applicationId);
      set({ messages: history });
    } catch {
      // 이력 로드 실패 시 빈 상태 유지
    }
  },

  resetChat: async (applicationId: number) => {
    try {
      await resetSldChat(applicationId);
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
      console.warn('[SLD-Chat] Failed to reset chat on server, keeping local state.');
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
