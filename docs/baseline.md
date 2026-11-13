# Baseline (Milestone 2)

Measured 2026-11-13 on the OnePlus phone (USB, `3C167D00FY000000`), build.py APK at commit after Milestone 1, vault of 646 notes, dark mode unless noted.

## Numbers

| What | Value | How |
|---|---|---|
| APK size | 462,923 bytes (452 KB) | `ls -l build/Save-to-Obsidian.apk` |
| Cold start to first frame | 237 / 302 / 296 / 293 / 298 ms (median **296 ms**) | `adb shell am start -W` after `force-stop`, TotalTime |
| Cold start to usable Feed | 1250 (first, cold cache) / 254 / 253 / 252 / 252 ms (median **253 ms**) | log `Sparkbox: feed usable N ms`, measured from process start until the first Feed rows with items are posted |
| Memory, Feed idle after start | 285 MB PSS total (Java heap 104 MB, native heap 137 MB), 426 MB RSS | `adb shell dumpsys meminfo me.venkatesh.saveobsidian` |
| Feed scroll, idle | 577 frames, **0.35 % janky**, p50 5 ms, p90 8 ms, p99 9 ms, 2 deadline misses | `dumpsys gfxinfo reset`, 12 fast swipes, `dumpsys gfxinfo` |

"Feed usable" can come out lower than "first frame": `am start -W` counts from the intent, the marker from process start, and the Feed draws from its cache on the first layout.

Memory is high for a list app: almost all of it is decoded images (heap plus native bitmaps) of the Feed cards. Worth watching in Milestone 4 (Coil with sized requests).

## Measured with real captures

Three Wikipedia links were shared into the real inbox. The vault was copied off the phone first and restored byte for byte afterwards. 11 new notes were deleted, 5 index and log files were put back, and a diff against the copy shows no change.

| What | Value |
|---|---|
| Ingest time per capture | 8.3 s, 20.9 s, 39.3 s |
| Feed scroll **while an ingest runs** | 1141 frames, **0.35 % janky**, p50 5 ms, p90 7 ms, p99 10 ms, 4 deadline misses |
| Kill during processing (gate c) | **Failed at first.** A force-stop cancels the app's scheduled jobs, and opening the app never scheduled processing again, so the capture sat in the inbox until the next share. **Fixed:** MainActivity now schedules the background job on open. After the fix, reopening resumed the capture and it finished in 39 s; the capture stayed intact in the inbox until filed. |
| Ask cancel | The answer stopped and showed "Cancelled."; with the per-request token it no longer touches background work |

## Screenshots

`docs/baseline/` holds 23 screenshots, sized to 1000 px: `{dark,light,light-font130}-{feed,spark,search,search-empty,ask,source,settings}.jpg`. `light-font130` is the system font scale at 1.3. Also `dark-feed-attention.jpg` (a capture that failed processing) and `dark-ask-stopped.jpg` (an answer cancelled mid-way).

What they show:
- Light and dark both readable; no content under the status or navigation bars.
- At font scale 1.3, Settings cuts long paths ("Documents/Obsidian/Tiny", "Knowledge/Sources/" lose their ends) and the Feed card host is ellipsised. Layout problem to fix in the Compose Settings.
- The empty-search state keeps the keyboard up and shows a stray dot at the right edge (scrollbar).

## Release gates for Milestones 3 to 6

a. Cold start to usable Feed: median of 5 warm-cache runs must stay within **+15 %** of 253 ms (so at most 291 ms), and first frame within +15 % of 296 ms (at most 340 ms).
b. Feed scroll jank while an ingest runs must stay at most 1 % janky frames and p99 at most 16 ms (baseline 0.35 %, 10 ms).
c. A captured link is never lost when the app is killed during processing: kill, restart, it resumes and the original capture is intact.

APK size is recorded, not a gate.

## Milestone 3 (Gradle 9.9.1, AGP 9.5.1, R8 release)

| What | Value | Gate |
|---|---|---|
| APK size | 298,518 bytes (292 KB, was 452 KB) | recorded |
| First frame, median of 5 | 310 ms | a: at most 340, pass |
| Usable Feed, median of 5 | 267 ms | a: at most 291, pass |
| Feed scroll, idle | 0.00 % janky, p99 8 ms | pass |

Installed over the build.py APK (versionCode 11 to 12) with the same key: vault, settings and inbox kept. Checked on the phone with the R8 build: share-sheet text capture, Quick note tile, Voice note tile (recorded, then discarded), widget provider registered. The widget itself was not placed on a home screen.

## Milestone 4 (Compose Settings pilot, Compose Feed)

