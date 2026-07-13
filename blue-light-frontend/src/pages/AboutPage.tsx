import { useEffect } from 'react';
import { Check } from 'lucide-react';
import { WHATSAPP_GENERIC_MESSAGE } from '../constants/publicServices';
import { buildWhatsAppLink } from '../utils/whatsapp';
import { trackPageView, trackWhatsAppClick } from '../utils/track';
import { useWhatsAppNumber } from '../hooks/useWhatsAppNumber';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { SEO_META } from '../constants/seoMeta';
import PublicHeader from '../components/common/PublicHeader';
import PublicFooter from '../components/common/PublicFooter';
import FloatingWhatsAppButton from '../components/common/FloatingWhatsAppButton';
import WhatsAppIcon from '../components/common/WhatsAppIcon';

/**
 * About Us — 공개 페이지. 헤더의 "About Us" 링크(구 Sign In)가 여기로 연결된다.
 * 카피는 클라이언트 제공 원문(About LicenseKaki). 신청·가입 흐름 없이 회사 소개 +
 * WhatsApp 문의 채널만 노출한다(WhatsApp 퍼스트).
 */

const whyChooseUs = [
  'Singapore-owned and operated',
  'Team of experienced professionals and Licensed Electrical Workers (LEWs)',
  'Directors with 10+ years in electrical engineering, BCA ME05-registered',
  'Proven track record working with Singapore authorities',
  'Every case handled with care, diligence, and professionalism',
];

export default function AboutPage() {
  const whatsappNumber = useWhatsAppNumber();

  useDocumentMeta(SEO_META.about);

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
        <div className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 text-center">
          <span className="text-xs font-semibold tracking-widest text-primary uppercase">
            About Us
          </span>
          <h1 className="mt-3 text-2xl sm:text-3xl font-bold text-gray-900">
            About LicenseKaki
          </h1>
          <p className="mt-5 text-gray-600 leading-relaxed">
            LicenseKaki is proudly Singapore-owned, operating under HanVision
            Holdings Private Limited. We're a dedicated team of experienced
            professionals and Licensed Electrical Workers (LEWs) who have worked
            closely with Singapore's regulatory authorities — including the Energy
            Market Authority (EMA) — to help businesses and homeowners navigate the
            electrical licensing process with confidence and ease.
          </p>
          <p className="mt-4 text-gray-600 leading-relaxed">
            We understand that every application matters. Whether it's a new
            license, a renewal, or supporting documentation like SLDs and layout
            plans, we treat each case with the seriousness and attention to detail
            it deserves.
          </p>
        </div>
      </section>

      <main className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-12 sm:py-16 space-y-10">
        {/* ── Backed by Real Industry Experience ── */}
        <section className="bg-white rounded-2xl border border-primary-100 p-6 sm:p-8">
          <h2 className="text-lg sm:text-xl font-bold text-gray-900">
            Backed by Real Industry Experience
          </h2>
          <p className="mt-3 text-sm text-gray-600 leading-relaxed">
            Our directors bring over 10 years of hands-on experience running
            electrical engineering companies in Singapore, and are registered under
            BCA ME05 (Electrical Engineering) — a recognized Building and
            Construction Authority registration for electrical engineering works.
            This means LicenseKaki isn't just a service platform; we're backed by
            real, licensed industry practitioners who understand the technical and
            regulatory landscape from the inside.
          </p>
        </section>

        {/* ── Why Choose Us ── */}
        <section className="bg-white rounded-2xl border border-primary-100 p-6 sm:p-8">
          <h2 className="text-lg sm:text-xl font-bold text-gray-900">Why Choose Us</h2>
          <ul className="mt-4 space-y-2.5">
            {whyChooseUs.map((item) => (
              <li key={item} className="flex items-start gap-2 text-sm text-gray-700">
                <Check className="w-4 h-4 text-success-500 mt-0.5 flex-shrink-0" />
                {item}
              </li>
            ))}
          </ul>
          <p className="mt-6 text-sm font-medium text-gray-800 leading-relaxed">
            Rest assured — when you work with LicenseKaki, you're working with a
            legitimate, experienced, and accountable team.
          </p>
        </section>

        {/* ── WhatsApp CTA ── */}
        <section className="bg-white rounded-2xl border border-primary-100 p-6 sm:p-8 text-center">
          <h2 className="text-lg sm:text-xl font-bold text-gray-900">
            Have a question? Just ask.
          </h2>
          <p className="mt-2 text-sm text-gray-600">
            No account or form needed — message us on WhatsApp and a real person
            replies.
          </p>
          <a
            href={whatsappHref}
            target="_blank"
            rel="noopener noreferrer"
            onClick={() => trackWhatsAppClick()}
            className="mt-5 inline-flex items-center gap-2 rounded-lg bg-[#25D366] px-6 py-3 text-sm font-semibold text-white shadow-sm hover:bg-[#1da851] hover:shadow-md transition-all"
          >
            <WhatsAppIcon className="w-5 h-5" />
            Chat with Us on WhatsApp
          </a>
        </section>
      </main>

      <PublicFooter />
      <FloatingWhatsAppButton />
    </div>
  );
}
