import { Link } from 'react-router-dom';
import { Button } from '../ui/Button';
import licensekakiLogo from '../../assets/licensekaki-logo.png';

/**
 * 공개 페이지(랜딩·서비스 상세·About) 공용 헤더.
 * 신청자는 WhatsApp 으로 문의하므로 가입/로그인 CTA 없이 About Us 만 노출한다.
 * (내부 사용자는 /login 으로 직접 접속)
 */
export default function PublicHeader() {
  return (
    <header className="sticky top-0 z-50 bg-white/95 backdrop-blur-sm border-b border-gray-100">
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <Link to="/" className="flex items-center">
          <img src={licensekakiLogo} alt="LicenseKaki" className="h-6" />
        </Link>
        <Link to="/about">
          <Button variant="ghost" size="sm">About Us</Button>
        </Link>
      </div>
    </header>
  );
}
