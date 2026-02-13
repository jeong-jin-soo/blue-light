import { Link } from 'react-router-dom';

export default function Footer() {
  return (
    <footer className="border-t border-gray-200 bg-gray-50 px-4 lg:px-6 py-4">
      <div className="flex flex-col sm:flex-row items-center justify-between gap-2 text-xs text-gray-400">
        <span>&copy; {new Date().getFullYear()} LicenseKaki. All rights reserved.</span>
        <div className="flex items-center gap-3">
          <Link to="/disclaimer" className="hover:text-gray-600 transition-colors">Disclaimer</Link>
          <span>·</span>
          <Link to="/privacy" className="hover:text-gray-600 transition-colors">Privacy Policy</Link>
        </div>
      </div>
    </footer>
  );
}
