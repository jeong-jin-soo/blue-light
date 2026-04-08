import { useRef, useState, useCallback, useEffect } from 'react';

interface Props {
  svg: string;
  className?: string;
  onDownloadPdf?: () => void;
  onDownloadDxf?: () => void;
}

/**
 * SVG 미리보기 뷰어 — 확대/축소/이동 지원
 * - react-svg-pan-zoom 없이 네이티브 구현 (의존성 최소화)
 * - 마우스 휠: 확대/축소
 * - 드래그: 이동
 * - 더블 클릭: 리셋
 *
 * SVG는 ezdxf에서 생성되며 width/height가 mm 단위 + 큰 viewBox를 사용한다.
 * 컨테이너에 맞게 표시하기 위해 width/height를 100%로 정규화한다.
 */
export function SvgPreviewViewer({ svg, className = '', onDownloadPdf, onDownloadDxf }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);
  const [translate, setTranslate] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });

  // 확대/축소 (마우스 휠)
  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    const delta = e.deltaY > 0 ? 0.9 : 1.1;
    setScale((s) => Math.min(Math.max(s * delta, 0.1), 10));
  }, []);

  // 드래그 시작
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (e.button !== 0) return;
    setIsDragging(true);
    setDragStart({ x: e.clientX - translate.x, y: e.clientY - translate.y });
  }, [translate]);

  // 드래그 중
  const handleMouseMove = useCallback(
    (e: React.MouseEvent) => {
      if (!isDragging) return;
      setTranslate({
        x: e.clientX - dragStart.x,
        y: e.clientY - dragStart.y,
      });
    },
    [isDragging, dragStart],
  );

  // 드래그 끝
  const handleMouseUp = useCallback(() => {
    setIsDragging(false);
  }, []);

  // 더블 클릭 리셋
  const handleDoubleClick = useCallback(() => {
    setScale(1);
    setTranslate({ x: 0, y: 0 });
  }, []);

  // Fit to container on SVG change
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setScale(1);
    setTranslate({ x: 0, y: 0 });
  }, [svg]);

  if (!svg) {
    return (
      <div className={`flex items-center justify-center bg-gray-50 border border-gray-200 rounded-lg ${className}`}>
        <div className="text-center text-gray-400">
          <span className="text-3xl block mb-2">📐</span>
          <p className="text-sm">SLD preview will appear here</p>
        </div>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className={`relative overflow-hidden bg-gray-900 border border-gray-200 rounded-lg cursor-grab active:cursor-grabbing ${className}`}
      onWheel={handleWheel}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
      onDoubleClick={handleDoubleClick}
    >
      {/* Zoom controls */}
      <div className="absolute top-2 right-2 z-10 flex flex-col gap-1">
        <button
          onClick={() => setScale((s) => Math.min(s * 1.2, 10))}
          className="w-7 h-7 bg-white border border-gray-300 rounded text-sm font-bold text-gray-600 hover:bg-gray-50 flex items-center justify-center"
          title="Zoom in"
        >
          +
        </button>
        <button
          onClick={() => setScale((s) => Math.max(s * 0.8, 0.1))}
          className="w-7 h-7 bg-white border border-gray-300 rounded text-sm font-bold text-gray-600 hover:bg-gray-50 flex items-center justify-center"
          title="Zoom out"
        >
          −
        </button>
        <button
          onClick={handleDoubleClick}
          className="w-7 h-7 bg-white border border-gray-300 rounded text-xs text-gray-600 hover:bg-gray-50 flex items-center justify-center"
          title="Reset view"
        >
          ⟲
        </button>
      </div>

      {/* Download buttons */}
      {(onDownloadPdf || onDownloadDxf) && (
        <div className="absolute top-2 left-2 z-10 flex flex-col gap-1">
          {onDownloadPdf && (
            <button
              onClick={onDownloadPdf}
              className="h-7 px-2 bg-white border border-gray-300 rounded text-xs font-medium text-gray-600 hover:bg-gray-50 flex items-center gap-1"
              title="Download PDF"
            >
              <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              PDF
            </button>
          )}
          {onDownloadDxf && (
            <button
              onClick={onDownloadDxf}
              className="h-7 px-2 bg-white border border-gray-300 rounded text-xs font-medium text-gray-600 hover:bg-gray-50 flex items-center gap-1"
              title="Download DXF"
            >
              <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              DXF
            </button>
          )}
        </div>
      )}

      {/* Scale indicator */}
      <div className="absolute bottom-2 left-2 z-10 text-xs text-gray-400 bg-black/50 px-1.5 py-0.5 rounded">
        {Math.round(scale * 100)}%
      </div>

      {/* SVG content */}
      <div
        style={{
          transform: `translate(${translate.x}px, ${translate.y}px) scale(${scale})`,
          transformOrigin: 'center center',
          transition: isDragging ? 'none' : 'transform 0.1s ease-out',
        }}
        className="w-full h-full flex items-center justify-center p-2 [&>svg]:max-w-full [&>svg]:max-h-full [&>svg]:w-auto [&>svg]:h-auto"
        dangerouslySetInnerHTML={{ __html: normalizeSvg(svg) }}
      />
    </div>
  );
}

/**
 * ezdxf가 생성한 SVG를 브라우저 표시에 맞게 정규화
 * - width/height의 mm 단위를 제거하고 viewBox 기반 반응형으로 변환
 * - 이를 통해 SVG가 컨테이너 크기에 자동으로 맞춰짐
 */
function normalizeSvg(svg: string): string {
  // width="400mm" height="277mm" → width="100%" height="100%" preserveAspectRatio="xMidYMid meet"
  return svg.replace(
    /<svg([^>]*)\s+width="[^"]*"\s+height="[^"]*"/,
    '<svg$1 width="100%" height="100%" preserveAspectRatio="xMidYMid meet"',
  );
}
