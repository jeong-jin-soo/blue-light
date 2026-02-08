import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';

interface AuthLayoutProps {
  children: ReactNode;
}

/**
 * Shared layout for Login/Signup pages.
 * Centers content with logo, gradient background, and card wrapper.
 */
export default function AuthLayout({ children }: AuthLayoutProps) {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-50 to-blue-50 px-4 py-8">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 bg-primary rounded-2xl mb-4">
            <span className="text-3xl">💡</span>
          </div>
          <h1 className="text-2xl font-bold text-primary">Blue Light</h1>
          <p className="text-sm text-gray-500 mt-1">Singapore EMA Licence Platform</p>
        </div>

        {/* Card */}
        <div className="bg-surface rounded-2xl shadow-auth p-8">
          {children}
        </div>

        {/* Footer links */}
        <div className="text-center mt-6 text-xs text-gray-400 space-x-3">
          <Link to="/disclaimer" className="hover:text-gray-600 transition-colors">Disclaimer</Link>
          <span>·</span>
          <Link to="/privacy" className="hover:text-gray-600 transition-colors">Privacy Policy</Link>
        </div>
      </div>
    </div>
  );
}
