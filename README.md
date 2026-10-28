# Idea Radar

Collects app-demand signals daily on your server, exports a weekly JSON, and Claude Cowork turns it into a ranked report.

```
CasaOS server (24/7)                          Laptop
┌──────────────────────────────┐              ┌──────────────────────────┐
│ collector (Ktor) ─ daily ──► │  exports/ ──►│ Syncthing ─► Cowork      │
│   Google suggest, Trends,    │   (sync)     │  weekly prompt ─►        │
│   News, Reddit, HN, Play ◄─ sidecar         │  reports/2026-W44.md     │
│ MongoDB (no exposed port)    │              └──────────────────────────┘
└──────────────────────────────┘
```

## 1. Deploy (once, ~30 min)
```bash
scp -r idea-radar you@server:/DATA/AppData/   # or any path
ssh you@server
cd /DATA/AppData/idea-radar
cp .env.example .env && nano .env              # set a long MONGO_PASSWORD
nano config/seeds.json                          # your seeds, competitors, subreddits
docker compose up -d --build
docker compose logs -f collector                # first run starts immediately (takes a while: throttled)
curl -X POST http://localhost:8090/run          # manual run any time (409 if one is already running)
curl http://localhost:8090/status               # last run: per-collector counts, errors
```
`seeds.json` is re-read on every run, so edit it without restarting. A broken `seeds.json` fails that run only (see `/status`); the schedule keeps going.

Restarting the container doesn't trigger an extra run: if `exports/radar-latest.json` is younger than `RUN_EVERY_HOURS`, the collector waits out the rest. This keeps `seenCount` meaning "days observed". Use `POST /run` to force one.

### Reddit (recommended)
Reddit blocks most unauthenticated requests (403). To enable it:
1. Logged in, open reddit.com/prefs/apps → **create app** → type **script**, redirect uri `http://localhost`.
2. Put the client id (under the app name) and secret in `.env` as `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`, plus `REDDIT_USERNAME`.
3. `docker compose up -d` and check `curl localhost:8090/status` shows `"redditOAuth":true`.

Without credentials the collector tries the public JSON once per run and skips Reddit on the first 403/429.

### Reddit via Brave Search (no Reddit approval needed)
`reddit_search` finds recent Reddit posts for each `redditQueries` phrase through the official Brave Search API (`site:reddit.com`, past month).
1. Sign up at api-dashboard.search.brave.com, pick the **Search** plan (includes free monthly credit; a card is required for abuse prevention).
2. Create an API key, put it in `.env` as `BRAVE_API_KEY`, `docker compose up -d`.
3. `curl localhost:8090/status` shows `"braveSearch":true`. ~4 searches/day ≈ 120/month.

### Hacker News (always on, no key)
`hackernews` searches Ask HN posts, stories and comments from the last 30 days for exact phrases like "is there an app". Override the phrases with `hnQueries` in `seeds.json`. Expect a handful of hits per week, mostly developer tools.

**MongoDB note:** port 27017 is deliberately not published. Use `ssh -L 27017:...` or `docker compose exec mongo mongosh` if you want to look inside.
If `mongo:7` exits immediately on an older CPU (no AVX), switch the image to `mongo:4.4`.

## 2. Sync exports to the laptop
Install **Syncthing** from the CasaOS App Store, share `/DATA/AppData/idea-radar/exports`, and accept it on the laptop as `~/idea-radar/exports`. Create `~/idea-radar/reports/` next to it.

## 3. Weekly routine (~45 min)
1. Open Claude Cowork, give it the `~/idea-radar` folder, paste `cowork/WEEKLY_ANALYSIS_PROMPT.md`.
   (If your Cowork supports scheduled tasks, schedule it weekly instead.)
2. Read the report, shortlist 1–3 ideas.
3. Update `seeds.json` with the suggested seeds/competitors; remove dead ones.

## 4. Validate before building (per idea, a few days)
Landing page or minimal Play listing → measure interest → build only what pulls.

## Tuning
- `REQUEST_DELAY_MS` (default 2500): raise it if you see 429s in logs.
- Seed count drives runtime: ~18 seeds × 2 regions ≈ 1–2 h per run with the default delay. That's fine for a daily job.
- Reddit: see the Reddit section above (OAuth via `.env`).
- `COLLECTORS=news,play` limits a run to those collectors (names: `trends_daily, google_suggest, play, reddit, reddit_search, hackernews, news`).
- Google endpoints used (Trends RSS, autocomplete, News RSS) are public but unofficial; expect occasional format changes. Each collector fails independently, so one breaking doesn't stop the run.

## Local development (no Docker)
```bash
cd play-sidecar && npm install && node server.js          # terminal 1
cd collector && gradle buildFatJar                         # JDK 21
MONGO_URI=memory CONFIG_PATH=../config/seeds.json EXPORT_DIR=../exports \
  java -jar build/libs/idea-radar.jar --once               # one run, writes exports/, exits
```
`MONGO_URI=memory` keeps signals in RAM (no MongoDB needed; `seenCount` resets each start). Drop `--once` to run the HTTP server and daily schedule. `PORT` changes the server port (default 8080).

## Data model (`radar.signals`)
`_id, source, region, seed, title, text, url, tags[], metrics{}, firstSeen, lastSeen, seenCount`
`seenCount` rising week over week is the strongest "persistent demand" signal.
