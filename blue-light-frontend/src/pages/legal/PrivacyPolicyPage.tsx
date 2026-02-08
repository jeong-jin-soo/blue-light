import { Link } from 'react-router-dom';

export default function PrivacyPolicyPage() {
  return (
    <div className="min-h-screen bg-gray-50 py-12 px-4">
      <div className="max-w-3xl mx-auto">
        {/* Header */}
        <div className="text-center mb-8">
          <Link to="/" className="inline-flex items-center gap-2 text-primary hover:opacity-80 transition-opacity">
            <span className="text-2xl">💡</span>
            <span className="text-xl font-bold">Blue Light</span>
          </Link>
        </div>

        {/* Content */}
        <div className="bg-white rounded-2xl shadow-sm p-8 space-y-6">
          <h1 className="text-2xl font-bold text-gray-800">Privacy Policy</h1>
          <p className="text-sm text-gray-500">Last updated: February 2026</p>

          <p className="text-sm text-gray-600 leading-relaxed">
            Blue Light ("we", "our", "us") is committed to protecting your personal data in
            compliance with the <strong>Singapore Personal Data Protection Act 2012 (PDPA)</strong>.
            This Privacy Policy explains how we collect, use, disclose, and protect your personal data.
          </p>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">1. Personal Data We Collect</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We collect the following personal data when you use our services:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li><strong>Account Information:</strong> Full name, email address, phone number</li>
              <li><strong>Application Information:</strong> Installation address, postal code, building type</li>
              <li><strong>Documents:</strong> Single Line Diagrams (SLD), Owner's Authorisation Letters, and other uploaded documents</li>
              <li><strong>Usage Data:</strong> Login timestamps, application activity logs</li>
            </ul>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">2. Purpose of Collection</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              Your personal data is collected and used for the following purposes:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li>To create and manage your user account</li>
              <li>To process and facilitate your electrical installation licence applications</li>
              <li>To communicate with you regarding your application status</li>
              <li>To calculate pricing based on your selected kVA capacity</li>
              <li>To comply with legal and regulatory requirements</li>
            </ul>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">3. Disclosure to Third Parties</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We may share your personal data with:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li><strong>Energy Market Authority (EMA):</strong> When submitting licence applications on your behalf through official channels</li>
              <li><strong>Licensed Electrical Workers (LEWs):</strong> As necessary for inspection coordination</li>
              <li><strong>Legal authorities:</strong> When required by law or regulatory obligations</li>
            </ul>
            <p className="text-sm text-gray-600 leading-relaxed">
              We do not sell, rent, or trade your personal data to any third parties for marketing purposes.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">4. Data Retention</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We retain your personal data for as long as your account is active or as needed to
              provide our services. Application records are retained for a minimum of 5 years to
              comply with regulatory requirements. You may request deletion of your account data,
              subject to our legal retention obligations.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">5. Data Security</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We implement appropriate technical and organisational measures to protect your
              personal data, including:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li>Encrypted password storage (BCrypt hashing)</li>
              <li>JWT-based authentication with time-limited tokens</li>
              <li>Role-based access control (RBAC)</li>
              <li>Secure file storage with access verification</li>
              <li>Login rate limiting to prevent unauthorised access</li>
            </ul>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">6. Your Rights</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              Under the PDPA, you have the right to:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li><strong>Access:</strong> Request access to your personal data held by us</li>
              <li><strong>Correction:</strong> Request correction of inaccurate or incomplete personal data</li>
              <li><strong>Withdrawal:</strong> Withdraw your consent for data collection (note: this may affect our ability to provide services)</li>
              <li><strong>Deletion:</strong> Request deletion of your personal data, subject to legal retention requirements</li>
            </ul>
            <p className="text-sm text-gray-600 leading-relaxed">
              To exercise any of these rights, please contact our Data Protection Officer.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">7. Cookies and Tracking</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We use essential cookies and local storage to maintain your login session and
              application state. We do not use third-party tracking cookies or analytics services
              that collect personal data.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">8. Changes to This Policy</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We may update this Privacy Policy from time to time. Changes will be posted on this page
              with an updated revision date. Your continued use of our services after any changes
              constitutes your acceptance of the updated policy.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">9. Contact Us</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              If you have questions about this Privacy Policy or wish to exercise your data protection
              rights, please contact our Data Protection Officer:
            </p>
            <div className="bg-gray-50 rounded-lg p-4 text-sm text-gray-600">
              <p><strong>Blue Light Pte Ltd</strong></p>
              <p>Email: <a href="mailto:dpo@bluelight.sg" className="text-primary underline">dpo@bluelight.sg</a></p>
            </div>
          </section>
        </div>

        {/* Footer nav */}
        <div className="text-center mt-6 space-x-4 text-sm text-gray-500">
          <Link to="/disclaimer" className="hover:text-primary transition-colors">Disclaimer</Link>
          <span>·</span>
          <Link to="/login" className="hover:text-primary transition-colors">Back to Login</Link>
        </div>
      </div>
    </div>
  );
}
