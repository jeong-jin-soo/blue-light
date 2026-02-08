import type { ReactNode } from 'react';

interface AuthLayoutProps {
  children: ReactNode;
}

/**
 * Shared layout for Login/Signup pages.
 * Centers content with logo, gradient background, and card wrapper.
 */
export default function AuthLayout({ children }: AuthLayoutProps) {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-50 to-blue-50 px-4 py-8">
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
      </div>
    </div>
  );
}
