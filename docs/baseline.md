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
