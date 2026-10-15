/** Selected Pi Responses adapter. Tools and their execution stay inside Android. */
import type { StreamFn } from '@earendil-works/pi-agent-core';
import type { AssistantMessage, Model } from '@earendil-works/pi-ai';
import { streamSimple } from '@earendil-works/pi-ai/api/openai-responses';
import { AssistantMessageEventStream } from '@earendil-works/pi-ai/utils/event-stream';
import { SCRIPT_MODEL, scriptedStream } from './scripted.js';

export interface ProviderConfig {
  mode?: 'scripted' | 'openai' | 'codex';
  endpoint?: string;
  model?: string;
}
export interface ProviderCredentials {
  endpoint?: string;
  model?: string;
  apiKey?: string;
  authMode?: string;
}

function isRelay(config: ProviderConfig, credentials: ProviderCredentials): boolean {
  return ['codex', 'codexRelay', 'codex-relay'].includes(credentials.authMode || config.mode || '');
}

export function modelFor(config: ProviderConfig, credentials: ProviderCredentials = {}): Model<'openai-responses'> {
  if (config.mode === 'scripted') return SCRIPT_MODEL;
  const relay = isRelay(config, credentials);
  const endpoint = credentials.endpoint || config.endpoint || (relay ? 'http://10.0.2.2:8787/v1' : 'https://api.openai.com/v1');
  const url = new URL(endpoint);
  if (url.username || url.password || url.search || url.hash) throw new Error('Provider endpoint must be a base URL without credentials, query or fragment.');
  if (relay) {
    if (url.protocol !== 'http:' || !['10.0.2.2', '127.0.0.1', 'localhost'].includes(url.hostname)) {
      throw new Error('Codex relay is restricted to the Android emulator host or loopback. Native ChatGPT login is not implemented.');
    }
  } else if (url.protocol !== 'https:') {
    throw new Error('Direct API providers require HTTPS.');
  }
  const id = credentials.model || config.model || 'gpt-6-sol';
  if (!/^[a-zA-Z0-9._:-]{1,120}$/.test(id)) throw new Error('Invalid model identifier.');
  return {
    id, name: id, provider: 'openai', api: 'openai-responses',
    baseUrl: url.toString().replace(/\/$/, ''), input: ['text', 'image'],
    reasoning: true, contextWindow: 128000, maxTokens: 4096,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    // Pi uses this flag to *emit* `strict:false`, retaining optional action fields.
    // Omitting strict lets Responses normalize them as mandatory strict properties.
    compat: { supportsMaxOutputTokens: !relay, supportsStrictMode: true },
  };
}

function failure(model: Model<'openai-responses'> | undefined, text: string, aborted = false): AssistantMessageEventStream {
  const stream = new AssistantMessageEventStream();
  const output: AssistantMessage = {
    role: 'assistant', content: [], api: 'openai-responses', provider: 'openai', model: model?.id || 'unconfigured',
    timestamp: Date.now(), stopReason: aborted ? 'aborted' : 'error', errorMessage: text.slice(0, 512),
    usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
  };
  stream.push({ type: 'error', reason: aborted ? 'aborted' : 'error', error: output });
  stream.end(output);
  return stream;
}

export function makeStream(config: ProviderConfig, credentials: ProviderCredentials = {}, owner: any): StreamFn {
  if (config.mode === 'scripted') return scriptedStream(owner);
  return (_model, context, options) => {
    let model: Model<'openai-responses'> | undefined;
    try {
      model = modelFor(config, credentials);
      if (options?.signal?.aborted) return failure(model, 'Request cancelled.', true);
      if (!credentials.apiKey?.trim()) return failure(model, 'Configure a provider credential before sending.');
      // Zero retries: a lost response must not invisibly replay a tool sequence.
      // Pi owns SSE decoding, structured tool calls/results and AbortSignal propagation.
      const source = streamSimple(model, context, {
        ...options, apiKey: credentials.apiKey, transport: 'sse', maxRetries: 0,
        timeoutMs: 120000, reasoning: 'low',
      });
      const guarded = new AssistantMessageEventStream();
      void (async () => {
        try {
          for await (const event of source) {
            if (event.type === 'error') {
              event.error.errorMessage = (event.error.errorMessage || 'Provider request failed.')
                .replaceAll(credentials.apiKey!, '[redacted]').slice(0, 512);
            }
            guarded.push(event);
          }
          guarded.end(await source.result());
        } catch {
          const aborted = !!options?.signal?.aborted;
          const terminal = await failure(model, aborted ? 'Request cancelled.' : 'Provider stream failed; no retry was attempted.', aborted).result();
          guarded.push({ type: 'error', reason: aborted ? 'aborted' : 'error', error: terminal });
          guarded.end(terminal);
        }
      })();
      return guarded;
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Provider configuration failed.';
      return failure(model, message.replaceAll(credentials.apiKey || '\0', '[redacted]'));
    }
  };
}
