import { useRef, useImperativeHandle, forwardRef, useCallback, useEffect, useState } from 'react';
import SignatureCanvas from 'react-signature-canvas';

export interface SignaturePadHandle {
  /** 서명 이미지를 PNG Blob으로 변환 */
  toBlob: () => Promise<Blob | null>;
  /** 서명 초기화 */
  clear: () => void;
  /** 서명 여부 확인 */
  isEmpty: () => boolean;
}

interface SignaturePadProps {
  /** 서명 변경 시 콜백 (서명 유무) */
  onSignatureChange?: (hasSignature: boolean) => void;
  /** 펜 색상 (기본: #1e293b) */
  penColor?: string;
  /** 비활성화 여부 */
  disabled?: boolean;
  /** 추가 className */
  className?: string;
}

/**
 * 전자서명 캔버스 컴포넌트
 * - react-signature-canvas 래퍼
 * - toBlob(), clear(), isEmpty() 메서드 노출
 */
const SignaturePad = forwardRef<SignaturePadHandle, SignaturePadProps>(
  ({ onSignatureChange, penColor = '#1e293b', disabled = false, className = '' }, ref) => {
    const canvasRef = useRef<SignatureCanvas | null>(null);
    const containerRef = useRef<HTMLDivElement>(null);
    const [hasSignature, setHasSignature] = useState(false);

    useImperativeHandle(ref, () => ({
      toBlob: async () => {
        if (!canvasRef.current || canvasRef.current.isEmpty()) return null;
        const dataUrl = canvasRef.current.toDataURL('image/png');
        const res = await fetch(dataUrl);
        return res.blob();
      },
      clear: () => {
        canvasRef.current?.clear();
        setHasSignature(false);
        onSignatureChange?.(false);
      },
      isEmpty: () => canvasRef.current?.isEmpty() ?? true,
    }));

    const handleEnd = useCallback(() => {
      const empty = canvasRef.current?.isEmpty() ?? true;
      setHasSignature(!empty);
      onSignatureChange?.(!empty);
    }, [onSignatureChange]);

    // 컨테이너 리사이즈 시 캔버스 크기 맞추기
    useEffect(() => {
      const resizeCanvas = () => {
        if (containerRef.current && canvasRef.current) {
          const canvas = canvasRef.current.getCanvas();
          const container = containerRef.current;
          canvas.width = container.offsetWidth;
          canvas.height = container.offsetHeight;
        }
      };

      resizeCanvas();
      window.addEventListener('resize', resizeCanvas);
      return () => window.removeEventListener('resize', resizeCanvas);
    }, []);

    return (
      <div className={`relative ${className}`}>
        <div
          ref={containerRef}
          className={`relative border-2 border-dashed rounded-lg overflow-hidden ${
            disabled ? 'border-gray-200 bg-gray-50' : 'border-gray-300 bg-white'
          }`}
          style={{ height: '150px' }}
        >
          {/* 플레이스홀더 */}
          {!hasSignature && !disabled && (
            <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
              <span className="text-gray-300 text-sm select-none">Sign here</span>
            </div>
          )}

          <SignatureCanvas
            ref={canvasRef}
            penColor={penColor}
            canvasProps={{
              className: 'w-full h-full',
              style: { touchAction: 'none' },
            }}
            onEnd={handleEnd}
          />

          {disabled && (
            <div className="absolute inset-0 bg-gray-100/50 cursor-not-allowed" />
          )}
        </div>

        {/* Clear 버튼 */}
        {!disabled && hasSignature && (
          <button
            type="button"
            onClick={() => {
              canvasRef.current?.clear();
              setHasSignature(false);
              onSignatureChange?.(false);
            }}
            className="absolute top-2 right-2 text-xs text-gray-400 hover:text-gray-600 bg-white/80 px-2 py-1 rounded"
          >
            Clear
          </button>
        )}
      </div>
    );
  }
);

SignaturePad.displayName = 'SignaturePad';

export default SignaturePad;
