import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { PUBLIC_SERVICES, WHATSAPP_GENERIC_MESSAGE } from '../constants/publicServices';
import { buildWhatsAppLink } from '../utils/whatsapp';
import { trackPageView, trackWhatsAppClick } from '../utils/track';
import { useWhatsAppNumber } from '../hooks/useWhatsAppNumber';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { SEO_META } from '../constants/seoMeta';
import PublicHeader from '../components/common/PublicHeader';
import PublicFooter from '../components/common/PublicFooter';
import FloatingWhatsAppButton from '../components/common/FloatingWhatsAppButton';

/**
 * Page 2 — Services 허브. 각 서비스는 전용 상세 페이지(/services/:slug)로 연결된다.
 * 전체 본문(설명·불릿·CTA)은 상세 페이지에만 두어 중복 콘텐츠를 피한다(SEO).
 */
export default function ServicesPage() {
  const whatsappNumber = useWhatsAppNumber();

  useDocumentMeta(SEO_META.services);

  useEffect(() => {
    window.scrollTo(0, 0);
    trackPageView();
  }, []);

  const whatsappHref = buildWhatsAppLink(whatsappNumber, WHATSAPP_GENERIC_MESSAGE);

  return (
    <div className="min-h-screen bg-canvas">
      <PublicHeader />

      {/* ── Intro ── */}
      <section className="bg-white border-b border-gray-100">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 text-center">
          <span className="text-xs font-semibold tracking-widest text-primary uppercase">Our Services</span>
          <h1 className="mt-3 text-2xl sm:text-3xl font-bold text-gray-900">
            Electrical licensing services in Singapore
          </h1>
          <p className="mt-3 text-gray-500">
            Pick the service that fits — each has its own page with the details.
          </p>
        </div>
      </section>

      {/* ── Service grid (hub) ── */}
      <main className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 sm:gap-5">
          {PUBLIC_SERVICES.map((service, i) => {
            const Icon = service.icon;
            return (
              <Link
                key={service.slug}
                to={`/services/${service.slug}`}
                className="group flex flex-col p-5 sm:p-6 rounded-2xl bg-white border border-primary-100 hover:shadow-lg hover:border-primary/40 transition-all"
              >
                <div className="flex items-center justify-between mb-4">
                  <span className="w-12 h-12 bg-primary/5 rounded-xl flex items-center justify-center group-hover:bg-primary/10 transition-colors">
                    <Icon className="w-6 h-6 text-primary" />
                  </span>
                  <span className="text-xs font-bold text-gray-300 tracking-widest tabular-nums">
                    {String(i + 1).padStart(2, '0')}
                  </span>
                </div>
                <h2 className="text-base font-semibold text-gray-900 group-hover:text-primary transition-colors">
                  {service.label}
                </h2>
                <p className="mt-2 text-sm text-gray-500 leading-relaxed flex-1">{service.cardDesc}</p>
                <span className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-primary/80 group-hover:text-primary transition-colors">
                  Learn more <ArrowRight className="w-4 h-4" />
                </span>
              </Link>
            );
          })}
        </div>

        {/* Secondary CTA */}
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
      </main>

      <PublicFooter />
      <FloatingWhatsAppButton />
    </div>
  );
}
