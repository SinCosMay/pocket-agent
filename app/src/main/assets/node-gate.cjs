(async () => {
  const http = require('node:http');
  const crypto = require('node:crypto');
  let server;
  try {
    if (typeof fetch !== 'function' || typeof ReadableStream !== 'function' || typeof AbortController !== 'function') throw Error('Modern Web APIs unavailable');
    const hash = Buffer.from(await crypto.webcrypto.subtle.digest('SHA-256', new TextEncoder().encode('abc'))).toString('hex');
    if (hash !== 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad') throw Error('Crypto mismatch');
    const timers = new Set();
    server = http.createServer((req, res) => {
      res.writeHead(200, { 'content-type': 'text/plain' }); res.write('first\n');
      const timer = setTimeout(() => { timers.delete(timer); res.end('second\n'); }, req.url === '/abort' ? 10000 : 120);
      timers.add(timer); res.on('close', () => { clearTimeout(timer); timers.delete(timer); });
    });
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
    const base = 'http://127.0.0.1:' + server.address().port;
    const response = await fetch(base + '/stream'); const reader = response.body.getReader();
    let reads = 0, text = '';
    while (true) { const item = await reader.read(); if (item.done) break; ++reads; text += new TextDecoder().decode(item.value); }
    if (reads < 2 || text !== 'first\nsecond\n') throw Error('Fetch did not stream separate chunks');
    const controller = new AbortController();
    const pending = await fetch(base + '/abort', { signal: controller.signal });
    const body = pending.body.getReader(); await body.read(); controller.abort();
    let cancelled = false;
    try { await body.read(); } catch (error) { cancelled = error.name === 'AbortError'; }
    if (!cancelled) throw Error('Fetch cancellation failed');
    const result = { type: 'gate', ok: true, node: process.versions.node, v8: process.versions.v8, crypto: true, streamingReads: reads, cancelled, uuid: crypto.randomUUID(), memory: process.memoryUsage() };
    for (const timer of timers) clearTimeout(timer);
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); server = null;
    PocketNative.emit(JSON.stringify(result));
  } catch (error) {
    if (server) { server.closeAllConnections(); server.close(); }
    PocketNative.emit(JSON.stringify({ type: 'gate', ok: false, error: String(error.message) }));
  }
})();
