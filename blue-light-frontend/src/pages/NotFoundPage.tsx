import { useNavigate } from 'react-router-dom';
import { Button } from '../components/ui/Button';
import { useAuthStore } from '../stores/authStore';

export default function NotFoundPage() {
  const navigate = useNavigate();
  const { isAuthenticated, user } = useAuthStore();

  const handleGoHome = () => {
    if (isAuthenticated && user) {
      const dest = user.role === 'SYSTEM_ADMIN' ? '/admin/system' : user.role === 'ADMIN' ? '/admin/dashboard' : user.role === 'LEW' ? '/lew/dashboard' : '/dashboard';
      navigate(dest, { replace: true });
    } else {
      navigate('/login', { replace: true });
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-surface-secondary">
      <div className="text-center px-6">
        <div className="text-6xl font-bold text-primary mb-4">404</div>
        <h1 className="text-2xl font-semibold text-gray-800 mb-2">Page Not Found</h1>
        <p className="text-gray-500 mb-8 max-w-md">
          The page you are looking for doesn't exist or has been moved.
        </p>
        <Button onClick={handleGoHome}>
          Go to {isAuthenticated ? 'Dashboard' : 'Login'}
        </Button>
      </div>
    </div>
  );
}
