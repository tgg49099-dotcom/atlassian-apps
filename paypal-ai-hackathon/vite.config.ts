import { defineConfig, loadEnv, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { handleApi } from './src/api.ts';

/**
 * Dev-only middleware: serves the same JSON API routes as `src/server.ts`
 * (the production server) so that `npm run dev` is a working full-stack app
 * with no second process to start.
 */
function apiPlugin(): Plugin {
  return {
    name: 'paypal-ai-hackathon-api',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        if (!req.url || !req.url.startsWith('/api/')) return next();
        const chunks: Buffer[] = [];
        for await (const chunk of req) chunks.push(chunk as Buffer);
        const body = Buffer.concat(chunks).toString('utf8');
        const result = await handleApi(new URL(req.url, 'http://localhost'), req.method ?? 'GET', body);
        if (!result) return next();
        res.statusCode = result.status;
        res.setHeader('Content-Type', result.contentType);
        res.end(result.body);
      });
    },
  };
}

export default defineConfig(({ mode }) => {
  // Load `.env` into the *server* environment only. Vite still exposes only
  // `VITE_`-prefixed variables to the browser, so PAYPAL_SECRET can never be
  // shipped to the client.
  const env = loadEnv(mode, process.cwd(), '');
  for (const [key, value] of Object.entries(env)) {
    if (process.env[key] === undefined) process.env[key] = value;
  }

  return {
    plugins: [react(), apiPlugin()],
    build: {
      outDir: 'dist',
      // Keep the build light: this container has a 1.5 GB memory cap.
      sourcemap: false,
      reportCompressedSize: false,
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks: {
            aggrid: ['ag-grid-community', 'ag-grid-react'],
          },
        },
      },
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
    },
  };
});
