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
