import { useState, useRef, useEffect, useCallback } from 'react';
import { Button } from '../../../components/ui/Button';
import { SvgPreviewViewer } from '../../../components/ui/SvgPreviewViewer';
import { useSldChatStore } from '../../../stores/sldChatStore';
import { useToastStore } from '../../../stores/toastStore';
import { acceptSld } from '../../../api/sldChatApi';
import { getSldAiGeneration } from '../../../api/systemAdminApi';
import fileApi from '../../../api/fileApi';
import type { FileInfo, SldRequest } from '../../../types';

interface Props {
  applicationSeq: number;
  sldRequest: SldRequest;
  onSldUpdated: () => void;
  existingSldFiles?: FileInfo[];
  onFileDelete?: (fileId: number) => Promise<void>;
}

/**
 * SLD AI 채팅 패널 — 2분할 레이아웃 (좌: 채팅, 우: SVG 미리보기)
 */
export function SldChatPanel({ applicationSeq, sldRequest: _sldRequest, onSldUpdated, existingSldFiles = [], onFileDelete }: Props) {
  const [inputValue, setInputValue] = useState('');
  const [acceptLoading, setAcceptLoading] = useState(false);
  const [aiEnabled, setAiEnabled] = useState<boolean | null>(null);
  const [showReplaceDialog, setShowReplaceDialog] = useState(false);
  const toast = useToastStore();
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const {
    messages,
    isLoading,
    isStreaming,
    svgPreview,
    generatedFileId,
    activeToolName,
    activeToolDescription,
    isToolCompleted,
    progressStage,
    progressMessage,
    sendMessage,
    loadHistory,
    resetChat,
  } = useSldChatStore();

  // AI SLD 생성 토글 상태 조회
  useEffect(() => {
    getSldAiGeneration()
      .then((data) => setAiEnabled(data.enabled))
      .catch(() => setAiEnabled(true));
  }, []);

  // 채팅 이력 로드
  useEffect(() => {
    loadHistory(applicationSeq);
  }, [applicationSeq, loadHistory]);

  // 새 메시지 시 자동 스크롤 (채팅 컨테이너 내부만, 페이지 전체 스크롤 방지)
  useEffect(() => {
    const el = messagesEndRef.current;
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }, [messages, isLoading, activeToolName]);

  // 메시지 전송
  const handleSend = useCallback(async () => {
    const trimmed = inputValue.trim();
    if (!trimmed || isLoading || isStreaming) return;

    setInputValue('');
    await sendMessage(applicationSeq, trimmed);
  }, [inputValue, isLoading, isStreaming, applicationSeq, sendMessage]);

  // Enter 키 전송
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  // SLD 수락 — 기존 파일 존재 시 Replace/Add 선택
  const handleAcceptClick = useCallback(() => {
    if (!generatedFileId) return;
    if (existingSldFiles.length > 0) {
      setShowReplaceDialog(true);
    } else {
      doAccept(false);
    }
  }, [generatedFileId, existingSldFiles]);

  const doAccept = useCallback(async (replaceExisting: boolean) => {
    if (!generatedFileId) return;
    setShowReplaceDialog(false);
    setAcceptLoading(true);
    try {
      // Replace 선택 시 기존 SLD 파일 모두 삭제
      if (replaceExisting && onFileDelete) {
        for (const f of existingSldFiles) {
          try {
            await fileApi.deleteFile(f.fileSeq);
          } catch {
            // 개별 파일 삭제 실패는 무시하고 계속 진행
          }
        }
      }
      await acceptSld(applicationSeq, generatedFileId);
      toast.success('SLD accepted and uploaded successfully.');
      onSldUpdated();
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : 'Failed to accept SLD. Please try again.';
      toast.error(message);
    } finally {
      setAcceptLoading(false);
    }
  }, [applicationSeq, generatedFileId, existingSldFiles, onFileDelete, onSldUpdated]);

  // 대화 초기화
  const handleReset = useCallback(async () => {
    if (!confirm('Reset the conversation? All chat history will be cleared.')) return;
    await resetChat(applicationSeq);
  }, [applicationSeq, resetChat]);

  if (aiEnabled === false) {
    return (
      <div className="flex flex-col h-[600px] border border-gray-200 rounded-lg overflow-hidden bg-white">
        <div className="flex items-center px-4 py-2.5 bg-gray-50 border-b border-gray-200">
          <span className="text-base">🤖</span>
          <span className="text-sm font-medium text-gray-700 ml-2">SLD AI Generator</span>
        </div>
        <div className="flex-1 flex items-center justify-center">
          <div className="text-center px-6">
            <p className="text-sm font-medium text-gray-600 mb-1">AI SLD Generation is Disabled</p>
            <p className="text-xs text-gray-400">
              The system administrator has disabled AI SLD generation.
              Please contact the system administrator to enable this feature.
            </p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-[600px] border border-gray-200 rounded-lg overflow-hidden bg-white">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 bg-gray-50 border-b border-gray-200">
        <div className="flex items-center gap-2">
          <span className="text-base">🤖</span>
          <span className="text-sm font-medium text-gray-700">SLD AI Generator</span>
          {(isLoading || isStreaming || activeToolName) && (
            <span className="text-xs text-blue-600 animate-pulse">
              {progressStage === 'error' ? 'error' : progressMessage || 'processing...'}
            </span>
          )}
        </div>
        <Button variant="ghost" size="sm" onClick={handleReset}>
          Reset
        </Button>
      </div>

      {/* Main content: Chat + Preview */}
      <div className="flex flex-1 min-h-0">
        {/* Left: Chat Area (60%) */}
        <div className="flex flex-col w-3/5 border-r border-gray-200">
          {/* Messages */}
          <div className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
            {messages.length === 0 && !isLoading && (
              <div className="text-center text-gray-400 py-8">
                <span className="text-2xl block mb-2">💬</span>
                <p className="text-sm">
                  Start a conversation with the AI to generate your SLD.
                </p>
                <p className="text-xs text-gray-300 mt-1">
                  The AI will ask about your electrical requirements.
                </p>
              </div>
            )}

            {messages.map((msg) => {
              const displayContent = msg.role === 'assistant'
                ? stripSvgContent(msg.content)
                : msg.content;
              if (!displayContent.trim()) return null;
              return (
                <div
                  key={msg.sldChatMessageSeq}
                  className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
                >
                  <div
                    className={`max-w-[85%] rounded-lg px-3 py-2 text-sm whitespace-pre-wrap ${
                      msg.role === 'user'
                        ? 'bg-blue-600 text-white'
                        : 'bg-gray-100 text-gray-800'
                    }`}
                  >
                    {displayContent}
                  </div>
                </div>
              );
            })}

            {/* Tool execution indicator */}
            {activeToolName && (
              <div className="flex justify-start">
                <div className={`rounded-lg px-3 py-2 text-xs flex items-center gap-2 ${
                  isToolCompleted
                    ? 'bg-green-50 border border-green-200 text-green-700'
                    : 'bg-yellow-50 border border-yellow-200 text-yellow-700'
                }`}>
                  {isToolCompleted
                    ? <span>✅</span>
                    : <span className="animate-spin">⚙️</span>
                  }
                  {activeToolDescription || formatToolName(activeToolName)}
                </div>
              </div>
            )}

            {/* Progress / Loading indicator */}
            {isLoading && !activeToolName && (
              <div className="flex justify-start">
                {progressMessage ? (
                  <div className="bg-blue-50 border border-blue-200 rounded-lg px-3 py-2 text-xs flex items-center gap-2 text-blue-700">
                    <span className="animate-spin">⚙️</span>
                    {progressMessage}
                  </div>
                ) : (
                  <div className="bg-gray-100 rounded-lg px-3 py-2">
                    <div className="flex gap-1">
                      <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" />
                      <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:0.1s]" />
                      <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:0.2s]" />
                    </div>
                  </div>
                )}
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          {/* Input area */}
          <div className="border-t border-gray-200 px-3 py-2">
            <div className="flex gap-2">
              <textarea
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Describe your electrical requirements..."
                rows={2}
                maxLength={2000}
                className="flex-1 resize-none rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                disabled={isLoading || isStreaming}
              />
              <Button
                variant="primary"
                size="sm"
                onClick={handleSend}
                disabled={!inputValue.trim() || isLoading || isStreaming}
                className="self-end"
              >
                Send
              </Button>
            </div>
          </div>
        </div>

        {/* Right: SVG Preview (40%) */}
        <div className="w-2/5 flex flex-col">
          <SvgPreviewViewer svg={svgPreview || ''} className="flex-1" />
        </div>
      </div>

      {/* Bottom action bar */}
      {generatedFileId && (
        <div className="flex items-center justify-between px-4 py-2.5 bg-gray-50 border-t border-gray-200">
          <div className="text-xs text-gray-500">
            SLD generated. Review the preview and accept when ready.
            {existingSldFiles.length > 0 && (
              <span className="text-amber-600 ml-1">
                ({existingSldFiles.length} existing SLD file{existingSldFiles.length !== 1 ? 's' : ''})
              </span>
            )}
          </div>
          <div className="flex gap-2">
            <Button
              variant="primary"
              size="sm"
              onClick={handleAcceptClick}
              loading={acceptLoading}
              disabled={acceptLoading}
            >
              Accept & Upload
            </Button>
          </div>
        </div>
      )}

      {/* Replace/Add dialog */}
      {showReplaceDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-xl shadow-xl max-w-sm w-full mx-4 p-5">
            <h3 className="text-base font-semibold text-gray-800 mb-2">Existing SLD Files Found</h3>
            <p className="text-sm text-gray-600 mb-1">
              There {existingSldFiles.length === 1 ? 'is' : 'are'} {existingSldFiles.length} existing SLD file{existingSldFiles.length !== 1 ? 's' : ''}.
            </p>
            <p className="text-sm text-gray-600 mb-4">
              Would you like to replace them or add the new SLD as an additional file?
            </p>
            <div className="flex flex-col gap-2">
              <Button
                variant="primary"
                size="sm"
                onClick={() => doAccept(true)}
                className="w-full"
              >
                Replace Existing
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => doAccept(false)}
                className="w-full"
              >
                Add as Additional
              </Button>
              <button
                onClick={() => setShowReplaceDialog(false)}
                className="text-sm text-gray-500 hover:text-gray-700 mt-1"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * AI 응답에서 SVG 소스 코드를 제거 (미리보기 패널에 별도 표시되므로)
 * - 완성된 SVG 태그: <svg...>...</svg>
 * - 스트리밍 중 미완성 SVG: <svg 이후 전체 (아직 </svg> 미도착)
 * - 마크다운 코드블록: ```svg...``` 또는 ```xml...```
 * - 스트리밍 중 미완성 코드블록: ```svg 또는 ```xml 이후 전체
 */
function stripSvgContent(text: string): string {
  return text
    // 완성된 SVG 태그
    .replace(/<svg[\s\S]*?<\/svg>/gi, '')
    // 스트리밍 중 미완성 SVG 태그 (<svg 시작했지만 </svg> 없음)
    .replace(/<svg[\s\S]*$/gi, '')
    // 완성된 마크다운 코드블록
    .replace(/```(?:svg|xml)[\s\S]*?```/gi, '')
    // 스트리밍 중 미완성 코드블록 (``` 시작했지만 닫는 ``` 없음)
    .replace(/```(?:svg|xml)[\s\S]*$/gi, '')
    .trim();
}

/**
 * 도구 이름을 사용자 친화적 텍스트로 변환
 */
function formatToolName(tool: string): string {
  const toolNames: Record<string, string> = {
    get_application_details: 'Fetching application details...',
    get_standard_specs: 'Looking up electrical standards...',
    validate_sld_requirements: 'Validating requirements...',
    generate_sld: 'Generating SLD drawing...',
    generate_preview: 'Creating preview...',
  };
  return toolNames[tool] || `Running ${tool}...`;
}
