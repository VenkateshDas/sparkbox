# Save to Obsidian

Approved scope: remove Automate's flow chooser by providing one Android share target named Save to Obsidian. Alternatives were retaining the tested Automate flow (extra tap) or automating taps in its chooser (fragile and needs accessibility access).

Receive shared text, extract its first HTTP(S) URL, record the local capture time, prefer a supplied subject, otherwise fetch a bounded webpage response and extract its title. Decode HTML entities and normalize whitespace. If fetching fails, save the URL as the title. Append the same two-line Markdown format to Sources/Inbox.md in Tiny experiments using Obsidian's built-in new URI with append=true. Obsidian opens and GSync remains responsible for syncing.

No server, analytics, account, file permission, accessibility permission, or third-party libraries. Only INTERNET is declared. A small visible activity handles the request and displays failures without claiming a save was confirmed. A recreated activity must not resend a dispatched append.

Validation: runnable JVM checks for extraction, title parsing, fallback, timestamp format and content formatting; Android compilation, manifest inspection, signature verification. Actual Android sharesheet and Obsidian delivery require a phone test because no device is connected.

## Background capture extension (2026-11-04)

User requested saving without launching Obsidian plus a Source added island acknowledgment. Replace URI delivery with append-only access to the single user-selected Inbox.md through Android's Storage Access Framework. Persist pending captures privately before work, use a short foreground service, verify old bytes plus appended bytes before confirming, and retain uncertain writes for inspection rather than retrying. Setup and share activities are separate so the share receiver returns to the originating app. Request promoted progress notifications on Android 16+; enable Live Alerts on OnePlus. Display a brief completed progress acknowledgment, then remove the task notification. GSync remains foreground-dependent; local captures sync when Obsidian next opens. No Drive tokens, all-files access, or accessibility service are introduced.
