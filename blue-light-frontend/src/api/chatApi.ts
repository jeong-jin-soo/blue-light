import axiosClient from './axiosClient';
import type { ChatRequest, ChatResponse } from '../types';

/**
 * AI 챗봇 API (기존 동기 방식 — fallback)
 */
export const sendChatMessage = async (request: ChatRequest): Promise<ChatResponse> => {
  const response = await axiosClient.post<ChatResponse>('/public/chat', request);
  return response.data;
};

/**
 * SSE 스트리밍 콜백 인터페이스
 */
export interface StreamCallbacks {
  onToken: (text: string) => void;
  onDone: (fullMessage: string, suggestedQuestions: string[]) => void;
  onError: (error: string) => void;
}

/**
 * SSE 스트리밍 챗봇 API
 */
export const sendChatMessageStream = async (
  request: ChatRequest,
  callbacks: StreamCallbacks,
): Promise<void> => {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090/api';
  const token = localStorage.getItem('bluelight_token');

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(`${baseUrl}/public/chat/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    if (response.status === 429) {
      callbacks.onError('Too many requests. Please try again later.');
      return;
    }
    callbacks.onError('Failed to connect to the assistant.');
    return;
  }

  const reader = response.body?.getReader();
  if (!reader) {
    callbacks.onError('Streaming is not supported.');
    return;
  }

  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // SSE 이벤트 파싱
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim();
          if (!data) continue;

          try {
            const parsed = JSON.parse(data);
            if (parsed.type === 'token') {
              callbacks.onToken(parsed.content);
            } else if (parsed.type === 'done') {
              callbacks.onDone(parsed.content, parsed.suggestedQuestions || []);
            } else if (parsed.type === 'error') {
              callbacks.onError(parsed.content);
            }
          } catch {
            // 파싱 불가능한 라인 무시
          }
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
};
