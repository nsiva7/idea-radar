// Tiny HTTP wrapper around google-play-scraper so the Kotlin collector can query Play.
// Endpoints (all GET, JSON): /suggest  /search  /app  /reviews  /health
import http from 'node:http';
import gplay from 'google-play-scraper';

const THROTTLE = 2; // max requests/second to Google Play

const pick = (a) => ({
  appId: a.appId, title: a.title, summary: a.summary, developer: a.developer,
  url: a.url, score: a.score ?? null, ratings: a.ratings ?? null,
  minInstalls: a.minInstalls ?? null, free: a.free, updated: a.updated ?? null,
  genre: a.genre ?? null, adSupported: a.adSupported ?? null,
});

const routes = {
  '/health': async () => ({ ok: true }),
  '/suggest': async (q) =>
    gplay.suggest({ term: q.term, country: q.country, lang: q.lang, throttle: THROTTLE }),
  '/search': async (q) => {
    const res = await gplay.search({
      term: q.term, country: q.country, lang: q.lang,
      num: Math.min(Number(q.num ?? 8), 20), fullDetail: q.full !== 'false', throttle: THROTTLE,
    });
    return res.map(pick);
  },
  '/app': async (q) => pick(await gplay.app({ appId: q.appId, country: q.country, lang: q.lang, throttle: THROTTLE })),
  '/reviews': async (q) => {
    const res = await gplay.reviews({
      appId: q.appId, country: q.country, lang: q.lang, sort: gplay.sort.NEWEST,
      num: Math.min(Number(q.num ?? 100), 300), throttle: THROTTLE,
    });
    const list = Array.isArray(res) ? res : (res.data ?? []);
    return list.map((r) => ({
      id: r.id, score: r.score, text: r.text, date: r.date, thumbsUp: r.thumbsUp ?? 0, version: r.version ?? null,
    }));
  },
};

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const handler = routes[url.pathname];
  if (!handler) { res.writeHead(404).end('{"error":"not found"}'); return; }
  const q = Object.fromEntries(url.searchParams);
  q.country ??= 'in'; q.lang ??= 'en';
  try {
    const body = JSON.stringify(await handler(q));
    res.writeHead(200, { 'content-type': 'application/json' }).end(body);
  } catch (e) {
    console.error(url.pathname, q, e.message);
    res.writeHead(502, { 'content-type': 'application/json' }).end(JSON.stringify({ error: e.message }));
  }
}).listen(3000, () => console.log('play-sidecar on :3000'));
