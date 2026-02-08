interface Step {
  label: string;
  description?: string;
}

interface StepTrackerProps {
  steps: Step[];
  currentStep: number; // 0-indexed
  variant?: 'horizontal' | 'vertical';
  className?: string;
}

export function StepTracker({
  steps,
  currentStep,
  variant = 'horizontal',
  className = '',
}: StepTrackerProps) {
  if (variant === 'vertical') {
    return <VerticalSteps steps={steps} currentStep={currentStep} className={className} />;
  }
  return <HorizontalSteps steps={steps} currentStep={currentStep} className={className} />;
}

function HorizontalSteps({
  steps,
  currentStep,
  className,
}: {
  steps: Step[];
  currentStep: number;
  className: string;
}) {
  return (
    <div className={`flex items-center ${className}`}>
      {steps.map((step, idx) => {
        const status = idx < currentStep ? 'completed' : idx === currentStep ? 'current' : 'upcoming';

        return (
          <div key={idx} className="flex items-center flex-1 last:flex-none">
            {/* Step circle + label */}
            <div className="flex flex-col items-center">
              <div
                className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium border-2 transition-colors ${
                  status === 'completed'
                    ? 'bg-primary border-primary text-white'
                    : status === 'current'
                    ? 'bg-white border-primary text-primary'
                    : 'bg-white border-gray-300 text-gray-400'
                }`}
              >
                {status === 'completed' ? (
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M5 13l4 4L19 7" />
                  </svg>
                ) : (
                  idx + 1
                )}
              </div>
              <span
                className={`mt-2 text-xs font-medium text-center max-w-[80px] ${
                  status === 'current' ? 'text-primary' : status === 'completed' ? 'text-gray-700' : 'text-gray-400'
                }`}
              >
                {step.label}
              </span>
            </div>

            {/* Connector line */}
            {idx < steps.length - 1 && (
              <div
                className={`flex-1 h-0.5 mx-2 mt-[-1.25rem] ${
                  idx < currentStep ? 'bg-primary' : 'bg-gray-200'
                }`}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

function VerticalSteps({
  steps,
  currentStep,
  className,
}: {
  steps: Step[];
  currentStep: number;
  className: string;
}) {
  return (
    <div className={`space-y-0 ${className}`}>
      {steps.map((step, idx) => {
        const status = idx < currentStep ? 'completed' : idx === currentStep ? 'current' : 'upcoming';

        return (
          <div key={idx} className="flex">
            {/* Line + circle column */}
            <div className="flex flex-col items-center mr-4">
              <div
                className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium border-2 transition-colors flex-shrink-0 ${
                  status === 'completed'
                    ? 'bg-primary border-primary text-white'
                    : status === 'current'
                    ? 'bg-white border-primary text-primary'
                    : 'bg-white border-gray-300 text-gray-400'
                }`}
              >
                {status === 'completed' ? (
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M5 13l4 4L19 7" />
                  </svg>
                ) : (
                  idx + 1
                )}
              </div>
              {idx < steps.length - 1 && (
                <div
                  className={`w-0.5 flex-1 my-1 ${
                    idx < currentStep ? 'bg-primary' : 'bg-gray-200'
                  }`}
                />
              )}
            </div>

            {/* Label + description */}
            <div className="pb-6">
              <span
                className={`text-sm font-medium ${
                  status === 'current' ? 'text-primary' : status === 'completed' ? 'text-gray-700' : 'text-gray-400'
                }`}
              >
                {step.label}
              </span>
              {step.description && (
                <p className="text-xs text-gray-500 mt-0.5">{step.description}</p>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
