import { Link } from 'react-router-dom';

export default function PrivacyPolicyPage() {
  return (
    <div className="min-h-screen bg-gray-50 py-12 px-4">
      <div className="max-w-3xl mx-auto">
        {/* Header */}
        <div className="text-center mb-8">
          <Link to="/" className="inline-flex items-center gap-2 text-primary hover:opacity-80 transition-opacity">
            <span className="text-2xl">💡</span>
            <span className="text-xl font-bold">LicenseKaki</span>
          </Link>
        </div>

        {/* Content */}
        <div className="bg-white rounded-2xl shadow-sm p-8 space-y-6">
          <h1 className="text-2xl font-bold text-gray-800">Privacy Policy</h1>
          <p className="text-sm text-gray-500">Last updated: February 2026</p>

          <p className="text-sm text-gray-600 leading-relaxed">
            LicenseKaki ("we", "our", "us") is committed to protecting your personal data in
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
              <li><strong>Documents:</strong> Single Line Diagrams (SLD), Letters of Appointment, and other uploaded documents</li>
              <li><strong>Chat Data:</strong> Messages you send through our AI chatbot assistant</li>
              <li><strong>Technical Data:</strong> IP address, browser User-Agent, HTTP request method and URL (collected as part of audit logging for security and compliance purposes)</li>
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
              <li>To provide AI-powered chatbot assistance (see Section 3)</li>
              <li>To maintain audit logs for security monitoring and incident investigation (IP address, User-Agent; see Section 6)</li>
              <li>To comply with legal and regulatory requirements</li>
            </ul>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">3. AI-Powered Chatbot (Google Gemini)</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              Our platform provides an AI-powered chatbot assistant to help you with questions about
              electrical installation licences and application procedures. This service is powered by{' '}
              <strong>Google Gemini API</strong>, a third-party artificial intelligence service provided
              by Google LLC.
            </p>
            <p className="text-sm text-gray-600 leading-relaxed">
              When you use the chatbot:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li><strong>Data sent to Google:</strong> Your chat messages and conversation history are transmitted to Google's servers for AI processing</li>
              <li><strong>Overseas transfer:</strong> Your chat data may be processed on Google's servers located outside of Singapore (including the United States and other jurisdictions)</li>
              <li><strong>Data retention by Google:</strong> Google may retain data in accordance with their own privacy policies and terms of service</li>
              <li><strong>No sensitive data:</strong> We do not send your personal account details (email, phone, address) to the chatbot. Only the messages you type are transmitted</li>
            </ul>
            <p className="text-sm text-gray-600 leading-relaxed">
              <strong>Your consent is required</strong> before using the chatbot. You will be prompted to
              provide consent when you first open the chat assistant. You may withdraw your consent at any
              time by choosing not to use the chatbot feature.
            </p>
            <p className="text-sm text-gray-600 leading-relaxed">
              For more information about Google's data practices, please refer to{' '}
              <a
                href="https://policies.google.com/privacy"
                target="_blank"
                rel="noopener noreferrer"
                className="text-primary underline"
              >
                Google's Privacy Policy
              </a>.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">4. Disclosure to Third Parties</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We may share your personal data with:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li><strong>Energy Market Authority (EMA):</strong> When submitting licence applications on your behalf through official channels</li>
              <li><strong>Licensed Electrical Workers (LEWs):</strong> As necessary for inspection coordination</li>
              <li><strong>Google LLC (Gemini API):</strong> Chat messages are processed by Google's AI service for the chatbot feature (see Section 3)</li>
              <li><strong>Legal authorities:</strong> When required by law or regulatory obligations</li>
            </ul>
            <p className="text-sm text-gray-600 leading-relaxed">
              We do not sell, rent, or trade your personal data to any third parties for marketing purposes.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">5. Data Retention</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We retain your personal data only for as long as necessary to fulfil the purposes
              for which it was collected, or as required by law:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li><strong>Account data:</strong> Retained while your account is active. Upon account deletion, personal data is anonymised</li>
              <li><strong>Application records:</strong> Retained for a minimum of 5 years to comply with regulatory requirements (EMA/BCA)</li>
              <li><strong>Chat messages:</strong> Automatically deleted after <strong>90 days</strong> from creation</li>
              <li><strong>Audit logs:</strong> Retained for <strong>365 days</strong> for security and compliance purposes (see Section 6)</li>
            </ul>
            <p className="text-sm text-gray-600 leading-relaxed">
              You may request deletion of your account data at any time via your Profile page,
              subject to our legal retention obligations.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">6. Data Security &amp; Audit Logging</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We implement appropriate technical and organisational measures to protect your
              personal data, including:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li>Encrypted password storage (BCrypt hashing)</li>
              <li>JWT-based authentication with httpOnly cookies</li>
              <li>Role-based access control (RBAC)</li>
              <li>Secure file storage with access verification</li>
              <li>Login rate limiting to prevent unauthorised access</li>
            </ul>
            <p className="text-sm text-gray-600 leading-relaxed mt-2">
              <strong>Audit logging:</strong> For security and compliance purposes, we maintain audit logs
              that record user actions (login, application submissions, administrative actions). These logs
              include your <strong>IP address</strong>, <strong>browser User-Agent</strong>, HTTP request
              details, and timestamps. Audit logs are retained for 365 days and are automatically deleted
              thereafter. Only system administrators have access to audit logs.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">7. Your Rights</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              Under the PDPA, you have the right to:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li><strong>Access:</strong> Request access to your personal data held by us</li>
              <li><strong>Correction:</strong> Request correction of inaccurate or incomplete personal data</li>
              <li><strong>Portability:</strong> Export your personal data in a machine-readable format via your profile page</li>
              <li><strong>Withdrawal:</strong> Withdraw your consent for data collection (note: this may affect our ability to provide services)</li>
              <li><strong>Deletion:</strong> Request deletion of your account and personal data via your profile page, subject to legal retention requirements</li>
            </ul>
            <p className="text-sm text-gray-600 leading-relaxed">
              You can exercise your data access and deletion rights directly from your{' '}
              <Link to="/profile" className="text-primary underline">Profile</Link> page,
              or contact our Data Protection Officer for assistance.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">8. Data Breach Notification</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              In the event of a data breach that is likely to cause significant harm, we will:
            </p>
            <ul className="text-sm text-gray-600 list-disc pl-5 space-y-1">
              <li>Notify the <strong>Personal Data Protection Commission (PDPC)</strong> within 3 calendar days of becoming aware of the breach</li>
              <li>Notify affected individuals as soon as practicable</li>
              <li>Take immediate steps to contain and remediate the breach</li>
              <li>Document all breaches and remediation actions taken</li>
            </ul>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">9. Cookies and Tracking</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We use essential cookies and local storage to maintain your login session and
              application state. We do not use third-party tracking cookies or analytics services
              that collect personal data.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">10. Changes to This Policy</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              We may update this Privacy Policy from time to time. Changes will be posted on this page
              with an updated revision date. Your continued use of our services after any changes
              constitutes your acceptance of the updated policy.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">11. Contact Us</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              If you have questions about this Privacy Policy or wish to exercise your data protection
              rights, please contact our Data Protection Officer:
            </p>
            <div className="bg-gray-50 rounded-lg p-4 text-sm text-gray-600">
              <p><strong>LicenseKaki Pte Ltd</strong></p>
              <p>Email: <a href="mailto:dpo@licensekaki.sg" className="text-primary underline">dpo@licensekaki.sg</a></p>
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
