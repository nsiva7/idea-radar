// Tiny HTTP wrapper around google-play-scraper so the Kotlin collector can query Play.
// Endpoints (all GET, JSON): /suggest  /search  /app  /reviews  /health
import http from 'node:http';
import gplay from 'google-play-scraper';

const REQUEST_TIMEOUT_MS = Number(process.env.REQUEST_TIMEOUT_MS ?? 45_000); // answer before the collector's 60s
const DETAIL_CONCURRENCY = 3; // app detail pages fetched in parallel per search

const pick = (a) => ({
  appId: a.appId, title: a.title, summary: a.summary, developer: a.developer,
  url: a.url, score: a.score ?? null, ratings: a.ratings ?? null,
  minInstalls: a.minInstalls ?? null, free: a.free, updated: a.updated ?? null,
  genre: a.genre ?? null, adSupported: a.adSupported ?? null,
});

/** Runs fn over items with limited concurrency; failures resolve to null instead of rejecting. */
async function mapLimit(items, limit, fn) {
  const out = new Array(items.length).fill(null);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const i = next++;
      try { out[i] = await fn(items[i]); } catch (e) { console.error('detail', items[i]?.appId, e.message); }
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

const routes = {
  '/health': async () => ({ ok: true }),

  // Google returns null when there are no suggestions; the library then throws. Treat as "none".
  '/suggest': async (q) => {
    try {
      return await gplay.suggest({ term: q.term, country: q.country, lang: q.lang });
    } catch (e) {
      if (/reading 'map'/.test(e.message)) return [];
      throw e;
    }
  },

  // Fast search (one page), then detail pages in parallel. fullDetail=true fetched them one by one
  // under the library throttle and took 30-60s per term; this takes ~2s. One bad app no longer fails the search.
  '/search': async (q) => {
    const num = Math.min(Number(q.num ?? 8), 20);
    const basic = (await gplay.search({ term: q.term, country: q.country, lang: q.lang, num, fullDetail: false })).slice(0, num);
    if (q.full === 'false') return basic.map(pick);
    const details = await mapLimit(basic, DETAIL_CONCURRENCY,
      (a) => gplay.app({ appId: a.appId, country: q.country, lang: q.lang }));
    return basic.map((a, i) => pick(details[i] ?? a));
  },

  '/app': async (q) => pick(await gplay.app({ appId: q.appId, country: q.country, lang: q.lang })),

  '/reviews': async (q) => {
    const res = await gplay.reviews({
      appId: q.appId, country: q.country, lang: q.lang, sort: gplay.sort.NEWEST,
      num: Math.min(Number(q.num ?? 100), 300),
    });
    const list = Array.isArray(res) ? res : (res.data ?? []);
    return list.map((r) => ({
      id: r.id, score: r.score, text: r.text, date: r.date, thumbsUp: r.thumbsUp ?? 0, version: r.version ?? null,
    }));
  },
};

const withTimeout = (p, ms) => Promise.race([
  p,
  new Promise((_, reject) => setTimeout(() => reject(Object.assign(new Error(`timed out after ${ms} ms`), { status: 504 })), ms)),
]);

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const handler = routes[url.pathname];
  if (!handler) { res.writeHead(404).end('{"error":"not found"}'); return; }
  const q = Object.fromEntries(url.searchParams);
  q.country ??= 'in'; q.lang ??= 'en';
  const started = Date.now();
  try {
    const body = JSON.stringify(await withTimeout(handler(q), REQUEST_TIMEOUT_MS));
    res.writeHead(200, { 'content-type': 'application/json' }).end(body);
  } catch (e) {
    // 404 = app doesn't exist (wrong appId in seeds.json); 504 = too slow; 502 = Play/parse error
    const status = e.status ?? (/\(404\)/.test(e.message) ? 404 : 502);
    console.error(url.pathname, q, status, e.message, `${Date.now() - started}ms`);
    if (!res.headersSent) res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify({ error: e.message }));
  }
}).listen(3000, () => console.log('play-sidecar on :3000'));
