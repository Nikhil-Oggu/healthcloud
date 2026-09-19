/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The SPA runs on :5173 and proxies API calls to the Spring backend on :8080. Keeping the browser
// same-origin (no changeOrigin) means the HttpOnly SESSION cookie and readable XSRF-TOKEN cookie are
// first-party and "just work" — so we need no CORS and never handle the session token in JavaScript.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: false },
      // The Cognito OIDC flow (Phase 10 slice 13): the BFF's authorization request + Cognito callback.
      // Scoped to `/login/oauth2` (NOT all of `/login`, which is the SPA's own route). changeOrigin:false
      // keeps the Host as :5173 so the session cookie stays first-party on `localhost`.
      '/oauth2': { target: 'http://localhost:8080', changeOrigin: false },
      '/login/oauth2': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: false,
  },
})
