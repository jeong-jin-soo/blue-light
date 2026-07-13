import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { initAttribution } from './utils/attribution'

// 렌더 전에 광고 유입(UTM) first-touch 캡처 — WhatsApp 링크 빌드 시 동기 참조 가능하도록
initAttribution()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
