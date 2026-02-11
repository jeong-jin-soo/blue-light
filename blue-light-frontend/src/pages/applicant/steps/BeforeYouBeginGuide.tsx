import { Button } from '../../../components/ui/Button';
import { Card } from '../../../components/ui/Card';

interface BeforeYouBeginGuideProps {
  onStart: () => void;
  onCancel: () => void;
}

export function BeforeYouBeginGuide({ onStart, onCancel }: BeforeYouBeginGuideProps) {
  return (
    <Card>
      <div className="space-y-6">
        <div className="flex justify-between items-start">
          <div>
            <h2 className="text-lg font-semibold text-gray-800">Before You Begin</h2>
            <p className="text-sm text-gray-500 mt-1">
              Please review the following checklist to ensure a smooth application process.
            </p>
          </div>
          <Button size="sm" onClick={onStart}>
            Start Application
          </Button>
        </div>

        {/* Process Overview */}
        <div className="bg-gray-50 rounded-xl p-5 border border-gray-200">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider mb-4">Application Process</h3>
          <div className="space-y-3">
            {[
              { step: '1', title: 'Submit Application', desc: 'Fill in property details, select kVA capacity, and review pricing.' },
              { step: '2', title: 'Upload Documents', desc: 'Upload required documents including SLD (Single Line Diagram) and authorisation letter.' },
              { step: '3', title: 'LEW Review', desc: 'A Licensed Electrical Worker will review your application. You may be asked to revise.' },
              { step: '4', title: 'Make Payment', desc: 'Once approved, complete payment via PayNow or bank transfer.' },
              { step: '5', title: 'Licence Issued', desc: 'After verification, your electrical installation licence will be issued.' },
            ].map(({ step, title, desc }) => (
              <div key={step} className="flex items-start gap-3">
                <div className="flex-shrink-0 w-7 h-7 bg-primary-100 text-primary-700 rounded-full flex items-center justify-center text-sm font-bold">
                  {step}
                </div>
                <div>
                  <p className="text-sm font-medium text-gray-800">{title}</p>
                  <p className="text-xs text-gray-500 mt-0.5">{desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Required Documents Checklist */}
        <div className="bg-amber-50 rounded-xl p-5 border border-amber-200">
          <h3 className="text-sm font-semibold text-amber-800 uppercase tracking-wider mb-3">Required Documents</h3>
          <p className="text-xs text-amber-700 mb-3">Prepare these documents before starting your application. You can upload them after submission.</p>
          <ul className="space-y-2">
            {[
              { label: 'Single Line Diagram (SLD)', desc: 'Accepted formats: PDF, JPG, DWG, DXF, DGN, TIF, GIF, ZIP' },
              { label: "Owner's Authorisation Letter", desc: 'Signed letter authorising the electrical installation work' },
            ].map(({ label, desc }) => (
              <li key={label} className="flex items-start gap-2.5">
                <svg className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
                <div>
                  <p className="text-sm font-medium text-amber-900">{label}</p>
                  <p className="text-xs text-amber-700">{desc}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>

        {/* Key Information */}
        <div className="bg-blue-50 rounded-xl p-5 border border-blue-200">
          <h3 className="text-sm font-semibold text-blue-800 uppercase tracking-wider mb-3">Key Information</h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {[
              { icon: '💰', title: 'Pricing', desc: 'Based on your DB Size (kVA). Service fee and EMA fee apply.' },
              { icon: '⏱️', title: 'Licence Period', desc: 'Choose between 3-month or 12-month licence validity.' },
              { icon: '🔌', title: 'SP Group Account', desc: 'You need an SP Group utilities account before applying.' },
              { icon: '📋', title: 'EMA Submission', desc: 'Files for ELISE submission must be under 2MB each.' },
            ].map(({ icon, title, desc }) => (
              <div key={title} className="flex items-start gap-2">
                <span className="text-blue-600 mt-0.5">{icon}</span>
                <div>
                  <p className="text-sm font-medium text-blue-900">{title}</p>
                  <p className="text-xs text-blue-700">{desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Start Application Button */}
        <div className="flex justify-between items-center pt-2">
          <Button variant="outline" onClick={onCancel}>
            Cancel
          </Button>
          <Button onClick={onStart}>
            Start Application
          </Button>
        </div>
      </div>
    </Card>
  );
}
