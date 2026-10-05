// `vitest/config` rather than `vite`: it is the same `defineConfig` widened to
// accept the `test` block below. Importing from `vite` type-errors on `test`, and
// the usual workaround of casting the whole config away would take every other
// option in this file out of the type checker's reach too.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * Vite configuration for the admin dashboard.
 *
 * Browser calls are same-origin in every deployed environment. The development
 * server mirrors that contract by proxying gateway paths to localhost:8080
 * without rewriting them, so the immutable production bundle never contains an
 * environment-specific public API origin.
 *
 * Cross-origin gateway policy is covered independently; the operator dashboard
 * deliberately exercises the production same-origin route here.
 */
export default defineConfig({
  plugins: [react()],

  server: {
    port: 5173,
    // Loopback only, matching every other service in docker-compose. A dashboard
    // that lists every payment on the platform should not be reachable from the
    // network just because a dev server was left running.
    host: '127.0.0.1',
    strictPort: true,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/.well-known': { target: 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },

  build: {
    outDir: 'dist',
    // Fail the build rather than shipping an unreadable stack trace to a browser
    // console. Source maps make a production error diagnosable, and this bundle
    // contains no secrets to protect — every credential lives server-side.
    sourcemap: true,
  },

  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
