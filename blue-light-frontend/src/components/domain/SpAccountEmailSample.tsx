/**
 * Mock SP Services Ltd email samples for the SP Account upload guide.
 * All data shown is fictitious — modelled after real SP Group confirmation emails.
 */
export function SpAccountEmailSample() {
  return (
    <div className="space-y-6">
      {/* ── Sample 1: Account Opening Request ── */}
      <div>
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
          Sample 1 — Account Opening Request
        </p>
        <div className="border border-gray-200 rounded-lg overflow-hidden bg-white text-sm">
          {/* Email header */}
          <div className="bg-gray-800 text-white px-4 py-3">
            <p className="text-base font-bold">SP Services Ltd</p>
          </div>
          {/* Email body */}
          <div className="px-4 py-4 space-y-3 text-gray-700 leading-relaxed">
            <p>Dear Sir or Madam,</p>

            <div className="space-y-0.5">
              <p>
                <span className="font-semibold">ACCOUNT NUMBER : </span>
                <span className="bg-yellow-100 px-1 rounded">12XXXXXXXX</span>
              </p>
              <p>
                <span className="font-semibold">ACCOUNT HOLDER : </span>
                <span className="bg-yellow-100 px-1 rounded">YOUR COMPANY NAME PTE LTD</span>
              </p>
              <p>
                <span className="font-semibold">PREMISES : </span>
                <span className="bg-yellow-100 px-1 rounded">XX YOUR STREET SINGAPORE XXXXXX</span>
              </p>
            </div>

            <p>We refer to your request for the opening of utilities account.</p>

            <p>
              If you have applied for water supply, please engage a licensed plumber for the piping works
              and submit all relevant documents to PUB for approval...
            </p>

            <p>
              For installation of Gas meter (if applicable), please contact City Energy Pte. Ltd. at
              1800-555 1661 to make an appointment...
            </p>

            <p>
              If you require clarification, please contact our customer service officers at 1800-2222 333.
            </p>

            <div className="pt-2 border-t border-gray-100">
              <p>Yours sincerely,</p>
              <p className="font-medium">Customer Services</p>
            </div>

            <p className="text-xs text-gray-400 italic">
              This is a computer generated email from SP Services Ltd
            </p>
          </div>
        </div>
      </div>

      {/* ── Sample 2: Account Opening Confirmation ── */}
      <div>
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
          Sample 2 — Account Opening Confirmation
        </p>
        <div className="border border-gray-200 rounded-lg overflow-hidden bg-white text-sm">
          {/* Email header */}
          <div className="bg-gray-800 text-white px-4 py-3">
            <p className="text-base font-bold">SP Services Ltd</p>
          </div>
          {/* Email body */}
          <div className="px-4 py-4 space-y-3 text-gray-700 leading-relaxed">
            <p>
              Dear <span className="bg-yellow-100 px-1 rounded">YOUR COMPANY NAME PTE LTD</span>
            </p>

            <div className="space-y-0.5">
              <div className="flex gap-6">
                <span className="font-semibold w-40 flex-shrink-0">ACCOUNT NUMBER</span>
                <span>
                  : <span className="bg-yellow-100 px-1 rounded">12XXXXXXXX</span>
                </span>
              </div>
              <div className="flex gap-6">
                <span className="font-semibold w-40 flex-shrink-0">PREMISES</span>
                <span>
                  : <span className="bg-yellow-100 px-1 rounded">XX YOUR STREET SINGAPORE XXXXXX</span>
                </span>
              </div>
            </div>

            <p>
              Your application to open a utilities account at the above mentioned premises has been
              successful.
            </p>

            <p>Your scheduled appointment is as follows:-</p>

            {/* Appointment table */}
            <div className="border border-gray-300 rounded overflow-hidden">
              <table className="w-full text-xs">
                <thead>
                  <tr className="bg-gray-100">
                    <th className="py-1.5 px-2 text-left font-semibold">SUPPLY</th>
                    <th className="py-1.5 px-2 text-left font-semibold">DEVICE NO.</th>
                    <th className="py-1.5 px-2 text-left font-semibold">SERVICE</th>
                    <th className="py-1.5 px-2 text-left font-semibold">DATE</th>
                    <th className="py-1.5 px-2 text-left font-semibold">TIME</th>
                  </tr>
                </thead>
                <tbody>
                  <tr className="border-t border-gray-200">
                    <td className="py-1.5 px-2">Electricity</td>
                    <td className="py-1.5 px-2 bg-yellow-50">RCXXXXXXX</td>
                    <td className="py-1.5 px-2">Turn-on</td>
                    <td className="py-1.5 px-2 bg-yellow-50">DD.MM.YYYY</td>
                    <td className="py-1.5 px-2 bg-yellow-50">HH:MM to HH:MM</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <p>
              Before the appointment, please engage a Licensed Electrical Worker (LEW) to take charge of
              your electrical installation and obtain an Electrical Installation Licence (EIL) from the
              Energy Market Authority (EMA) of Singapore...
            </p>

            <p>
              Please arrange for your LEW to be present for the appointment. Our technician may arrive
              anytime within the given time slot...
            </p>

            <div className="pt-2 border-t border-gray-100">
              <p className="text-xs text-gray-400 italic">
                This is a computer generated email from SP Services Ltd
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* Guide note */}
      <div className="flex items-start gap-2 p-3 bg-amber-50 border border-amber-200 rounded-lg">
        <span className="text-base mt-0.5">💡</span>
        <p className="text-xs text-amber-800">
          <span className="font-semibold">Highlighted fields</span> (in yellow) represent your personal
          information. The actual email you receive will contain your real account number, company name,
          premises address, and appointment details.
        </p>
      </div>
    </div>
  );
}
