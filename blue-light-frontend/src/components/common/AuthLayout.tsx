import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import licensekakiLogo from '../../assets/licensekaki-logo.png';

interface AuthLayoutProps {
  children: ReactNode;
}

/**
 * Shared layout for Login/Signup pages — "Split brand panel" (§9-4).
 * 좌측 navy 브랜드 패널(로고 + 가치문구 + 레드 슬래시 1점) + 우측 폼.
 * 모바일에서는 패널이 상단 navy 밴드로 축소된다.
 */
export default function AuthLayout({ children }: AuthLayoutProps) {
  return (
    <div className="min-h-screen flex flex-col lg:flex-row bg-canvas">
      {/* ── 좌측 브랜드 패널 (모바일: 상단 밴드) ── */}
      <div className="relative overflow-hidden bg-primary text-white lg:w-[44%] lg:min-h-screen flex flex-col justify-between px-6 py-8 lg:p-12">
        {/* 시그니처: 로고의 레드 슬래시를 키운 단 하나의 그래픽 디테일 */}
        <div
          className="hidden lg:block absolute -top-[10%] right-[18%] h-[120%] w-1.5 bg-accent-500 rotate-[24deg] pointer-events-none"
          aria-hidden
        />

        <Link to="/" className="group relative inline-block">
          <img
            src={licensekakiLogo}
            alt="LicenseKaki"
            className="h-7 lg:h-8 brightness-0 invert group-hover:opacity-80 transition-opacity"
          />
        </Link>

        <div className="hidden lg:block relative">
          <p className="text-3xl font-bold leading-snug max-w-sm">
            Singapore&rsquo;s electrical licensing,{' '}
            <span className="relative inline-block">
              handled.
              <span className="absolute left-0 -bottom-1 h-1 w-full bg-accent-500 rounded-full -skew-x-12" aria-hidden />
            </span>
          </p>
          <p className="mt-4 text-sm text-primary-200 max-w-sm leading-relaxed">
            Apply, track, and manage EMA electrical installation licences — all in one place.
          </p>
        </div>

        {/* 모바일 상단 밴드용 한 줄 문구 */}
        <p className="lg:hidden mt-3 text-sm text-primary-200">
          Singapore&rsquo;s electrical licensing, handled.
        </p>

        <p className="hidden lg:block relative text-xs text-primary-200/70">
          &copy; {new Date().getFullYear()} LicenseKaki by HanVision
        </p>
      </div>

      {/* ── 우측 폼 영역 ── */}
      <div className="flex-1 flex items-center justify-center px-4 py-10 lg:py-8">
        <div className="w-full max-w-md">
          <div className="bg-surface rounded-2xl shadow-auth p-8">
            {children}
          </div>

          {/* Footer links */}
          <div className="text-center mt-6 text-xs text-gray-400 space-x-3">
            <Link to="/disclaimer" className="hover:text-gray-600 transition-colors">Disclaimer</Link>
            <span>·</span>
            <Link to="/privacy" className="hover:text-gray-600 transition-colors">Privacy Policy</Link>
          </div>
        </div>
      </div>
    </div>
  );
}
