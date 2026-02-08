import { forwardRef, type TextareaHTMLAttributes } from 'react';

interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string;
  error?: string;
  hint?: string;
  required?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ label, error, hint, required, id, className = '', ...props }, ref) => {
    const textareaId = id || props.name;

    return (
      <div>
        {label && (
          <label htmlFor={textareaId} className="block text-sm font-medium text-gray-700 mb-1.5">
            {label}
            {required && <span className="text-error-500 ml-0.5">*</span>}
          </label>
        )}
        <textarea
          ref={ref}
          id={textareaId}
          className={`w-full px-4 py-2.5 border rounded-lg text-sm placeholder:text-gray-400 transition-colors focus:outline-none focus:ring-2 resize-y min-h-[100px] ${
            error
              ? 'border-error-500 focus:ring-error/20 focus:border-error-500'
              : 'border-gray-300 focus:ring-primary/20 focus:border-primary'
          } ${className}`}
          {...props}
        />
        {error && <p className="mt-1 text-xs text-error-600">{error}</p>}
        {hint && !error && <p className="mt-1 text-xs text-gray-500">{hint}</p>}
      </div>
    );
  }
);

Textarea.displayName = 'Textarea';
