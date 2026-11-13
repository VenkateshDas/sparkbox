# Save to Obsidian

> Historical: the original share-target design (version 1.0). The capture path it describes still exists but has grown; see [architecture.md](architecture.md).

Approved scope: remove Automate's flow chooser by providing one Android share target named Save to Obsidian. Alternatives were retaining the tested Automate flow (extra tap) or automating taps in its chooser (fragile and needs accessibility access).

Receive shared text, extract its first HTTP(S) URL, record the local capture time, prefer a supplied subject, otherwise fetch a bounded webpage response and extract its title. Decode HTML entities and normalize whitespace. If fetching fails, save the URL as the title. Append the same two-line Markdown format to Sources/Inbox.md in Tiny experiments using Obsidian's built-in new URI with append=true. Obsidian opens and GSync remains responsible for syncing.

No server, analytics, account, file permission, accessibility permission, or third-party libraries. Only INTERNET is declared. A small visible activity handles the request and displays failures without claiming a save was confirmed. A recreated activity must not resend a dispatched append.

Validation: runnable JVM checks for extraction, title parsing, fallback, timestamp format and content formatting; Android compilation, manifest inspection, signature verification. Actual Android sharesheet and Obsidian delivery require a phone test because no device is connected.

## Background capture extension (2026-11-04)

User requested saving without launching Obsidian plus a Source added island acknowledgment. Replace URI delivery with append-only access to the single user-selected Inbox.md through Android's Storage Access Framework. Persist pending captures privately before work, use a short foreground service, verify old bytes plus appended bytes before confirming, and retain uncertain writes for inspection rather than retrying. Setup and share activities are separate so the share receiver returns to the originating app. Request promoted progress notifications on Android 16+; enable Live Alerts on OnePlus. Display a brief completed progress acknowledgment, then remove the task notification. GSync remains foreground-dependent; local captures sync when Obsidian next opens. No Drive tokens, all-files access, or accessibility service are introduced.

## Sparkbox 1.2: inbox format, titles, home screen (2026-11-04)

Replaces the entry format with the vault's Telegram style (`- 🔗 Title`, bare URL, `Captured: YYYY-MM-DD HH:mm · via Sparkbox`) so the vault agent parses one shape. Shares with no link become `- 📝` text entries. URLs lose tracking parameters; titles prefer `og:title` and YouTube oEmbed; generic titles are treated as missing. A whole-line URL match skips duplicates, which also makes retrying an interrupted write safe, so failed saves keep a Retry action instead of requiring manual inspection. Exactly one blank line separates entries. `SetupActivity` becomes the home screen (setup checklist, needs-attention list, recent captures, dark mode); `ShareActivity` only queues. Deliberately not built: a Why note (conflicts with append-only verification), file shares, multiple destinations.

## Edit and delete from the app (2026-11-04)

Reverses two earlier decisions: no Why note, and strictly append-only. The home screen now lists entries parsed from the inbox file itself (the file is the source of truth, so Obsidian deletions show on next open) and offers Why/Ideas notes and delete. Both rewrite the file, so `CaptureService.edit` is the single truncating write: private pre-edit copy (`inbox.bak`), compare-before-write against the bytes read, read-back verification, restore on failure, one lock shared with appends. Entries are identified by their exact text; if it changed meanwhile the edit is refused. The old recent-captures log is removed because the file replaces it. Known gap: Obsidian edits between the compare and the write are not detectable; the window is milliseconds.


## 1.2.1 stabilization (2026-11-05)

Supersedes the earlier claims about safe retry and automatic restore. Captures retain full text within an explicit 1 MiB UTF-8 limit. Shared payloads preserve captions and additional URLs. Notes expose a complete body. Only supported capture blocks outside YAML/fences are parsed. Mutations use exact snapshot and occurrence position; ambiguous raw-only selection is refused.

Editing retains a destination-scoped original/attempted journal before any truncating write. Snapshot checks and read-back verification reduce risk but cannot make SAF transactional across apps. Unverified writes retain recovery evidence and block subsequent writes. No automatic rollback can overwrite newer external edits. Recovery exports and explicit resolution live in the app. Old backup files stay exportable.

Interrupted appends remain uncertain until the complete rendered entry is found. Retry never appends an uncertain capture again. Other queued appends wait for uncertain/unreadable captures to be resolved. Failed pre-write jobs can retry normally. Queue order is creation time, processing has a two-minute budget, and timeout stops the service while preserving jobs. The immediate foreground notification and separate share activity remain intact.

