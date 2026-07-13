import { useEffect } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Check, MessageCircle, ClipboardCheck, Send, BellRing } from 'lucide-react';
import { PUBLIC_SERVICES, whatsappServiceMessage } from '../constants/publicServices';
import { buildWhatsAppLink } from '../utils/whatsapp';
import { trackPageView, trackWhatsAppClick } from '../utils/track';
import { useWhatsAppNumber } from '../hooks/useWhatsAppNumber';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import PublicHeader from '../components/common/PublicHeader';
import PublicFooter from '../components/common/PublicFooter';
import FloatingWhatsAppButton from '../components/common/FloatingWhatsAppButton';
import WhatsAppIcon from '../components/common/WhatsAppIcon';

/**
 * Page 2b — 서비스별 개별 상세 페이지 (/services/:slug).
 * SEO: 서비스마다 전용 URL·제목·설명·JSON-LD(Service) 로 각 서비스 키워드를 겨냥한다.
 * 콘텐츠는 검증된 카피(publicServices) + 정직한 "How it works"(컨시어지 흐름). EMA 세부 규정은
 * 임의 서술하지 않는다(정확성·규제 오인 방지).
 */

// 우리 WhatsApp 컨시어지 실제 흐름 — EMA 세부 규정 서술 없이 사실만.
const HOW_IT_WORKS = [
  { icon: MessageCircle, title: 'Message us on WhatsApp', desc: 'Tell us what you need — a real person replies. No account or form.' },
  { icon: ClipboardCheck, title: 'We assess your case', desc: 'We work out exactly what applies to your situation and what’s required.' },
  { icon: Send, title: 'We prepare & submit', desc: 'We handle the paperwork and coordinate with your Licensed Electrical Worker (LEW) where needed.' },
  { icon: BellRing, title: 'We keep you updated', desc: 'We track it through to approval and keep you posted along the way.' },
];

