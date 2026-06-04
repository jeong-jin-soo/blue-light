import { useEffect, useRef, useState } from 'react';

/**
 * 이메일 본문(HTML) 비주얼 에디터.
 *
 * - 기본 Visual 모드: iframe(contenteditable body)으로 렌더된 이메일을 직접 편집 → 태그 미노출.
 *   이메일 본문이 인라인 스타일 포함 완전한 HTML 문서이므로, app CSS 와 격리되도록 iframe 사용.
 * - HTML source 토글: 원본 HTML 직접 편집(textarea).
 * - 의존성 추가 없음(execCommand 기반). 신뢰된 운영자 전용 화면.
 */
interface Props {
  value: string;
  onChange: (html: string) => void;
  variables?: string[];
}

const TOOLBAR_BTN =
  'px-2 py-1 text-xs border border-gray-300 rounded hover:bg-gray-100 cursor-pointer';

export default function HtmlBodyEditor({ value, onChange, variables = [] }: Props) {
  const [mode, setMode] = useState<'visual' | 'source'>('visual');
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  // 마지막으로 iframe 에 쓰거나 iframe 에서 읽어 emit 한 값 — 내부 편집의 echo 는 재작성하지 않기 위함.
  const lastValueRef = useRef<string | null>(null);

  // 외부 value 변경(본문 로드/소스편집/템플릿 전환) 시에만 iframe 재작성 → 내부 편집 커서 보존.
  useEffect(() => {
    // source 모드에서는 iframe 이 언마운트됨 → 재진입 시 항상 새로 쓰도록 ref 리셋.
    if (mode !== 'visual') {
      lastValueRef.current = null;
      return;
    }
    if (value === lastValueRef.current) return; // 내부 편집 echo → 무시
    const doc = iframeRef.current?.contentDocument;
    if (!doc) return;
    doc.open();
    doc.write(value || '<!DOCTYPE html><html><body></body></html>');
    doc.close();
    const body = doc.body;
    if (body) {
      body.contentEditable = 'true';
      body.style.outline = 'none';
      body.style.minHeight = '320px';
      body.style.margin = '0';
      body.style.padding = '12px';
    }
    lastValueRef.current = value;
    const handler = () => {
      const html = '<!DOCTYPE html>' + doc.documentElement.outerHTML;
      lastValueRef.current = html;
      onChangeRef.current(html);
    };
    body?.addEventListener('input', handler);
    return () => body?.removeEventListener('input', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, value]);

  const syncFromIframe = () => {
    const doc = iframeRef.current?.contentDocument;
    if (!doc) return;
    const html = '<!DOCTYPE html>' + doc.documentElement.outerHTML;
    lastValueRef.current = html;
    onChangeRef.current(html);
  };

  const exec = (command: string, val?: string) => {
    const win = iframeRef.current?.contentWindow;
    const doc = iframeRef.current?.contentDocument;
    if (!win || !doc) return;
    win.focus();
    doc.body?.focus();
    doc.execCommand(command, false, val);
    syncFromIframe();
  };

  const insertLink = () => {
    const url = window.prompt('Link URL (변수 사용 가능, 예: {{ctaUrl}}):');
    if (url) exec('createLink', url);
  };

  const insertVariable = (v: string) => {
    if (v) exec('insertText', `{{${v}}}`);
  };

  return (
    <div className="border border-gray-300 rounded">
      {/* 툴바 */}
      <div className="flex flex-wrap items-center gap-1 border-b border-gray-200 bg-gray-50 px-2 py-1.5">
        {mode === 'visual' && (
          <>
            {/* onMouseDown preventDefault: 버튼 클릭이 iframe 선택영역을 잃지 않도록 */}
            <button type="button" className={TOOLBAR_BTN} onMouseDown={(e) => e.preventDefault()} onClick={() => exec('bold')}><strong>B</strong></button>
            <button type="button" className={TOOLBAR_BTN} onMouseDown={(e) => e.preventDefault()} onClick={() => exec('italic')}><em>I</em></button>
            <button type="button" className={TOOLBAR_BTN} onMouseDown={(e) => e.preventDefault()} onClick={() => exec('underline')}><span className="underline">U</span></button>
            <span className="w-px h-4 bg-gray-300 mx-1" />
            <button type="button" className={TOOLBAR_BTN} onMouseDown={(e) => e.preventDefault()} onClick={() => exec('insertUnorderedList')}>• List</button>
            <button type="button" className={TOOLBAR_BTN} onMouseDown={(e) => e.preventDefault()} onClick={() => exec('insertOrderedList')}>1. List</button>
            <button type="button" className={TOOLBAR_BTN} onMouseDown={(e) => e.preventDefault()} onClick={insertLink}>🔗 Link</button>
            <button type="button" className={TOOLBAR_BTN} onMouseDown={(e) => e.preventDefault()} onClick={() => exec('removeFormat')}>Clear</button>
            {variables.length > 0 && (
              <select
                className="text-xs border border-gray-300 rounded px-1 py-1 ml-1"
                value=""
                onMouseDown={(e) => e.stopPropagation()}
                onChange={(e) => {
                  insertVariable(e.target.value);
                  e.target.value = '';
                }}
              >
                <option value="">Insert variable…</option>
                {variables.map((v) => (
                  <option key={v} value={v}>{`{{${v}}}`}</option>
                ))}
              </select>
            )}
          </>
        )}
        <button
          type="button"
          className={`${TOOLBAR_BTN} ml-auto`}
          onClick={() => setMode((m) => (m === 'visual' ? 'source' : 'visual'))}
        >
          {mode === 'visual' ? '</> HTML source' : '✏ Visual editor'}
        </button>
      </div>

      {/* 본문 영역 */}
      {mode === 'visual' ? (
        <iframe
          ref={iframeRef}
          title="email-body-editor"
          className="w-full h-80 bg-white rounded-b"
        />
      ) : (
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          rows={16}
          className="w-full px-3 py-2 text-sm font-mono rounded-b focus:outline-none"
        />
      )}
    </div>
  );
}
