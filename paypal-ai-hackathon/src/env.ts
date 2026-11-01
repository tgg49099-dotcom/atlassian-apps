/**
 * Environment access that works in three places without any bundler magic:
 *
 *  - Node (the server, `npm start`, and the `node --test` suite): reads
 *    `process.env`;
 *  - the Vite dev server / browser build: reads `import.meta.env`. Vite only
 *    exposes variables prefixed with `VITE_`, so a `PAYPAL_SECRET` can never
 *    be shipped to the browser by accident. That is deliberate: the secret
 *    stays on the server side;
 *  - anywhere else: an empty environment, which means "demo mode".
 */
export type EnvLike = Record<string, string | undefined>;

export function readEnv(): EnvLike {
  const out: EnvLike = {};

  const g = globalThis as unknown as { process?: { env?: EnvLike } };
  const nodeEnv = g.process?.env;
  if (nodeEnv) {
    for (const [key, value] of Object.entries(nodeEnv)) out[key] = value;
  }

  const viteEnv = import.meta.env as unknown as EnvLike | undefined;
  if (viteEnv) {
    for (const [key, value] of Object.entries(viteEnv)) out[key] = value;
  }

  return out;
}

/** True when the string is present and not just whitespace. */
export function hasValue(value: string | undefined): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
