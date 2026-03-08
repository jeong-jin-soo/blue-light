import { useState, useRef, useEffect, useCallback } from 'react';
import { Button } from '../../../components/ui/Button';
import { SvgPreviewViewer } from '../../../components/ui/SvgPreviewViewer';
import { useSldOrderChatStore } from '../../../stores/sldOrderChatStore';
import { useToastStore } from '../../../stores/toastStore';
import { sldOrderAcceptSld } from '../../../api/sldOrderChatApi';
import { getSldAiGeneration } from '../../../api/systemAdminApi';
import { sldManagerApi } from '../../../api/sldManagerApi';

interface Props {
  sldOrderSeq: number;
  onSldUpdated: () => void;
}

/**
 * SLD Manager AI Chat Panel -- 2-column layout (left: chat, right: SVG preview)
 * Uses sldOrderChatApi and useSldOrderChatStore for SLD order context.
 */
export function SldManagerChatPanel({ sldOrderSeq, onSldUpdated }: Props) {
  const [inputValue, setInputValue] = useState('');
  const [acceptLoading, setAcceptLoading] = useState(false);
  const [aiEnabled, setAiEnabled] = useState<boolean | null>(null);
  const [attachedFile, setAttachedFile] = useState<{ name: string; fileSeq: number } | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const toast = useToastStore();

  const {
    messages,
    isLoading,
    isStreaming,
    svgPreview,
    generatedFileId,
    activeToolName,
    activeToolDescription,
    isToolCompleted,
    sendMessage,
    loadHistory,
    resetChat,
  } = useSldOrderChatStore();

  // AI SLD generation toggle check
  useEffect(() => {
    getSldAiGeneration()
      .then((data) => setAiEnabled(data.enabled))
      .catch(() => setAiEnabled(true));
  }, []);

  // Load chat history
  useEffect(() => {
    loadHistory(sldOrderSeq);
  }, [sldOrderSeq, loadHistory]);

  // Auto-scroll on new messages (chat container only, not the whole page)
  useEffect(() => {
    const el = messagesEndRef.current;
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }, [messages, isLoading, activeToolName]);

  // File attachment
  const handleFileSelect = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    // Validate size (10MB)
    if (file.size > 10 * 1024 * 1024) {
      toast.error('File size must be under 10MB.');
      return;
    }

    setIsUploading(true);
    try {
      const result = await sldManagerApi.uploadFile(sldOrderSeq, file, 'CIRCUIT_SCHEDULE');
      setAttachedFile({ name: file.name, fileSeq: result.fileSeq });
    } catch {
      toast.error('Failed to upload file. Please try again.');
    } finally {
      setIsUploading(false);
      // Reset input so same file can be re-selected
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }, [sldOrderSeq]);

  // Send message
  const handleSend = useCallback(async () => {
    const trimmed = inputValue.trim();
    if (!trimmed || isLoading || isStreaming) return;

    const fileSeq = attachedFile?.fileSeq;
    setInputValue('');
    setAttachedFile(null);
    await sendMessage(sldOrderSeq, trimmed, fileSeq);
  }, [inputValue, isLoading, isStreaming, sldOrderSeq, sendMessage, attachedFile]);

  // Enter key to send
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSend();
      }
    },
    [handleSend],
  );

  // Accept generated SLD
  const handleAccept = useCallback(async () => {
    if (!generatedFileId) return;
    setAcceptLoading(true);
    try {
      await sldOrderAcceptSld(sldOrderSeq, generatedFileId);
      toast.success('SLD accepted and uploaded successfully.');
      onSldUpdated();
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : 'Failed to accept SLD. Please try again.';
      toast.error(message);
    } finally {
      setAcceptLoading(false);
    }
  }, [sldOrderSeq, generatedFileId, onSldUpdated]);

  // Reset conversation
  const handleReset = useCallback(async () => {
    if (!confirm('Reset the conversation? All chat history will be cleared.')) return;
    await resetChat(sldOrderSeq);
  }, [sldOrderSeq, resetChat]);

  if (aiEnabled === false) {
    return (
      <div className="flex flex-col h-[600px] border border-gray-200 rounded-lg overflow-hidden bg-white">
        <div className="flex items-center px-4 py-2.5 bg-gray-50 border-b border-gray-200">
          <span className="text-base">&#129302;</span>
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
          <span className="text-base">&#129302;</span>
          <span className="text-sm font-medium text-gray-700">SLD AI Generator</span>
          {(isLoading || isStreaming || activeToolName) && (
            <span className="text-xs text-blue-600 animate-pulse">generating...</span>
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
                <span className="text-2xl block mb-2">&#128172;</span>
                <p className="text-sm">
                  Start a conversation with the AI to generate your SLD.
                </p>
                <p className="text-xs text-gray-300 mt-1">
                  The AI will ask about your electrical requirements.
                  <br />
                  You can attach a circuit schedule file (Excel, CSV, or image) using the <span>&#128206;</span> button.
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
                    ? <span>&#10004;&#65039;</span>
                    : <span className="animate-spin">&#9881;&#65039;</span>
                  }
                  {activeToolDescription || formatToolName(activeToolName)}
                </div>
              </div>
            )}

            {/* Loading indicator */}
            {isLoading && !activeToolName && (
              <div className="flex justify-start">
                <div className="bg-gray-100 rounded-lg px-3 py-2">
                  <div className="flex gap-1">
                    <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" />
                    <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:0.1s]" />
                    <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce [animation-delay:0.2s]" />
                  </div>
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          {/* Input area */}
          <div className="border-t border-gray-200 px-3 py-2">
            {/* Attached file indicator */}
            {attachedFile && (
              <div className="flex items-center gap-2 mb-1.5 px-1">
                <span className="text-xs text-blue-600 bg-blue-50 px-2 py-0.5 rounded flex items-center gap-1">
                  <span>&#128206;</span>
                  {attachedFile.name}
                  <button
                    onClick={() => setAttachedFile(null)}
                    className="ml-1 text-blue-400 hover:text-blue-600"
                    title="Remove attachment"
                  >
                    &#10005;
                  </button>
                </span>
              </div>
            )}
            <div className="flex gap-2">
              {/* File attachment button */}
              <input
                ref={fileInputRef}
                type="file"
                accept=".xlsx,.xls,.csv,.jpg,.jpeg,.png,.pdf"
                onChange={handleFileSelect}
                className="hidden"
              />
              <button
                onClick={() => fileInputRef.current?.click()}
                disabled={isLoading || isStreaming || isUploading}
                className="self-end p-2 text-gray-400 hover:text-gray-600 disabled:opacity-50 disabled:cursor-not-allowed"
                title="Attach circuit schedule file (Excel, CSV, Image, PDF)"
              >
                {isUploading ? (
                  <span className="animate-spin inline-block">&#9881;&#65039;</span>
                ) : (
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
                  </svg>
                )}
              </button>
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
          </div>
          <div className="flex gap-2">
            <Button
              variant="primary"
              size="sm"
              onClick={handleAccept}
              loading={acceptLoading}
              disabled={acceptLoading}
            >
              Accept & Upload
            </Button>
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
 * Convert tool name to user-friendly text
 */
function formatToolName(tool: string): string {
  const toolNames: Record<string, string> = {
    get_application_details: 'Fetching order details...',
    get_standard_specs: 'Looking up electrical standards...',
    validate_sld_requirements: 'Validating requirements...',
    generate_sld: 'Generating SLD drawing...',
    generate_preview: 'Creating preview...',
  };
  return toolNames[tool] || `Running ${tool}...`;
}
