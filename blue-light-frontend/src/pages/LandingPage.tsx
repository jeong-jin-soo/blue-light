import { useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';
import { Button } from '../components/ui/Button';
import licensekakiLogo from '../assets/licensekaki-logo.png';

/* ------------------------------------------------------------------ */
/*  Data                                                               */
/* ------------------------------------------------------------------ */

const features = [
  {
    icon: '📋',
    title: 'Online Licence Applications',
    desc: 'Submit NEW or RENEWAL applications for EMA electrical installation licences with a guided multi-step form. Upload SLD drawings, authorization letters, and supporting documents — all online.',
    bullets: ['Multi-step guided form', 'Document upload & management', 'Real-time status tracking'],
  },
  {
    icon: '📐',
    title: 'Professional SLD Drawings',
    desc: 'Order single-line diagram drawings from qualified professionals. Receive custom quotes and professional SLD generation for faster turnaround.',
    bullets: ['Custom quote system', 'Professional generation', 'Expert review & revision'],
  },
];

const steps = [
  { num: 1, title: 'Create Account', desc: 'Sign up as a building, business, or shop owner. Add your details and SP account information.' },
  { num: 2, title: 'Submit Application', desc: 'Choose NEW or RENEWAL, enter your property details, select kVA capacity, and upload required documents.' },
  { num: 3, title: 'Review & Payment', desc: 'Your application is reviewed by a Licensed Electrical Worker. Once approved, complete payment via PayNow or QRcode.' },
  { num: 4, title: 'Licence Issued', desc: 'Track your application in real-time. Once processing is complete, your electrical installation licence is issued.' },
];

const trustItems = [
  { icon: '🔒', label: 'AES-256 Encryption', desc: 'Enterprise-grade file encryption at rest' },
  { icon: '🛡️', label: 'PDPA Compliant', desc: 'Singapore data protection compliance' },
  { icon: '📝', label: 'Audit Trail', desc: 'Complete logging for transparency' },
];

const statusSteps = [
  { label: 'Application Submitted', done: true },
  { label: 'Under Review', done: true },
  { label: 'Payment Completed', done: true },
  { label: 'Licence Issued', done: false, current: true },
];

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export default function LandingPage() {
  const navigate = useNavigate();
  const { isAuthenticated, user } = useAuthStore();

  // Redirect authenticated users to their dashboard
  useEffect(() => {
    if (isAuthenticated && user) {
      const dest =
        user.role === 'SYSTEM_ADMIN'  ? '/admin/system'
        : user.role === 'ADMIN'       ? '/admin/dashboard'
        : user.role === 'LEW'         ? '/lew/dashboard'
        : user.role === 'SLD_MANAGER' ? '/sld-manager/dashboard'
        : '/dashboard';
      navigate(dest, { replace: true });
    }
  }, [isAuthenticated, user, navigate]);

  const scrollToFeatures = () => {
    document.getElementById('features')?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <div className="min-h-screen bg-white">
      {/* ── A. Navigation Bar ── */}
      <header className="sticky top-0 z-50 bg-white/95 backdrop-blur-sm border-b border-gray-100">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center">
            <img src={licensekakiLogo} alt="LicenseKaki" className="h-6" />
          </div>
          <div className="flex items-center gap-3">
            <Link to="/login">
              <Button variant="ghost" size="sm">Sign In</Button>
            </Link>
            <Link to="/signup?role=APPLICANT">
              <Button size="sm">Get Started</Button>
            </Link>
          </div>
        </div>
      </header>

      {/* ── B. Hero Section ── */}
      <section className="bg-gradient-to-br from-slate-50 to-blue-50">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-16 sm:py-24 lg:py-28">
          <div className="lg:grid lg:grid-cols-2 lg:gap-16 items-center">
            {/* Left — Copy */}
            <div>
              <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-medium bg-primary/10 text-primary mb-6">
                Electrical Installation Licensing Platform
              </span>
              <h1 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-gray-900 leading-tight">
                Electrical Installation Licences,{' '}
                <span className="text-primary">Simplified</span>
              </h1>
              <p className="mt-5 text-base sm:text-lg text-gray-500 leading-relaxed max-w-lg">
                The end-to-end digital platform for applying, tracking, and managing
                EMA electrical installation licences in Singapore.
                For building, business, and shop owners, LEWs, and SLD professionals.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Link to="/signup?role=APPLICANT">
                  <Button size="lg">Apply for a Licence</Button>
                </Link>
                <Button
                  variant="ghost"
                  size="lg"
                  onClick={scrollToFeatures}
                  className="italic font-extrabold bg-gradient-to-r from-emerald-500 to-green-600 bg-clip-text text-transparent hover:bg-emerald-50"
                >
                  &amp; more
                </Button>
              </div>
            </div>

            {/* Right — Concierge CTA card (★ Kaki Concierge v1.5 — Hero inline placement, 모바일에서도 노출) */}
            <div className="relative mt-10 lg:mt-0">
              <div className="bg-concierge-50 border-2 border-concierge-300 rounded-2xl shadow-md p-6 sm:p-8">
                <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-concierge-100 text-concierge-700 text-xs font-semibold mb-4">
                  <svg
                    aria-hidden="true"
                    className="w-4 h-4"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 00-2.456 2.456z"
                    />
                  </svg>
                  White-Glove Service
                </div>
                <h2 className="text-xl sm:text-2xl font-bold text-gray-900 mb-3">
                  Let us handle your licensing
                </h2>
                <p className="text-sm text-gray-700 leading-relaxed mb-6">
                  Our team personally manages your entire electrical licensing
                  process — from submission to approval.{' '}
                  <strong className="text-concierge-700">We come to you.</strong>
                </p>
                <Button
                  variant="concierge"
                  size="lg"
                  fullWidth
                  onClick={() => navigate('/concierge/request')}
                >
                  Start Kaki Concierge
                </Button>
                <p className="mt-3 text-xs text-gray-500 text-center">
                  A Concierge Manager will contact you within 24 hours.
                </p>
              </div>
              {/* Decorative blobs — desktop만 (모바일 공간 절약) */}
              <div className="hidden lg:block absolute -top-4 -right-4 w-24 h-24 bg-concierge-100/60 rounded-full -z-10" />
              <div className="hidden lg:block absolute -bottom-6 -left-6 w-32 h-32 bg-concierge-50 rounded-full -z-10" />
            </div>
          </div>
        </div>
      </section>

      {/* ── C. Features Grid ── */}
      <section id="features" className="py-16 sm:py-24 bg-gradient-to-br from-emerald-50 to-green-50/60">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-12">
            <span className="text-xs font-semibold tracking-widest text-primary uppercase">
              Platform Features
            </span>
            <h2 className="mt-3 text-2xl sm:text-3xl font-bold text-gray-900">
              Everything You Need for Licence Management{' '}
              <span className="italic font-extrabold text-3xl sm:text-4xl bg-gradient-to-r from-emerald-500 to-green-600 bg-clip-text text-transparent">
                &amp; more
              </span>
            </h2>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 max-w-4xl mx-auto">
            {features.map((f) => (
              <div
                key={f.title}
                className="rounded-2xl bg-white border border-emerald-100/80 p-6 hover:shadow-lg hover:border-emerald-200 transition-all"
              >
                <div className="w-12 h-12 bg-primary/5 rounded-xl flex items-center justify-center text-2xl mb-4">
                  {f.icon}
                </div>
                <h3 className="text-lg font-semibold text-gray-800 mb-2">{f.title}</h3>
                <p className="text-sm text-gray-500 leading-relaxed mb-4">{f.desc}</p>
                <ul className="space-y-2">
                  {f.bullets.map((b) => (
                    <li key={b} className="flex items-center gap-2 text-sm text-gray-600">
                      <span className="w-1.5 h-1.5 bg-primary rounded-full flex-shrink-0" />
                      {b}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>

          {/* Request-a-service CTA — 4 direct entry points */}
          <div className="mt-12 sm:mt-16">
            <div className="text-center mb-6">
              <h3 className="text-lg sm:text-xl font-semibold text-gray-800">
                Start a Request
              </h3>
              <p className="mt-1.5 text-sm text-gray-500">
                Choose a service to get started instantly.
              </p>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 sm:gap-4 max-w-4xl mx-auto">
              {[
                { icon: '📐', label: 'SLD Drawing',     to: '/sld-orders/new' },
                { icon: '💡', label: 'Lighting Layout', to: '/lighting-orders/new' },
                { icon: '🔌', label: 'Power Socket',    to: '/power-socket-orders/new' },
                { icon: '⚡', label: 'LEW Service',     to: '/lew-service-orders/new' },
              ].map((item) => (
                <button
                  key={item.to}
                  type="button"
                  onClick={() => {
                    if (isAuthenticated) {
                      navigate(item.to);
                    } else {
                      // 회원가입 후 원래 기능 요청 페이지로 리다이렉트
                      navigate(`/signup?role=APPLICANT&returnTo=${encodeURIComponent(item.to)}`);
                    }
                  }}
                  className="group flex flex-col items-center gap-2 p-4 sm:p-5 rounded-2xl bg-white border border-emerald-100/80 hover:shadow-lg hover:border-primary/40 transition-all text-center"
                >
                  <span className="w-12 h-12 bg-primary/5 rounded-xl flex items-center justify-center text-2xl group-hover:bg-primary/10 transition-colors">
                    {item.icon}
                  </span>
                  <span className="text-sm font-semibold text-gray-800 group-hover:text-primary transition-colors">
                    {item.label}
                  </span>
                  <span className="text-xs text-gray-400 group-hover:text-primary/70 transition-colors">
                    Request now →
                  </span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* ── D. How It Works ── */}
      <section className="py-16 sm:py-24 bg-gray-50">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-12">
            <span className="text-xs font-semibold tracking-widest text-primary uppercase">
              How It Works
            </span>
            <h2 className="mt-3 text-2xl sm:text-3xl font-bold text-gray-900">
              Get Your Licence in 4 Simple Steps
            </h2>
            <p className="mt-3 text-gray-500 max-w-xl mx-auto">
              Track your application in real time — here's how a licence progresses.
            </p>
          </div>

          <div className="lg:grid lg:grid-cols-[1fr_auto] lg:gap-16 lg:items-center">
            {/* 좌측: 4-step vertical timeline */}
            <div className="space-y-6 sm:space-y-8 relative">
              <div className="absolute left-5 top-0 bottom-0 w-0.5 bg-gray-200" aria-hidden="true" />
              {steps.map((s) => (
                <div key={s.num} className="relative pl-14">
                  <div className="absolute left-0 w-10 h-10 bg-primary text-white rounded-full flex items-center justify-center text-sm font-bold z-10">
                    {s.num}
                  </div>
                  <h3 className="font-semibold text-gray-800 mb-1">{s.title}</h3>
                  <p className="text-sm text-gray-500 leading-relaxed">{s.desc}</p>
                </div>
              ))}
            </div>

            {/* 우측: APPLICATION status mockup (desktop only) */}
            <div className="hidden lg:block relative mt-0 w-80">
              <div className="bg-white rounded-2xl shadow-lg p-6 transform -rotate-1 hover:rotate-0 transition-transform duration-300">
                <div className="flex items-center justify-between mb-4">
                  <span className="text-xs font-medium text-gray-400 tracking-wide">APPLICATION #2026-0142</span>
                  <span className="px-2 py-0.5 rounded-full bg-primary/10 text-primary text-[11px] font-semibold">In Progress</span>
                </div>
                <div className="space-y-4">
                  {statusSteps.map((s, i) => (
                    <div key={i} className="flex items-center gap-3">
                      <div
                        className={`w-3 h-3 rounded-full flex-shrink-0 ${
                          s.current
                            ? 'bg-primary ring-4 ring-primary/20'
                            : s.done
                              ? 'bg-emerald-500'
                              : 'bg-gray-200'
                        }`}
                      />
                      <span className={`text-sm ${s.current ? 'font-semibold text-primary' : s.done ? 'text-gray-600' : 'text-gray-400'}`}>
                        {s.label}
                      </span>
                      {s.done && <span className="text-emerald-500 text-xs ml-auto">✓</span>}
                      {s.current && <span className="text-primary text-xs ml-auto font-medium">Processing…</span>}
                    </div>
                  ))}
                </div>
              </div>
              <div className="absolute -top-4 -right-4 w-20 h-20 bg-primary/5 rounded-full -z-10" />
              <div className="absolute -bottom-6 -left-6 w-28 h-28 bg-blue-100/50 rounded-full -z-10" />
            </div>
          </div>
        </div>
      </section>

      {/* ── E. Trust & Security Banner ── */}
      <section className="py-14 sm:py-20">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-8">
            {trustItems.map((t) => (
              <div key={t.label} className="text-center">
                <div className="w-12 h-12 bg-primary/5 rounded-xl flex items-center justify-center text-2xl mx-auto mb-3">
                  {t.icon}
                </div>
                <h4 className="text-sm font-semibold text-gray-800">{t.label}</h4>
                <p className="mt-1 text-xs text-gray-500">{t.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── F. CTA Band ── */}
      <section className="bg-primary">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-14 sm:py-20 text-center">
          <h2 className="text-2xl sm:text-3xl font-bold text-white mb-4">
            Ready to Simplify Your Licence Application{' '}
            <span className="italic">&amp; more</span>?
          </h2>
          <p className="text-blue-200 mb-8 max-w-2xl mx-auto">
            Singapore's first seamless online electrical licensing platform.
          </p>
          <div className="flex flex-wrap justify-center gap-3">
            <Link to="/signup?role=APPLICANT">
              <Button
                size="lg"
                variant="outline"
                className="!bg-white !text-primary !border-white hover:!bg-gray-100"
              >
                Apply for a Licence
              </Button>
            </Link>
          </div>
        </div>
      </section>

      {/* ── Footer ── */}
      <footer className="bg-gray-50 border-t border-gray-200 py-8">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex flex-col sm:flex-row items-center justify-between gap-4">
            <div className="flex items-center">
              <img src={licensekakiLogo} alt="LicenseKaki" className="h-5" />
            </div>
            <div className="flex items-center gap-4 text-xs text-gray-400">
              <Link to="/disclaimer" className="hover:text-gray-600 transition-colors">Disclaimer</Link>
              <span>·</span>
              <Link to="/privacy" className="hover:text-gray-600 transition-colors">Privacy Policy</Link>
            </div>
            <span className="text-xs text-gray-400">
              &copy; {new Date().getFullYear()} LicenseKaki. All rights reserved.
            </span>
          </div>
        </div>
      </footer>
    </div>
  );
}
