import { Link } from 'react-router-dom';
import licensekakiLogo from '../../assets/licensekaki-logo.png';

/** 공개 페이지(랜딩·서비스 상세) 공용 푸터 */
export default function PublicFooter() {
  return (
    <footer className="bg-gray-50 border-t border-gray-200 py-8">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
          <div className="flex items-end gap-1.5">
            <img src={licensekakiLogo} alt="LicenseKaki" className="h-5" />
            <span className="text-xs text-gray-400 leading-none">by HanVision</span>
          </div>
          <div className="flex items-center gap-4 text-xs text-gray-400">
            <Link to="/disclaimer" className="hover:text-gray-600 transition-colors">Disclaimer</Link>
            <span>·</span>
            <Link to="/privacy" className="hover:text-gray-600 transition-colors">Privacy Policy</Link>
          </div>
          <span className="text-xs text-gray-400">
            &copy; {new Date().getFullYear()} LicenseKaki by HanVision. All rights reserved.
          </span>
        </div>
      </div>
    </footer>
  );
}
