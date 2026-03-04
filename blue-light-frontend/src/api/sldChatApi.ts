import axiosClient from './axiosClient';
import type { SldChatMessage, SldRequest, SldSseEvent } from '../types';

/**
 * SLD AI 채팅 SSE 콜백 인터페이스
 */
export interface SldStreamCallbacks {
  onToken: (text: string) => void;
  onToolStart: (tool: string, description?: string) => void;
  onToolResult: (tool: string, summary: string) => void;
  onSldPreview: (svg: string) => void;
  onFileGenerated: (fileId: string) => void;
  onDone: (fullMessage: string) => void;
  onError: (error: string) => void;
}

/**
 * SLD AI 채팅 SSE 스트리밍
 */
export const sendSldChatStream = async (
  applicationId: number,
  message: string,
  callbacks: SldStreamCallbacks,
): Promise<void> => {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090/api';

  const response = await fetch(
    `${baseUrl}/admin/applications/${applicationId}/sld-chat/stream`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
      credentials: 'include',
    },
  );

  if (!response.ok) {
    if (response.status === 429) {
      callbacks.onError('Too many requests. Please try again later.');
      return;
    }
    if (response.status === 401 || response.status === 403) {
      callbacks.onError('Authentication required. Please log in again.');
      return;
    }
    // Try to extract error message from response body
    let errorMsg = 'Failed to connect to the AI agent.';
    try {
      const errorBody = await response.json();
      if (errorBody?.message) errorMsg = errorBody.message;
    } catch {
      // ignore parse errors
    }
    callbacks.onError(`${errorMsg} (${response.status})`);
    return;
  }

  const reader = response.body?.getReader();
  if (!reader) {
    callbacks.onError('Streaming is not supported.');
    return;
  }

  const decoder = new TextDecoder();
  let buffer = '';
  let receivedDone = false;

  // 타임아웃: 120초 동안 데이터 수신이 없으면 연결 해제
  const STREAM_TIMEOUT_MS = 120_000;
  let timeoutId: ReturnType<typeof setTimeout> | null = null;

  const resetTimeout = () => {
    if (timeoutId) clearTimeout(timeoutId);
    timeoutId = setTimeout(() => {
      reader.cancel();
      if (!receivedDone) {
        callbacks.onError('Connection timed out. Please try again.');
      }
    }, STREAM_TIMEOUT_MS);
  };

  try {
    resetTimeout();

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      resetTimeout(); // 데이터 수신마다 타임아웃 리셋

      buffer += decoder.decode(value, { stream: true });

      // SSE 이벤트는 빈 줄(\n\n)로 구분된다.
      // 각 이벤트 내에서 'event:' 줄과 'data:' 줄이 있을 수 있다.
      const events = buffer.split('\n\n');
      buffer = events.pop() || ''; // 마지막 불완전 이벤트를 buffer에 유지

      for (const eventBlock of events) {
        if (!eventBlock.trim()) continue;

        // 이벤트 블록 내에서 data: 줄 추출
        const dataLine = eventBlock
          .split('\n')
          .find((l) => l.startsWith('data:'));
        if (!dataLine) continue;

        const data = dataLine.slice(5).trim();
        if (!data) continue;

        try {
          const parsed: SldSseEvent = JSON.parse(data);

          if (parsed.type !== 'token') {
            console.log('[SLD-SSE]', parsed.type, parsed.type === 'sld_preview' ? `(svg: ${parsed.svg?.length ?? 0} chars)` : '', parsed);
          }

          switch (parsed.type) {
            case 'token':
              if (parsed.content) callbacks.onToken(parsed.content);
              break;
            case 'tool_start':
              if (parsed.tool) callbacks.onToolStart(parsed.tool, parsed.description);
              break;
            case 'tool_result':
              if (parsed.tool) callbacks.onToolResult(parsed.tool, parsed.summary || '');
              break;
            case 'sld_preview':
              if (parsed.svg) callbacks.onSldPreview(parsed.svg);
              break;
            case 'file_generated':
              if (parsed.fileId) callbacks.onFileGenerated(parsed.fileId);
              break;
            case 'done':
              receivedDone = true;
              callbacks.onDone(parsed.content || '');
              break;
            case 'error':
              receivedDone = true;
              callbacks.onError(parsed.content || 'Unknown error occurred.');
              break;
            case 'model_switch':
              // AI model fallback — show user which model is being used
              if (parsed.content) {
                callbacks.onToken(`\n⚡ ${parsed.content}\n\n`);
              }
              break;
            case 'status':
              // AI retry status — show as token so user sees progress
              if (parsed.content) callbacks.onToken(parsed.content + '\n');
              break;
            case 'heartbeat':
            case 'session':
            case 'template_matched':
              // Silently handled — no user-visible action needed
              break;
          }
        } catch (parseErr) {
          console.warn('[SLD-SSE] Parse error:', parseErr, 'data:', data.slice(0, 200));
        }
      }
    }

    // 스트림 종료 후 done 이벤트를 받지 못했다면 에러 처리
    if (!receivedDone) {
      callbacks.onError('Connection closed unexpectedly. Please try again.');
    }
  } catch (err) {
    if (!receivedDone) {
      callbacks.onError('Connection lost. Please try again.');
    }
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
    reader.releaseLock();
  }
};

/**
 * SLD 채팅 이력 조회
 */
export const getSldChatHistory = async (applicationId: number): Promise<SldChatMessage[]> => {
  const response = await axiosClient.get<SldChatMessage[]>(
    `/admin/applications/${applicationId}/sld-chat/history`,
  );
  return response.data;
};

/**
 * SLD 채팅 초기화
 */
export const resetSldChat = async (applicationId: number): Promise<void> => {
  await axiosClient.post(`/admin/applications/${applicationId}/sld-chat/reset`);
};

/**
 * SLD 수락 — AI 생성 SLD PDF를 확정
 */
export const acceptSld = async (
  applicationId: number,
  fileId: string,
): Promise<SldRequest> => {
  const response = await axiosClient.post<SldRequest>(
    `/admin/applications/${applicationId}/sld-chat/accept`,
    { fileId },
  );
  return response.data;
};

/**
 * SVG 미리보기 URL 생성
 */
export const getSldPreviewUrl = (applicationId: number, fileId: string): string => {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090/api';
  return `${baseUrl}/admin/applications/${applicationId}/sld-chat/preview/${fileId}`;
};
