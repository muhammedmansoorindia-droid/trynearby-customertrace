/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    /**
     * Bind to every interface so a phone on the same Wi-Fi can reach the dev server.
     *
     * `true` means 0.0.0.0 (all IPv4 interfaces). This is a literal, not this machine's LAN
     * address — the address belongs in .env, which is git-ignored, so no developer's IP is
     * ever committed.
     */
    host: true,
    port: 5173,
    // Fails loudly instead of silently hopping to :5174, which would not match the CORS
    // allow-list in .env and would break the phone with a confusing CORS error.
    strictPort: true,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});