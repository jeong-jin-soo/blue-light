import { Plug, FileText, Info } from 'lucide-react';
import { Button } from '../../../components/ui/Button';
import { Card } from '../../../components/ui/Card';

interface BeforeYouBeginGuideProps {
  onStart: () => void;
  onCancel: () => void;
}

/**
 * Before You Begin — 신청 전 체크리스트.
 * §9-2 C: 색색 박스(green/amber/blue) 나열 → 무채(surface-tertiary) + navy 아이콘으로 통일,
 * 필수 요건(SP Group 계정)에만 레드 액센트 바 1점. 카피는 기존 문구 유지.
 */
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

        {/* SP Group Account Notice — 유일한 하드 요건이라 레드 액센트 바로 강조 */}
        <div className="bg-surface-tertiary rounded-xl p-5 border border-primary-100 border-l-[3px] border-l-accent">
          <div className="flex items-start gap-3">
            <Plug className="w-5 h-5 text-primary shrink-0 mt-0.5" />
            <div>
              <h3 className="text-sm font-semibold text-gray-800 uppercase tracking-wider mb-2">
                SP Group Account (New Licence Only)
              </h3>
              <p className="text-sm text-gray-600 leading-relaxed">
                If you are applying for a <strong>New Licence</strong>, you must have an active SP Group electricity account for the installation address.
                If you don't have one, please open a group account at{' '}
                <a
                  href="https://www.spgroup.com.sg"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="underline font-medium text-primary hover:text-primary-700"
                >
                  www.spgroup.com.sg
                </a>{' '}
                before submitting your application.
              </p>
              <p className="text-xs text-gray-500 mt-2">
                * This is not required for Licence Renewal applications.
              </p>
            </div>
          </div>
        </div>

        {/* Process Overview */}
        <div className="bg-surface-tertiary rounded-xl p-5 border border-primary-100">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider mb-4">Application Process</h3>
          <div className="space-y-3">
            {[
              { step: '1', title: 'Submit Application', desc: 'Fill in property details, select kVA capacity, and review pricing. For New Licence applications, an SP Group account is required.' },
              { step: '2', title: 'Upload Documents', desc: 'Upload required documents including SLD (Single Line Diagram) and Letter of Appointment.' },
              { step: '3', title: 'LEW Review', desc: 'A Licensed Electrical Worker will review your application. You may be asked to revise.' },
              { step: '4', title: 'Make Payment', desc: 'Once approved, complete payment via PayNow.' },
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
        <div className="bg-surface-tertiary rounded-xl p-5 border border-primary-100">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider mb-3">Required Documents</h3>
          <p className="text-xs text-gray-500 mb-3">Prepare these documents before starting your application. You can upload them after submission.</p>
          <ul className="space-y-2">
            {[
              { label: 'SP Account Email Screenshot (New Licence)', desc: 'Screenshot or PDF of SP Group account confirmation email (PDF, JPG)' },
              { label: 'Single Line Diagram (SLD)', desc: 'Accepted formats: PDF, JPG, DWG, DXF, DGN, TIF, GIF, ZIP' },
              { label: 'Letter of Appointment', desc: 'Signed letter appointing the Licensed Electrical Worker (PDF, JPG)' },
              { label: 'Main Breaker Box Photo', desc: 'Photo of the main breaker box at the installation site (JPG, PNG)' },
            ].map(({ label, desc }) => (
              <li key={label} className="flex items-start gap-2.5">
                <FileText className="w-5 h-5 text-primary flex-shrink-0 mt-0.5" />
                <div>
                  <p className="text-sm font-medium text-gray-800">{label}</p>
                  <p className="text-xs text-gray-500">{desc}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>

        {/* Key Information */}
        <div className="bg-surface-tertiary rounded-xl p-5 border border-primary-100">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider mb-3">Key Information</h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {[
              { title: 'Pricing', desc: 'Pricing is based on kVA capacity tier and EMA fee (if applicable).' },
              { title: 'Licence Period', desc: 'Choose between 3-month or 12-month licence validity.' },
              { title: 'SP Group Account', desc: 'An SP Group utilities account is required for New Licence applications.' },
              { title: 'File Submission', desc: 'Files for licence submission must be under 2MB each.' },
            ].map(({ title, desc }) => (
              <div key={title} className="flex items-start gap-2">
                <Info className="w-4 h-4 text-primary mt-0.5 shrink-0" />
                <div>
                  <p className="text-sm font-medium text-gray-800">{title}</p>
                  <p className="text-xs text-gray-500">{desc}</p>
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
