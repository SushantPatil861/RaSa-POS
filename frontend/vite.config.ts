import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// Dev: the browser talks to :5173 and Vite forwards /api to the Spring Boot app on :8080,
// so the refresh-token cookie stays same-origin.
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'Valentina Café POS',
        short_name: 'Café POS',
        description: 'Sales, orders, menu and expenses for the café',
        theme_color: '#7c2d52',
        background_color: '#fdf7fa',
        display: 'standalone',
        start_url: '/',
        icons: [
          { src: 'icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any maskable' },
        ],
      },
      workbox: { navigateFallbackDenylist: [/^\/api\//] },
    }),
  ],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'node' },
})