This release fixes correctness, recovery, existing UI accessibility and misleading labels. Search, title correction, new review flows, quick notes, new widget actions and independent sync remain outside stabilization scope.


## 1.2.2: inbox polish and Settings (2026-11-05)

Audit: the earlier-backup card displaced the first capture, a full storage path dominated the header, and file/notification actions followed every inbox row. The existing neutral palette and annotation rail were already appropriate; this is a focused polish pass.

- Product: a personal link-and-thought inbox feeding a Markdown vault.
- Character: quiet, considered, readable.
- World: a notebook's contents, with maintenance kept out of the reading flow.
- Moments: saved titles and the user's annotation preview carry the emphasis.
- Signature: the existing thin green rail identifies entries with personal context.

Keep the graphite palette (#141415 / #19191B / #232325, text #EDEDED, accent #4CC38A in dark mode), system sans, 4dp spacing and 48dp touch targets. Use 16sp for the wordmark, 32sp for Inbox, 17sp medium for capture titles and 12–14sp for supporting text. No decorative assets or entrance animations are needed. This direction follows the user's request to move maintenance into Settings and retain a clear front page.

A Settings page inside the existing Activity reuses the original file, notification and recovery actions. It keeps separate scroll positions for Settings and Inbox. Back and state restoration preserve the current screen and selected filter. A compact inbox notice links to Settings when unresolved jobs or edit journals exist; historical backups alone produce no notice. First-time file setup stays on the inbox because it is required to begin capturing.

Design process reference: [unslop by Damir Sokolovsky (@heydamir)](https://heydamir.design/unslop), CC BY 4.0. No skill text or assets are bundled with the app.


## 1.3.0: smart tags, search, tag filters (2026-11-05)

Opt-in enrichment: with an OpenRouter key saved in Settings (sealed by an Android Keystore key, never in backups), each visit tags up to 40 untagged links, newest first, refreshing the list after each. The page text (capped ~3k tokens), title and URL go to the chosen model, which returns type, tags and a one-line summary. Output is forced into a whitelisted type, slug tags and one clean line (`Enrich.clean`), so page content cannot inject entries. Results are written as `Type:`, `Tags:`, `TL;DR:` lines through the same verified edit as annotations; the `Type:` line marks an entry as done. Capture is untouched and never waits on the model. No in-app spend cap: each visit is limited to 40 calls, and a credit limit on the OpenRouter key is the real stop. Provider errors stop the run quietly and the next visit retries. Search is an in-memory word match over title, link, notes, summary and tags; tag chips filter by tag. Not built: tagging right after a share, per-model pricing, notes enrichment.


## 1.4.0: background tagging and semantic search (2026-11-05)

`BackgroundJob` (JobScheduler, network constraint, no persistence) runs `Tagger`: tag untagged links, then embed anything without a vector. It is scheduled on app open and after each saved capture, so tagging no longer depends on the app staying in front. Embeddings use `openai/text-embedding-3-large` at 1024 dimensions, chosen by testing five OpenRouter models on this inbox (retrieval of an entry from the user's own Why/Ideas words: MRR 0.84 versus 0.76 for the 0.6B Perplexity model and 0.36 for Qwen3-8B, which was queried without its instruction prefix; six queries only, so treat differences as small). 1024 dimensions scored the same as 3072 and stores 4 KB per capture.

Vectors live in a private sidecar (`index.bin`, unit vectors keyed by a hash of the embedded text), never in the shared inbox. Edited or deleted entries re-embed or drop out automatically; a different model or dimension size discards the file. Search embeds the query and ranks by cosine with a 0.28 floor (unrelated queries scored under 0.2, relevant 0.3 to 0.6), falling back to the word match when there is no key, network or index. The entry editor lists up to three related captures (floor 0.45), computed offline. Limits: the index loads fully into memory (about 40 MB at 10,000 captures), and Related reads it on a worker thread each time an entry opens.


## 1.5.0: quick notes, voice, photos and documents (2026-11-05)

`QuickActivity` is one sheet for typing or recording, opened from two widget buttons, two Quick Settings tiles and two launcher shortcuts. Typed notes use the existing text job. A draft survives leaving the sheet.

Voice records 16 kHz mono AAC (`.m4a`, about 240 KB a minute, five minute cap) from the visible activity, so the Android 14 microphone rule is met, and a clip is saved if the screen is left mid-recording. The clip is queued privately as a `voice` job. `Tagger` transcribes it with OpenRouter (`openai/whisper-large-v3`), builds an ordinary `📝` note (`via Sparkbox voice`), appends it through the verified edit path (a repeat after a crash finds the entry and does nothing), then deletes the audio. Capture does not wait on the network; `BackgroundJob` retries with backoff while a clip is waiting. Audio shared from other apps (WhatsApp voice notes included) takes the same path. A refused file keeps its error on the job and shows under Needs attention.

Photos and documents need a folder grant, because the app only holds the single inbox file. Settings asks once for a vault folder. A share copies the bytes into private storage at once (the sender's access ends with the share screen), then the `file` job copies them into the folder under a deterministic name, checks the size, and appends `![[name]]` as a `🖼` or `📎` entry. A retry reuses an identical earlier copy. Photos and PDFs are described by a vision model only when "Describe photos and PDFs" is switched on, because they may contain private material; photos are shrunk to 1024 px first and PDFs go through OpenRouter's free parser. Notes of 40 characters or more are tagged like links (types question, idea, todo, note).

Not built: recording with the screen locked, audio retention, clips over five minutes, an in-app camera, thumbnails, text extraction from Office files.


## 1.5.0 interface pass: capture sheet and widgets (2026-11-06)

Direction: quiet, exact, a little warm; an index card on a desk at night. The two moments are the live waveform while speaking and the 14-day chart in the inbox widget. The existing thin green rail is the signature and now marks an active recording.

The capture sheet is a bottom sheet in the thumb zone, over a dimmed home screen (the theme disables the system starting window so the home screen shows through). A Write and Speak switch replaces two separate screens. Save stays quiet until there is text, then becomes the single filled control. Speak shows a large tabular timer and a scrolling waveform that fades toward the past, driven by the recorder's real loudness.

Recording moved into `RecordService`, a foreground service of type microphone, so one recording is shared by the sheet, the widget, the Voice tile and a notification with a Stop action. It no longer stops when the screen is left, which lifts the earlier "no recording with the screen locked" limit. A start that cannot get the microphone leaves foreground cleanly instead of crashing.

Two widgets replace one. Capture is two separate tiles, Write (filled) and Speak. While recording it becomes a strip with a pulsing dot, a ticking timer, a moving waveform and a Stop button; the animation is a `ViewFlipper` and a `Chronometer`, the only moving parts RemoteViews allows, so it runs without waking the app. Inbox is responsive: the launcher picks a micro, compact, chart or chart-with-latest layout from the widget's size; small layouts are sized in dp so large font scaling cannot clip them. Widget layouts may only use a short list of view classes, so plain `View` is not used. Settings has buttons that ask Android to pin either widget.


## 1.5.0 one widget (2026-11-06)

Capture and Inbox are now one widget, Sparkbox, so there is a single thing to place. `InboxWidget` keeps its provider (an already placed copy upgrades in place) and the separate Capture widget is gone. It is responsive: from two columns it is capture only (Write and Speak as two tiles, an icon over a label, with no numbers); one row lays the same two tiles side by side; two rows add the count, the stats and the 14-day chart and 48 dp Write and Speak tiles; three rows add a Latest list with the green rail on the newest. Recording turns the action row into the live strip (timer, moving waveform, Stop) in the same card.

Shape follows the platform: the card uses `system_app_widget_background_radius` and the tiles `system_app_widget_inner_radius` (8 dp smaller) on Android 12 and up, with 24 and 16 dp before that, so it sits with the launcher's own widgets. Depth comes from a small tone shift inside one neutral (top to bottom of the card) and a hairline, not from colour. The chart sits on a hairline with today in the accent and its count above it. Text is sized in dp so font scaling cannot clip it in a card whose size is fixed by the grid.

The two-column size has its own compact recording strip (pulsing dot, timer, Stop) because the waveform does not fit in about 135 dp. Tiles in the small sizes sit 8 dp inside the card, which is what makes the system inner radius correct for them.

The two-column and one-row sizes take their form from the Quick note widget the user already runs: one card split by a hairline, two large duotone icons (note with a plus badge, microphone with an "A" for speech to text), no numbers. Sparkbox keeps its graphite surface and uses the green accent for the badges, so the card reads as the same family as the large widget. The one-row size adds a short label beside each icon.
