import { RouterProvider } from 'react-router-dom';
import router from './router';
import { ToastProvider } from './components/ui/Toast';
import ChatWidget from './components/domain/ChatWidget';

function App() {
  return (
    <>
      <RouterProvider router={router} />
      <ToastProvider />
      <ChatWidget />
    </>
  );
}

export default App;
