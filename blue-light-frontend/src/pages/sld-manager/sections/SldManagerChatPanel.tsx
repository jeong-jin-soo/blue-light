import { useState, useRef, useEffect, useCallback } from 'react';
import { Button } from '../../../components/ui/Button';
import { SvgPreviewViewer } from '../../../components/ui/SvgPreviewViewer';
import { useSldOrderChatStore } from '../../../stores/sldOrderChatStore';
import { sldOrderAcceptSld } from '../../../api/sldOrderChatApi';

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
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const {
    messages,
    isLoading,
    isStreaming,
    svgPreview,
    generatedFileId,
    activeToolName,
    sendMessage,
    loadHistory,
    resetChat,
  } = useSldOrderChatStore();

  // Load chat history
  useEffect(() => {
    loadHistory(sldOrderSeq);
  }, [sldOrderSeq, loadHistory]);

  // Auto-scroll on new messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isLoading, activeToolName]);

  // Send message
  const handleSend = useCallback(async () => {
    const trimmed = inputValue.trim();
    if (!trimmed || isLoading || isStreaming) return;

    setInputValue('');
    await sendMessage(sldOrderSeq, trimmed);
  }, [inputValue, isLoading, isStreaming, sldOrderSeq, sendMessage]);

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
      onSldUpdated();
    } catch {
      // Error handling via toast in parent
    } finally {
      setAcceptLoading(false);
    }
  }, [sldOrderSeq, generatedFileId, onSldUpdated]);

  // Reset conversation
  const handleReset = useCallback(async () => {
    if (!confirm('Reset the conversation? All chat history will be cleared.')) return;
    await resetChat(sldOrderSeq);
  }, [sldOrderSeq, resetChat]);

  return (
    <div className="flex flex-col h-[600px] border border-gray-200 rounded-lg overflow-hidden bg-white">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 bg-gray-50 border-b border-gray-200">
        <div className="flex items-center gap-2">
          <span className="text-base">&#129302;</span>
          <span className="text-sm font-medium text-gray-700">SLD AI Generator</span>
          {(isLoading || isStreaming) && (
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
                </p>
              </div>
            )}

            {messages.map((msg) => (
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
                  {msg.content}
                </div>
              </div>
            ))}

            {/* Tool execution indicator */}
            {activeToolName && (
              <div className="flex justify-start">
                <div className="bg-yellow-50 border border-yellow-200 rounded-lg px-3 py-2 text-xs text-yellow-700 flex items-center gap-2">
                  <span className="animate-spin">&#9881;&#65039;</span>
                  {formatToolName(activeToolName)}
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
