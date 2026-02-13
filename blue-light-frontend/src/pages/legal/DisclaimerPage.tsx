import { Link } from 'react-router-dom';

export default function DisclaimerPage() {
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
          <h1 className="text-2xl font-bold text-gray-800">Disclaimer</h1>
          <p className="text-sm text-gray-500">Last updated: February 2026</p>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">Independent Service Provider</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              LicenseKaki is an independent service platform that facilitates the process of applying for
              electrical installation licences in Singapore. <strong>We are not affiliated with, endorsed by,
              or connected to the Energy Market Authority (EMA), SP Group, or any Singapore government
              agency.</strong>
            </p>
            <p className="text-sm text-gray-600 leading-relaxed">
              Official licence applications are submitted through the EMA's ELISE portal
              at <a href="https://elise.ema.gov.sg" target="_blank" rel="noopener noreferrer" className="text-primary underline">elise.ema.gov.sg</a>.
              LicenseKaki provides a supplementary management service to help applicants prepare, track,
              and organise their licence application documentation.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">Service Fees</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              Fees displayed on this platform are LicenseKaki's service fees for facilitating your
              application. These are separate from any official government fees charged by the EMA.
              Please refer to the EMA's official website for current government fee schedules.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">Use of Information</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              Information about EMA licence grades, application processes, and kVA pricing tiers
              provided on this platform is sourced from publicly available information and is intended
              for general reference only. While we strive to keep information accurate and up-to-date,
              we make no guarantees regarding the completeness, accuracy, or timeliness of the
              information provided.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">Official Forms and Documents</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              LicenseKaki does not reproduce or replicate official EMA or SP Group forms. Where official
              forms are required, users will be guided to the appropriate government channels to access
              and submit them.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">Limitation of Liability</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              LicenseKaki shall not be held liable for any delays, errors, or issues arising from
              the use of this platform, including but not limited to: application processing delays
              by government agencies, changes in government regulations or fee structures, service
              interruptions, or any indirect damages resulting from the use of our services.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">Intellectual Property</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              All logos, trademarks, and branding on this platform are the property of LicenseKaki.
              Any references to EMA, SP Group, or other entities are used solely for informational
              purposes and do not imply any affiliation or endorsement.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-gray-700">Contact</h2>
            <p className="text-sm text-gray-600 leading-relaxed">
              For questions regarding this disclaimer, please contact us
              at <a href="mailto:support@licensekaki.sg" className="text-primary underline">support@licensekaki.sg</a>.
            </p>
          </section>
        </div>

        {/* Footer nav */}
        <div className="text-center mt-6 space-x-4 text-sm text-gray-500">
          <Link to="/privacy" className="hover:text-primary transition-colors">Privacy Policy</Link>
          <span>·</span>
          <Link to="/login" className="hover:text-primary transition-colors">Back to Login</Link>
        </div>
      </div>
    </div>
  );
}