| What | Value | Gate |
|---|---|---|
| APK size | 1.29 MB (Compose + Material 3) | recorded |
| First frame, median of 6 | **150 ms** (was 296) | a: pass |
| Usable Feed, median of 6 | **221 ms** (was 253) | a: pass |
| Feed scroll, idle | 0.23 % janky, p99 11 ms | b (idle): pass |

The first Compose Feed was slower (first frame 420 ms, usable 370 ms): the vault cache was read and the Feed built twice on the main thread before the first frame (build() and onShow(), about 115 ms each; the View Feed did the same). Now it is read once, on a background thread, while Compose draws the shell. Full AOT compilation (`cmd package compile -m speed`) changed little, so it was main-thread work, not JIT.

"Feed usable" is now logged when the rows are handed to Compose, one frame before they are drawn.

Not re-run in this milestone: scroll jank during an ingest and the kill test (gate c). Ingest, capture and processing code are unchanged; the Feed only reads their state.

## Milestone 5 (Source, Topic, notes list, Ask, Spark, Search in Compose)

| What | Value | Gate |
|---|---|---|
| APK size | 1.72 MB | recorded |
| First frame, median of 5 | 142 ms | a: pass |
| Usable Feed, median of 5 | 208 ms | a: pass |
| Feed scroll, idle | 0.00 % janky, p99 10 ms | b (idle): pass |

Still on Views (they use Ui.java): Profile, Cards list, Make cards, Capture map, Capture detail, the vault browser (VaultActivity), Dashboard and the Quick capture sheet, plus the card editor and capture dialogs. Ui.java stays until those move. Link preview images still come from Thumb (a View inside Compose); Coil is not added yet.

## Milestone 5 complete (every screen in Compose)

| What | Value | Gate |
|---|---|---|
| APK size | 1.75 MB | recorded |
| First frame, median of 5 | 147 ms | a: pass |
| Usable Feed, median of 5 | 219 ms | a: pass |
| Feed scroll, idle | 0.25 % janky, p99 11 ms | b (idle): pass |
| Memory, Feed idle | 133 MB PSS (baseline 285 MB) | recorded |

All screens are Compose now: Feed, Spark, Search, Ask, Settings, Profile, Source, Topic, notes lists, Cards, Make cards, capture map, Capture detail, the Quick capture sheet and the vault browser. DashboardActivity was unreachable and is deleted, as are Bars.java and the unused View helpers in Ui.java. Platform dialogs (card editor, capture, key, model, confirmations) keep their logic but take a Material 3 look from the theme.

Kept on purpose:
- Ui.java (144 lines): the palette and type scale the Compose theme reads, the main shell's bottom bar, and the text helper the Markdown reader uses.
- Md.java renders note Markdown into a TextView with tappable links; the vault reader embeds that TextView in Compose.
- Thumb.java: link previews already have a memory cache, a disk cache and 480 px downsampling, so Coil would add a dependency for nothing.
- The home-screen widget stays RemoteViews (widgets cannot use Compose without Glance).

## Milestone 6 (concurrency limits, failure types)

Start values, in `Limits.java`:
- Source fetching (`Fetch.page`): 3 at a time, shared by ingest, tagging and backfills.
- Background model calls (`Model.post` for ingest, Craft/Life, tags, cards, descriptions, embeddings): 2 at a time.
- Interactive model calls never queue behind them: Ask streams on its own, and search, vault search and Similar mark their thread interactive.
- Vault edits: the single `VaultLock` from Milestone 0.
- Vault scans: one at a time. A caller that waited for a scan which started after it asked reuses that result instead of walking the vault again.
- Backfills (tags, descriptions, card generation, embeddings) already run one after another inside the single Tagger run, so they never overlap each other.

Failure types, in `Failures.java`, each with its own recovery text and retry behaviour:

| Kind | What the owner sees | Retry |
|---|---|---|
| offline | tried again by itself when back online | every couple of minutes, no try used |
| key / credit | check the key in Settings | stops until the owner retries |
| rate limit | OpenRouter is limiting requests | waits at least 10 minutes |
| unusable answer | asks again, then the fallback model | normal ladder |
| conflicting edit | the note changed while saving; the AI answer is kept | normal ladder, no new model call |

A vault-save failure reuses the saved model answer: the plan is stored before any vault write (`plan:` in the ingest prefs), so a retry goes straight to writing, unless the Why or Ideas changed (Milestone 0).

Measured after the change: first frame 154 ms, usable Feed 216 ms (median of 5). Not measured: how the limits behave under a real ingest burst; tune them from logs (`model … ms`, `stage … ms`) once real captures run.
