# Save to Obsidian

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
