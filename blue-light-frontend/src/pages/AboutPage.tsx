import { useEffect } from 'react';
import { ArrowRight, Lock, ShieldCheck, ScrollText } from 'lucide-react';
import { WHATSAPP_GENERIC_MESSAGE } from '../constants/publicServices';
import { buildWhatsAppLink } from '../utils/whatsapp';
import { useWhatsAppNumber } from '../hooks/useWhatsAppNumber';
import { Link } from 'react-router-dom';
import PublicHeader from '../components/common/PublicHeader';
import PublicFooter from '../components/common/PublicFooter';
import FloatingWhatsAppButton from '../components/common/FloatingWhatsAppButton';
import WhatsAppIcon from '../components/common/WhatsAppIcon';

/**
 * About Us — 공개 페이지. 헤더의 "About Us" 링크(구 Sign In)가 여기로 연결된다.
 * 신청·가입 흐름 없이 회사 소개 + WhatsApp 문의 채널만 노출한다(WhatsApp 퍼스트).
 * 법인 상세(UEN·등록주소 등)는 "설정 우선" 원칙 대상이라 마케팅 카피에 하드코딩하지 않는다.
 */

const trustItems = [
  { icon: Lock,        label: 'AES-256 Encryption', desc: 'Your documents are encrypted at rest with enterprise-grade security.' },
  { icon: ShieldCheck, label: 'PDPA Compliant',     desc: 'We handle your data in line with Singapore’s data protection rules.' },
  { icon: ScrollText,  label: 'Audit Trail',        desc: 'Every action is logged, so nothing about your application is a mystery.' },
];

export default function AboutPage() {
  const whatsappNumber = useWhatsAppNumber();

  useEffect(() => {
    window.scrollTo(0, 0);
  }, []);

  const whatsappHref = buildWhatsAppLink(whatsappNumber, WHATSAPP_GENERIC_MESSAGE);

  return (
    <div className="min-h-screen bg-canvas">
      <PublicHeader />

      {/* ── Intro ── */}
      <section className="bg-white border-b border-gray-100">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 text-center">
          <span className="text-xs font-semibold tracking-widest text-primary uppercase">
            About Us
          </span>
          <h1 className="mt-3 text-2xl sm:text-3xl font-bold text-gray-900">
            Electrical licensing, without the headache
          </h1>
          <p className="mt-4 text-gray-600 leading-relaxed">
            LicenseKaki is a licensing service by HanVision that helps businesses
            in Singapore get their EMA electrical installation licenses — new
            applications, renewals, single-line diagrams and more — from start to
            finish, so you don’t have to wrestle with the paperwork yourself.
          </p>
        </div>
      </section>

      <main className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 space-y-10">
        {/* ── What we do ── */}
        <section className="bg-white rounded-2xl border border-primary-100 p-6 sm:p-8">
          <h2 className="text-lg sm:text-xl font-bold text-gray-900">What we do</h2>
          <p className="mt-3 text-sm text-gray-600 leading-relaxed">
            EMA licensing involves forms, technical documents, and coordination
            with a Licensed Electrical Worker (LEW). We take that whole process off
            your plate: we work out which license you need, prepare and submit your
            application, coordinate with your LEW, and keep track of it until it’s
            approved. You stay informed; we do the legwork.
          </p>
          <Link
            to="/services"
            className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-primary hover:text-primary-900 transition-colors"
          >
            See our services <ArrowRight className="w-4 h-4" />
          </Link>
        </section>

        {/* ── How it works (WhatsApp-first) ── */}
        <section className="bg-white rounded-2xl border border-primary-100 p-6 sm:p-8">
          <h2 className="text-lg sm:text-xl font-bold text-gray-900">
            A conversation, not a form
          </h2>
          <p className="mt-3 text-sm text-gray-600 leading-relaxed">
            There’s no account to create and no long form to fill in. You message us
            on WhatsApp, a real person replies, and we guide you through everything
            from there. It’s the simplest way to get started — ask a question, share
            your details when you’re ready, and we handle the rest.
          </p>
          <a
            href={whatsappHref}
            target="_blank"
            rel="noopener noreferrer"
            className="mt-5 inline-flex items-center gap-2 rounded-lg bg-[#25D366] px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-[#1da851] hover:shadow-md transition-all"
          >
            <WhatsAppIcon className="w-5 h-5" />
            Chat with Us on WhatsApp
          </a>
        </section>

        {/* ── Why LicenseKaki (trust) ── */}
        <section className="bg-white rounded-2xl border border-primary-100 p-6 sm:p-8">
          <h2 className="text-lg sm:text-xl font-bold text-gray-900">Why LicenseKaki</h2>
          <div className="mt-6 grid grid-cols-1 sm:grid-cols-3 gap-8">
            {trustItems.map((t) => {
              const Icon = t.icon;
              return (
                <div key={t.label} className="text-center sm:text-left">
                  <div className="w-12 h-12 bg-primary/5 rounded-xl flex items-center justify-center mx-auto sm:mx-0 mb-3">
                    <Icon className="w-6 h-6 text-primary" />
                  </div>
                  <h3 className="text-sm font-semibold text-gray-800">{t.label}</h3>
                  <p className="mt-1 text-xs text-gray-500 leading-relaxed">{t.desc}</p>
                </div>
              );
            })}
          </div>
        </section>
      </main>

      <PublicFooter />
      <FloatingWhatsAppButton />
    </div>
  );
}
