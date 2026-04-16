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
                <Button variant="ghost" size="lg" onClick={scrollToFeatures}>
                  Learn More
                </Button>
              </div>
            </div>

            {/* Right — Visual mockup (hidden on mobile) */}
            <div className="hidden lg:block relative mt-12 lg:mt-0">
              <div className="bg-white rounded-2xl shadow-lg p-6 transform rotate-1 hover:rotate-0 transition-transform duration-300">
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
              {/* Decorative blobs */}
              <div className="absolute -top-4 -right-4 w-24 h-24 bg-primary/5 rounded-full -z-10" />
              <div className="absolute -bottom-6 -left-6 w-32 h-32 bg-blue-100/50 rounded-full -z-10" />
            </div>
          </div>
        </div>
      </section>

      {/* ── C. Features Grid ── */}
      <section id="features" className="py-16 sm:py-24">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-12">
            <span className="text-xs font-semibold tracking-widest text-primary uppercase">
              Platform Features
            </span>
            <h2 className="mt-3 text-2xl sm:text-3xl font-bold text-gray-900">
              Everything You Need for Licence Management{' '}
              <span className="text-primary italic">& more</span>
            </h2>
            <p className="mt-3 text-gray-500 max-w-2xl mx-auto">
              From application to approval, manage the entire licensing process online.
            </p>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 max-w-4xl mx-auto">
            {features.map((f) => (
              <div
                key={f.title}
                className="rounded-2xl border border-gray-100 p-6 hover:shadow-lg hover:border-gray-200 transition-all"
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
          </div>

          {/* Desktop: horizontal with connectors */}
          <div className="hidden lg:grid lg:grid-cols-4 gap-8 relative">
            {/* Connector line */}
            <div className="absolute top-6 left-[12.5%] right-[12.5%] h-0.5 bg-gray-200" aria-hidden="true">
              <div className="h-full w-1/2 bg-primary" />
            </div>
            {steps.map((s) => (
              <div key={s.num} className="relative text-center">
                <div className="w-12 h-12 bg-primary text-white rounded-full flex items-center justify-center text-lg font-bold mx-auto mb-4 relative z-10">
                  {s.num}
                </div>
                <h3 className="font-semibold text-gray-800 mb-2">{s.title}</h3>
                <p className="text-sm text-gray-500 leading-relaxed">{s.desc}</p>
              </div>
            ))}
          </div>

          {/* Mobile / Tablet: vertical timeline */}
          <div className="lg:hidden space-y-8 relative">
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
