import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { Check } from 'lucide-react';
import {
  PUBLIC_SERVICES,
  whatsappServiceMessage,
} from '../constants/publicServices';
import { buildWhatsAppLink } from '../utils/whatsapp';
import { useWhatsAppNumber } from '../hooks/useWhatsAppNumber';
import PublicHeader from '../components/common/PublicHeader';
import PublicFooter from '../components/common/PublicFooter';
import FloatingWhatsAppButton from '../components/common/FloatingWhatsAppButton';
import WhatsAppIcon from '../components/common/WhatsAppIcon';

/**
 * Page 2 — Service Details (미팅 문서 "Landing Page Content Draft v1").
 * 랜딩 카드가 /services#<slug> 앵커로 연결되며, 각 섹션은 쉬운 설명 +
 * 해당 서비스명이 프리필된 WhatsApp 문의 버튼을 제공한다.
 * 담당자가 WhatsApp 대화 중 특정 섹션 링크를 신청자에게 보낼 수 있다.
 */
export default function ServicesPage() {
  const { hash } = useLocation();
  const whatsappNumber = useWhatsAppNumber();

  // React Router 는 해시 스크롤을 처리하지 않으므로 직접 이동한다.
  // 즉시 1회(클라이언트 사이드 내비게이션) + 지연 1회(전체 리로드 시
  // 브라우저 초기 스크롤 복원과의 경합 보정) 실행한다.
  useEffect(() => {
    if (!hash) {
      window.scrollTo(0, 0);
      return;
    }
    const scrollToSection = () =>
      document.getElementById(hash.slice(1))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    scrollToSection();
    const timer = window.setTimeout(scrollToSection, 100);
    return () => window.clearTimeout(timer);
  }, [hash]);

  return (
    <div className="min-h-screen bg-canvas">
      <PublicHeader />

      {/* ── Intro ── */}
      <section className="bg-white border-b border-gray-100">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 text-center">
          <span className="text-xs font-semibold tracking-widest text-primary uppercase">
            Service Details
          </span>
          <h1 className="mt-3 text-2xl sm:text-3xl font-bold text-gray-900">
            Not sure where to start?
          </h1>
          <p className="mt-3 text-gray-500">
            Here's what each service covers.
          </p>
        </div>
      </section>

      {/* ── Service sections ── */}
      <main className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 space-y-8">
        {PUBLIC_SERVICES.map((service, i) => {
          const Icon = service.icon;
          return (
            <section
              key={service.slug}
              id={service.slug}
              // sticky 헤더(h-16)에 가리지 않도록 앵커 여백 확보
              className="scroll-mt-24 bg-white rounded-2xl border border-primary-100 p-6 sm:p-8"
            >
              <div className="flex items-start gap-4">
                <span className="hidden sm:flex w-12 h-12 bg-primary/5 rounded-xl items-center justify-center flex-shrink-0">
                  <Icon className="w-6 h-6 text-primary" />
                </span>
                <div className="min-w-0">
                  <div className="flex items-baseline gap-2">
                    <span className="text-xs font-bold text-gray-300 tracking-widest">
                      {String(i + 1).padStart(2, '0')}
                    </span>
                    <h2 className="text-lg sm:text-xl font-bold text-gray-900">
                      {service.label}
                    </h2>
                  </div>
                  <p className="mt-3 text-sm text-gray-600 leading-relaxed">
                    {service.intro}
                  </p>
                  {service.bullets.length > 0 && (
                    <ul className="mt-4 space-y-2">
                      {service.bullets.map((bullet) => (
                        <li key={bullet} className="flex items-start gap-2 text-sm text-gray-700">
                          <Check className="w-4 h-4 text-success-500 mt-0.5 flex-shrink-0" />
                          {bullet}
                        </li>
                      ))}
                    </ul>
                  )}
                  {service.whoNeedsThis && (
                    <div className="mt-5 rounded-xl bg-canvas px-4 py-3">
                      <span className="text-[11px] font-semibold tracking-widest text-primary uppercase">
                        Who needs this
                      </span>
                      <p className="mt-1 text-sm text-gray-600">{service.whoNeedsThis}</p>
                    </div>
                  )}
                  <a
                    href={buildWhatsAppLink(whatsappNumber, whatsappServiceMessage(service.label))}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="mt-5 inline-flex items-center gap-2 rounded-lg bg-[#25D366] px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-[#1da851] hover:shadow-md transition-all"
                  >
                    <WhatsAppIcon className="w-5 h-5" />
                    Chat about {service.label}
                  </a>
                </div>
              </div>
            </section>
          );
        })}
      </main>

      <PublicFooter />
      <FloatingWhatsAppButton />
    </div>
  );
}
