# Idea Radar — weekly analysis (paste into Claude Cowork)

Folder: `~/idea-radar/` (synced from the server via Syncthing)
- `exports/radar-latest.json` — last 7 days of signals
- `reports/` — previous weekly reports (read the last 2 for continuity)

## Task
You are helping me (an Android/KMP developer, 8+ yrs, 15+ Play Store apps) pick the next small app to build.
Target markets: India and global — let demand decide.

1. Load `exports/radar-latest.json`. Each signal has source, region, seed, title, text, url, tags, metrics, firstSeen, lastSeen, seenCount.
2. Cluster signals into concrete **app opportunities** (a specific user + a specific job), combining evidence across sources:
   - `google_suggest` / `play_suggest`: what people actually search (lower rank = more popular)
   - `play_search` with `weak_*` tags: incumbents that look beatable
   - `play_review_negative`: complaints that become features (ads, forced login, no offline, no regional language, bugs)
   - `reddit` / `reddit_search` / `hackernews`: explicit "is there an app…" requests (HN skews to developer tools; weight consumer requests higher)
   - `stackexchange`: software/app requests; `unanswered` tag + high `views` = unmet demand
   - `news` / `trends_daily`: new rules, deadlines, schemes, events that create demand
   - Higher `seenCount` and older `firstSeen` = persistent demand, not a spike. Weight this heavily.
3. Score each opportunity 1–5 on: demand strength, demand persistence, competitor weakness,
   MVP buildable by one dev in ≤4 weeks, monetization path (one-time unlock / subscription > ads only),
   Play policy risk (inverse: finance/loans, health claims, sensitive permissions score low),
   and India vs global fit.
4. Drop anything that's only a one-day trend, needs heavy content/licensing, or needs a large backend.

## Output
Write `reports/YYYY-Www.md` containing:
- **Top 5 opportunities**, each with: one-line pitch, target user + region, evidence (cite signal titles/urls and counts),
  scores table, MVP feature list (max 6), what to do better than incumbents, monetization idea, risks.
- **Watchlist**: ideas trending up but not ready yet (compare with previous reports).
- **Dropped since last week** and why.
- **Seed suggestions**: 5–10 new keywords or competitor appIds to add to `seeds.json`, and seeds that produced nothing useful.

Be skeptical. If the evidence for an idea is thin, say so rather than padding the list.
