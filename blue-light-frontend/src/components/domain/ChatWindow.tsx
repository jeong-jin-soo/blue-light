import { useState, useRef, useEffect } from 'react';

import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { useChatStore } from '../../stores/chatStore';
import type { ChatMessage } from '../../types';

/**
 * 채팅 윈도우 — 메시지 목록, 입력, 추천 질문
 * AI 동의 화면 포함 (PDPA 준수)
 */
export default function ChatWindow() {
  const {
    messages, isLoading, isStreaming, suggestedQuestions,
    sendMessage, closeChat, clearMessages,
    aiConsented, acceptAiConsent, declineAiConsent,
  } = useChatStore();
  const [input, setInput] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 새 메시지 / 스트리밍 시 자동 스크롤
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isLoading, isStreaming]);

  // 열릴 때 포커스 (동의 완료 후)
  useEffect(() => {
    if (aiConsented) {
      inputRef.current?.focus();
    }
  }, [aiConsented]);

  const isBusy = isLoading || isStreaming;

  const handleSend = () => {
    const trimmed = input.trim();
    if (!trimmed || isBusy) return;
    sendMessage(trimmed);
    setInput('');
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleSuggestionClick = (question: string) => {
    if (isBusy) return;
    sendMessage(question);
  };

  return (
    <div
      className="fixed bottom-32 right-6 z-50 flex flex-col
                 w-[calc(100vw-2rem)] max-w-96 h-[520px]
                 md:w-96
                 bg-white rounded-2xl shadow-modal border border-gray-200
                 animate-in"
    >
      {/* 헤더 */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100 bg-primary rounded-t-2xl">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-full bg-white/20 flex items-center justify-center">
            <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09z" />
            </svg>
          </div>
          <div>
            <h3 className="text-sm font-semibold text-white">LicenseKaki Assistant</h3>
            <p className="text-xs text-white/70">AI-powered help</p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          {messages.length > 0 && (
            <button
              onClick={clearMessages}
              className="p-1.5 text-white/70 hover:text-white rounded-lg hover:bg-white/10 transition-colors"
              title="Clear chat"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M14.74 9l-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 01-2.244 2.077H8.084a2.25 2.25 0 01-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 00-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 013.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 00-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 00-7.5 0" />
              </svg>
            </button>
          )}
          <button
            onClick={closeChat}
            className="p-1.5 text-white/70 hover:text-white rounded-lg hover:bg-white/10 transition-colors"
            title="Minimize"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 12h-15" />
            </svg>
          </button>
        </div>
      </div>

      {/* AI 동의 화면 (PDPA) */}
      {!aiConsented ? (
        <div className="flex-1 overflow-y-auto p-4 flex flex-col justify-center">
          <div className="space-y-4">
            <div className="w-12 h-12 mx-auto rounded-full bg-amber-100 flex items-center justify-center">
              <svg className="w-6 h-6 text-amber-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
              </svg>
            </div>
            <div className="text-center">
              <h4 className="text-sm font-semibold text-gray-800 mb-2">AI Data Processing Notice</h4>
              <p className="text-xs text-gray-600 leading-relaxed">
                This chatbot is powered by <strong>Google Gemini AI</strong>.
                Your messages will be sent to Google's servers for processing
                and may be transferred overseas.
              </p>
              <p className="text-xs text-gray-600 leading-relaxed mt-2">
                We do not send your personal account data (email, phone, address).
                Only the messages you type are transmitted.
              </p>
              <p className="text-xs text-gray-500 mt-2">
                See our{' '}
                <a href="/privacy-policy" className="text-primary underline" target="_blank" rel="noopener noreferrer">
                  Privacy Policy
                </a>{' '}
                (Section 3) for details.
              </p>
            </div>
            <div className="flex gap-2 justify-center pt-2">
              <button
                onClick={declineAiConsent}
                className="px-4 py-2 text-xs font-medium text-gray-600 bg-gray-100 hover:bg-gray-200
                           rounded-lg transition-colors cursor-pointer"
              >
                Decline
              </button>
              <button
                onClick={acceptAiConsent}
                className="px-4 py-2 text-xs font-medium text-white bg-primary hover:bg-primary-hover
                           rounded-lg transition-colors cursor-pointer"
              >
                I Agree &amp; Continue
              </button>
            </div>
          </div>
        </div>
      ) : (
        <>
          {/* 메시지 영역 */}
          <div className="flex-1 overflow-y-auto p-4 space-y-3">
            {/* 웰컴 메시지 */}
            {messages.length === 0 && (
              <div className="flex gap-2">
                <div className="w-7 h-7 rounded-full bg-primary-100 flex items-center justify-center flex-shrink-0 mt-0.5">
                  <svg className="w-3.5 h-3.5 text-primary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09z" />
                  </svg>
                </div>
                <div className="bg-gray-50 rounded-2xl rounded-tl-sm px-3.5 py-2.5 max-w-[85%]">
                  <p className="text-sm text-gray-700 leading-relaxed">
                    Hi! I'm the LicenseKaki assistant. I can help you with questions about Singapore
                    electrical installation licences, application procedures, and using this platform.
                  </p>
                  <p className="text-sm text-gray-700 mt-2">How can I help you today?</p>
                </div>
              </div>
            )}

            {/* 대화 메시지 */}
            {messages.map((msg, idx) => (
              <MessageBubble
                key={msg.id}
                message={msg}
                isStreamingCurrent={
                  isStreaming && msg.role === 'assistant' && idx === messages.length - 1
                }
              />
            ))}

            {/* 타이핑 인디케이터 (스트리밍 시작 전에만 표시) */}
            {isLoading && !isStreaming && (
              <div className="flex gap-2">
                <div className="w-7 h-7 rounded-full bg-primary-100 flex items-center justify-center flex-shrink-0 mt-0.5">
                  <svg className="w-3.5 h-3.5 text-primary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09z" />
                  </svg>
                </div>
                <div className="bg-gray-50 rounded-2xl rounded-tl-sm px-4 py-3">
                  <div className="flex gap-1">
                    <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                    <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                    <span className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                  </div>
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          {/* 추천 질문 */}
          {suggestedQuestions.length > 0 && !isBusy && (
            <div className="px-4 pb-2 flex flex-wrap gap-1.5">
              {suggestedQuestions.map((q) => (
                <button
                  key={q}
                  onClick={() => handleSuggestionClick(q)}
                  className="text-xs px-2.5 py-1.5 bg-primary-50 text-primary-700 rounded-full
                             hover:bg-primary-100 transition-colors whitespace-nowrap cursor-pointer"
                >
                  {q}
                </button>
              ))}
            </div>
          )}

          {/* 입력 영역 */}
          <div className="border-t border-gray-100 p-3">
            <div className="flex items-end gap-2">
              <textarea
                ref={inputRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Type your question..."
                rows={1}
                className="flex-1 resize-none rounded-xl border border-gray-200 px-3.5 py-2.5 text-sm
                           focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary
                           placeholder:text-gray-400 max-h-24"
                disabled={isBusy}
              />
              <button
                onClick={handleSend}
                disabled={!input.trim() || isBusy}
                className="p-2.5 rounded-xl bg-primary text-white hover:bg-primary-hover
                           disabled:opacity-40 disabled:cursor-not-allowed transition-colors
                           flex-shrink-0 cursor-pointer"
                aria-label="Send message"
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
                </svg>
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/** 메시지 버블 컴포넌트 */
function MessageBubble({
  message,
  isStreamingCurrent = false,
}: {
  message: ChatMessage;
  isStreamingCurrent?: boolean;
}) {
  const isUser = message.role === 'user';

  return (
    <div className={`flex gap-2 ${isUser ? 'flex-row-reverse' : ''}`}>
      {!isUser && (
        <div className="w-7 h-7 rounded-full bg-primary-100 flex items-center justify-center flex-shrink-0 mt-0.5">
          <svg className="w-3.5 h-3.5 text-primary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09z" />
          </svg>
        </div>
      )}
      <div
        className={`rounded-2xl px-3.5 py-2.5 max-w-[85%] ${
          isUser
            ? 'bg-primary text-white rounded-tr-sm'
            : 'bg-gray-50 text-gray-700 rounded-tl-sm'
        }`}
      >
        {isUser ? (
          <p className="text-sm leading-relaxed whitespace-pre-wrap">{message.content}</p>
        ) : (
          <div className="chat-markdown text-sm leading-relaxed">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                a: ({ href, children }) => (
                  <a href={href} target="_blank" rel="noopener noreferrer">
                    {children}
                  </a>
                ),
              }}
            >
              {message.content}
            </ReactMarkdown>
            {isStreamingCurrent && (
              <span className="inline-block w-1.5 h-4 bg-gray-400 ml-0.5 animate-pulse align-text-bottom" />
            )}
          </div>
        )}
      </div>
    </div>
  );
}
