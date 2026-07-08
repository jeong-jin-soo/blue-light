import { useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, Lock, ShieldCheck, ScrollText } from 'lucide-react';
import { useAuthStore } from '../stores/authStore';
import {
  PUBLIC_SERVICES,
  WHATSAPP_GENERIC_MESSAGE,
} from '../constants/publicServices';
import { buildWhatsAppLink } from '../utils/whatsapp';
import { trackPageView, trackWhatsAppClick } from '../utils/track';
import { useWhatsAppNumber } from '../hooks/useWhatsAppNumber';
import PublicHeader from '../components/common/PublicHeader';
import PublicFooter from '../components/common/PublicFooter';
import FloatingWhatsAppButton from '../components/common/FloatingWhatsAppButton';
import WhatsAppIcon from '../components/common/WhatsAppIcon';

/* ------------------------------------------------------------------ */
/*  Data                                                               */
/* ------------------------------------------------------------------ */

// WhatsApp 퍼스트 랜딩 (미팅 문서 "Landing Page Content Draft v1"):
// 신청·가입 흐름 없이 서비스 소개 + WhatsApp 문의 채널만 노출한다.
// 뒷단 프로세스는 담당자가 내부 시스템에서 대행한다.

const trustItems = [
  { icon: Lock,        label: 'AES-256 Encryption', desc: 'Enterprise-grade file encryption at rest' },
  { icon: ShieldCheck, label: 'PDPA Compliant', desc: 'Singapore data protection compliance' },
  { icon: ScrollText,  label: 'Audit Trail', desc: 'Complete logging for transparency' },
];

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export default function LandingPage() {
  const navigate = useNavigate();
  const { isAuthenticated, user } = useAuthStore();
  const whatsappNumber = useWhatsAppNumber();

  // 공개 방문 기록 (1st-party)
  useEffect(() => {
    trackPageView();
  }, []);

  // 내부 사용자(담당자·LEW·관리자)는 로그인 상태면 대시보드로
  useEffect(() => {
    if (isAuthenticated && user) {
      const dest =
        user.role === 'SYSTEM_ADMIN'       ? '/admin/system'
        : user.role === 'ADMIN'            ? '/admin/dashboard'
        : user.role === 'LEW'              ? '/lew/dashboard'
        : user.role === 'SLD_MANAGER'      ? '/sld-manager/dashboard'
        : user.role === 'CONCIERGE_MANAGER' ? '/concierge-manager/dashboard'
        : '/dashboard';
      navigate(dest, { replace: true });
    }
  }, [isAuthenticated, user, navigate]);

  const whatsappHref = buildWhatsAppLink(whatsappNumber, WHATSAPP_GENERIC_MESSAGE);

  return (
    <div className="min-h-screen bg-white">
      <PublicHeader />

      {/* ── Hero — navy 단색 브랜드 모먼트 + 레드 슬래시 1점(§9-4) ── */}
      <section className="relative overflow-hidden bg-primary">
        {/* 시그니처: 로고의 레드 슬래시를 키운 단 하나의 그래픽 디테일 */}
        <div
          className="hidden lg:block absolute -top-[10%] right-[18%] h-[120%] w-1.5 bg-accent-500 rotate-[24deg] pointer-events-none"
          aria-hidden
        />
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-16 sm:py-24 lg:py-28 text-center">
          <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-white/10 text-primary-100 mb-6">
            Electrical Installation Licensing Service
          </span>
          <h1 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-white leading-tight">
            Electrical installation license,{' '}
            <span className="relative inline-block">
              Made simple
              {/* 레드 슬래시 언더라인 — 브랜드 모티프 */}
              <span className="absolute left-0 -bottom-1.5 h-1 w-full bg-accent-500 rounded-full -skew-x-12" aria-hidden />
            </span>
            .
          </h1>
          <p className="mt-6 text-sm sm:text-base text-primary-100 leading-relaxed max-w-xl mx-auto">
            Skip the confusing paperwork. LicenseKaki handles your electrical
            installation licensing from start to finish.
          </p>
          <div className="mt-8 flex justify-center">
            <a
              href={whatsappHref}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => trackWhatsAppClick()}
              className="inline-flex items-center gap-2.5 rounded-lg bg-[#25D366] px-7 py-3.5 text-base font-semibold text-white shadow-md hover:bg-[#1da851] hover:shadow-lg transition-all"
            >
              <WhatsAppIcon className="w-6 h-6" />
              Chat with Us on WhatsApp
            </a>
          </div>
          <p className="mt-4 text-xs text-primary-200">
            A real person replies — no account or form needed.
          </p>
        </div>
      </section>

      {/* ── Our 6 Services ── */}
      <section id="services" className="py-16 sm:py-24 bg-white">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-12">
            <span className="text-xs font-semibold tracking-widest text-primary uppercase">
              Our Services
            </span>
            <h2 className="mt-3 text-2xl sm:text-3xl font-bold text-gray-900">
              Our 7 Services
            </h2>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-5">
            {PUBLIC_SERVICES.map((service, i) => {
              const Icon = service.icon;
              return (
                <Link
                  key={service.slug}
                  to={`/services#${service.slug}`}
                  className="group flex flex-col p-5 sm:p-6 rounded-2xl bg-white border border-primary-100 hover:shadow-lg hover:border-primary/40 transition-all"
                >
                  <div className="flex items-center justify-between mb-4">
                    <span className="w-12 h-12 bg-primary/5 rounded-xl flex items-center justify-center group-hover:bg-primary/10 transition-colors">
                      <Icon className="w-6 h-6 text-primary" />
                    </span>
                    <span className="text-xs font-bold text-gray-300 tracking-widest">
                      {String(i + 1).padStart(2, '0')}
                    </span>
                  </div>
                  <h3 className="text-base font-semibold text-gray-900 group-hover:text-primary transition-colors">
                    {service.label}
                  </h3>
                  <p className="mt-2 text-sm text-gray-500 leading-relaxed flex-1">
                    {service.cardDesc}
                  </p>
                  <span className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-primary/80 group-hover:text-primary transition-colors">
                    Learn more <ArrowRight className="w-4 h-4" />
                  </span>
                </Link>
              );
            })}
          </div>

          {/* Secondary CTA (미팅 문서: "Not sure which service you need?") */}
          <div className="mt-12 text-center">
            <a
              href={whatsappHref}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => trackWhatsAppClick()}
              className="inline-flex items-center gap-2 text-sm font-medium text-primary hover:text-primary-900 transition-colors"
            >
              Not sure which service you need? Chat with our team
              <ArrowRight className="w-4 h-4" />
            </a>
          </div>
        </div>
      </section>

      {/* ── Trust & Security Banner ── */}
      <section className="py-14 sm:py-20 bg-canvas">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-8">
            {trustItems.map((t) => {
              const Icon = t.icon;
              return (
                <div key={t.label} className="text-center">
                  <div className="w-12 h-12 bg-primary/5 rounded-xl flex items-center justify-center mx-auto mb-3">
                    <Icon className="w-6 h-6 text-primary" />
                  </div>
                  <h4 className="text-sm font-semibold text-gray-800">{t.label}</h4>
                  <p className="mt-1 text-xs text-gray-500">{t.desc}</p>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      <PublicFooter />
      <FloatingWhatsAppButton />
    </div>
  );
}