export default function ServiceDetailPage() {
  const { slug } = useParams<{ slug: string }>();
  const whatsappNumber = useWhatsAppNumber();
  const service = PUBLIC_SERVICES.find((s) => s.slug === slug);

  useEffect(() => {
    window.scrollTo(0, 0);
    if (service) trackPageView();
  }, [service]);

  useDocumentMeta(
    service
      ? {
          title: `${service.label} in Singapore | LicenseKaki`,
          description: service.cardDesc,
          path: `/services/${service.slug}`,
        }
      : { title: 'Services | LicenseKaki', description: '', path: '/services' },
  );

  // 잘못된 slug → 허브로
  if (!service) return <Navigate to="/services" replace />;

  const Icon = service.icon;
  const whatsappHref = buildWhatsAppLink(whatsappNumber, whatsappServiceMessage(service.label));

  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'Service',
    name: service.label,
    serviceType: service.label,
    description: service.cardDesc,
    areaServed: { '@type': 'Country', name: 'Singapore' },
    provider: {
      '@type': 'ProfessionalService',
      name: 'LicenseKaki',
      url: 'https://licensekaki.com/',
    },
    url: `https://licensekaki.com/services/${service.slug}`,
  };

  const others = PUBLIC_SERVICES.filter((s) => s.slug !== service.slug).slice(0, 4);

  return (
    <div className="min-h-screen bg-canvas">
      <PublicHeader />
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />

      {/* ── Hero ── */}
      <section className="bg-white border-b border-gray-100">
        <div className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-10 sm:py-14">
          <Link to="/services" className="inline-flex items-center gap-1.5 text-sm text-gray-400 hover:text-primary transition-colors">
            <ArrowLeft className="w-4 h-4" /> All services
          </Link>
          <div className="mt-5 flex items-center gap-4">
            <span className="w-14 h-14 bg-primary/5 rounded-2xl flex items-center justify-center flex-shrink-0">
              <Icon className="w-7 h-7 text-primary" />
            </span>
            <h1 className="text-2xl sm:text-3xl font-bold text-gray-900">
              {service.label}{' '}
              <span className="text-gray-400 font-semibold">· Singapore</span>
            </h1>
          </div>
          <p className="mt-5 text-gray-600 leading-relaxed">{service.intro}</p>
        </div>
      </section>

      <main className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-10 sm:py-14 space-y-10">
        {/* ── What's covered ── */}
        {service.bullets.length > 0 && (
          <section className="bg-white rounded-2xl border border-primary-100 p-6 sm:p-8">
            <h2 className="text-lg font-bold text-gray-900">What we help with</h2>
            <ul className="mt-4 space-y-2.5">
              {service.bullets.map((bullet) => (
                <li key={bullet} className="flex items-start gap-2.5 text-sm text-gray-700">
                  <Check className="w-4 h-4 text-success-500 mt-0.5 flex-shrink-0" />
                  {bullet}
                </li>
              ))}
            </ul>
            {service.whoNeedsThis && (
              <div className="mt-6 rounded-xl bg-canvas px-4 py-3">
                <span className="text-[11px] font-semibold tracking-widest text-primary uppercase">Who needs this</span>
                <p className="mt-1 text-sm text-gray-600">{service.whoNeedsThis}</p>
              </div>
            )}
          </section>
        )}

        {/* ── How it works ── */}
        <section className="bg-white rounded-2xl border border-primary-100 p-6 sm:p-8">
          <h2 className="text-lg font-bold text-gray-900">How it works</h2>
          <ol className="mt-5 grid grid-cols-1 sm:grid-cols-2 gap-5">
            {HOW_IT_WORKS.map((step, i) => {
              const StepIcon = step.icon;
              return (
                <li key={step.title} className="flex gap-3">
                  <span className="flex-none w-9 h-9 rounded-lg bg-primary/5 flex items-center justify-center">
                    <StepIcon className="w-4 h-4 text-primary" />
                  </span>
                  <div>
                    <div className="flex items-baseline gap-2">
                      <span className="text-xs font-bold text-gray-300 tabular-nums">{String(i + 1).padStart(2, '0')}</span>
                      <h3 className="text-sm font-semibold text-gray-900">{step.title}</h3>
                    </div>
                    <p className="mt-1 text-sm text-gray-500 leading-relaxed">{step.desc}</p>
                  </div>
                </li>
              );
            })}
          </ol>
        </section>

        {/* ── CTA ── */}
        <section className="bg-primary rounded-2xl p-6 sm:p-8 text-center">
          <h2 className="text-xl font-bold text-white">Ready to start? Or just have a question?</h2>
          <p className="mt-2 text-sm text-primary-100">Message us on WhatsApp — a real person replies. No account or form needed.</p>
          <a
            href={whatsappHref}
            target="_blank"
            rel="noopener noreferrer"
            onClick={() => trackWhatsAppClick(service.slug)}
            className="mt-5 inline-flex items-center gap-2 rounded-lg bg-[#25D366] px-6 py-3 text-sm font-semibold text-white shadow-md hover:bg-[#1da851] hover:shadow-lg transition-all"
          >
            <WhatsAppIcon className="w-5 h-5" />
            Chat about {service.label}
          </a>
        </section>

        {/* ── Other services ── */}
        <section>
          <h2 className="text-sm font-semibold text-gray-500 mb-4">Explore other services</h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {others.map((o) => {
              const OIcon = o.icon;
              return (
                <Link
                  key={o.slug}
                  to={`/services/${o.slug}`}
                  className="group flex items-center gap-3 rounded-xl bg-white border border-primary-100 px-4 py-3 hover:border-primary/40 hover:shadow-sm transition-all"
                >
                  <span className="w-9 h-9 bg-primary/5 rounded-lg flex items-center justify-center flex-shrink-0">
                    <OIcon className="w-4 h-4 text-primary" />
                  </span>
                  <span className="text-sm font-medium text-gray-800 group-hover:text-primary transition-colors flex-1">{o.label}</span>
                  <ArrowRight className="w-4 h-4 text-gray-300 group-hover:text-primary transition-colors" />
                </Link>
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
