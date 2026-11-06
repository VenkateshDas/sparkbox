# Sparkbox

A small personal Android app that catches links and ideas for your knowledge base. The current version saves to a local Markdown inbox used by Obsidian, without opening Obsidian.

Repository: https://github.com/VenkateshDas/sparkbox. The package ID keeps its original name (`me.venkatesh.saveobsidian`) so updates install in place.

**Share → Save to Obsidian → “Added” in the OnePlus island → continue reading.**

The app saves the link (or text), title, and local capture time. It has no server, accounts, advertising, analytics, or third-party app dependencies.

## Current behavior

Example entries appended to the selected Markdown file:

```markdown
- 🔗 Article or video title
  https://example.org/article
  Captured: 2026-11-04 22:24 · via Sparkbox

- 📝 A shared thought with no link
  Captured: 2026-11-04 22:25 · via Sparkbox
  Why: added from the app
  Ideas: more lines are indented
    four spaces
```

The format matches the Telegram captures already in the inbox, so `Process inbox` in the vault reads both. See `Agents.md` → Inbox Entry Format in the vault.

1. Receive an Android `ACTION_SEND` intent with MIME type `text/*`.
2. Extract the first HTTP(S) URL, remove tracking parameters (`utm_*`, `fbclid`, `gclid`, `igshid`, `mc_cid`, `mc_eid`, `si`, `ref_src`), and capture the phone's current local date/time. Text with no link is saved as a 📝 text entry.
3. Persist the capture in the app's private queue before starting background work.
4. Choose the title: shared subject, then text written before the link, then a fetched title (`og:title`, `twitter:title`, `<title>`; YouTube uses its oEmbed endpoint), then the URL. Generic titles such as "YouTube" or "LinkedIn" count as missing.
5. Skip an ordinary duplicate and show "Already in inbox" if a supported dated capture already contains the URL. Uncertain jobs require an exact complete entry match.
6. Append to the user-selected inbox with one blank line between entries, flush the write, and read it back to verify that all existing bytes plus the exact new entry are present.
7. Show a brief completed progress acknowledgment, then stop the foreground service. A failed save keeps the capture privately, with Retry in the notification and in Settings. An interrupted write is check-only: Retry requires the complete rendered entry, not just the URL. Partial writes require inspection/export and explicit resolution.

Obsidian is not launched. On the tested OnePlus, the notification title is **Source added** (**Already in inbox** for a duplicate) and the compact island text is **Added**. The acknowledgment clears after approximately 3.5 seconds once processing finishes. Other devices may show a regular notification rather than an island.

**Saving is local.** The existing GSync plugin uploads entries when Obsidian next opens and syncs. This app does not independently synchronize with Google Drive.

## Install and configure

Install `build/Save-to-Obsidian.apk` on the phone. The APK is personally signed, not published on Google Play.

1. Open **Sparkbox** from the launcher. The home screen asks for an inbox on first use. File, notification and recovery controls live in Settings.
2. Tap **Choose inbox file** and select the existing local inbox (any `.md` file) using Android's document picker.
3. Allow notifications.
4. On OnePlus, open the app's notification settings and enable **Show Live Updates on Live Alerts**. Enable banners if a standard notification fallback is desired.
5. Share a webpage, video, post link, or plain text and select **Sparkbox**.

The home screen lists the entries currently in the inbox file, newest first. The Inbox heading shows the saved count; filters show links, notes and annotated entries. It re-reads the file every time you open the app, so entries you delete or process in Obsidian disappear from the list. Tap an entry to read/copy the full note or original shared text and write **Why I saved this** and **My ideas** (saved as `Why:` and `Ideas:` lines under the entry) or to **Delete** it from the file; long-press copies the link. Settings contains captures that need attention (Retry/check, Copy, Export, confirmed Discard with a private retained copy), **Open in Obsidian**, and **Check inbox access**. The inbox shows a compact notice only when something needs attention.

**Editing rewrites the file.** Saving a share remains append-only. Annotation and deletion require the exact full inbox snapshot and destination observed when the editor opened. Identical entries are addressed by their position within that snapshot. Before a rewrite, an atomic private journal stores the original, attempted edit and destination. Sparkbox compares again immediately before writing and verifies afterwards. On any unverified write it keeps both copies, blocks further writes to that destination, and offers **Edit recovery** exports in Settings. It never automatically rolls back over possible Obsidian/GSync changes. After exporting and inspecting the inbox, explicitly resolve the recovery. SAF does not provide an atomic transaction shared with other apps: an external change in the final compare/write window can still be overwritten. Avoid editing the same inbox concurrently.

The original setup uses `Tiny experiments/Sources/Inbox.md`. The picker initially opens `Documents/Obsidian`. That path is a navigation hint, not a direct filesystem permission. The actual destination is the persisted URI of the selected file. The picker accepts any `.md` file.

The old Automate flow is no longer required.

## Project layout

| File | Responsibility |
|---|---|
| `AndroidManifest.xml` | App identity, permissions, share intent filter, activities, short foreground service |
| `src/me/venkatesh/saveobsidian/ShareActivity.java` | Receive shares, queue them privately, start the service |
| `src/me/venkatesh/saveobsidian/SetupActivity.java` | Launcher inbox with edit/delete; Settings groups file access, notifications and recovery |
| `src/me/venkatesh/saveobsidian/VerifiedEdit.java` | Snapshot, backup, write and read-back sequence, exercised with failing storage callbacks |
| `src/me/venkatesh/saveobsidian/InboxFile.java` | Pure inbox parsing: list entries, delete one, set Why/Ideas |
| `src/me/venkatesh/saveobsidian/CaptureService.java` | Private queue, title lookup orchestration, append/verification, progress and completion notifications |
| `src/me/venkatesh/saveobsidian/PageTitle.java` | Bounded HTTPS HTML title retrieval |
| `src/me/venkatesh/saveobsidian/Capture.java` | URL extraction, title cleanup, local timestamp, Markdown formatting |
| `src/me/venkatesh/saveobsidian/AppendCheck.java` | Exact byte comparison of original content plus appended entry |
| `src/me/venkatesh/saveobsidian/Vault.java` | Pure Java: frontmatter and `[[link]]` parsing, link resolution, backlinks, citations |
| `src/me/venkatesh/saveobsidian/VaultStore.java` | Read-only vault folder scan over SAF with a modified-time cache, embeddings and search |
| `src/me/venkatesh/saveobsidian/VaultActivity.java` | Knowledge screen: folders, notes, Read/Links/Similar tabs, search |
| `src/me/venkatesh/saveobsidian/Ask.java`, `AskActivity.java` | Ask your notes: OpenRouter tool loop (search, read, list, links) with tappable citations |
| `src/me/venkatesh/saveobsidian/Md.java` | Minimal Markdown renderer for notes and answers |
| `tests/CaptureTest.java` | Runnable JVM checks; no test framework required |
| `res/drawable/` | App and notification vector icons |
| `build.py` | Compile, test, package, sign, and verify without Gradle |
| `docs/design.md` | Original design plus the later background-capture extension; the extension supersedes URI delivery |

App ID: `me.venkatesh.saveobsidian`. Current version: `1.2.2`, version code `5`. Minimum API: `26`. Compile/target API: `35`.

## Build

Prerequisites:

- Python 3.
- JDK 17.
- Android SDK platform 35 (`android.jar`).
- Android SDK build-tools 35 (`aapt2`, `d8`, `zipalign`, `apksigner`).

No Gradle project or wrapper is included. Configure the extracted tool directories and run:

```bash
export SAVE_OBSIDIAN_JDK="/path/to/jdk/Contents/Home"
export SAVE_OBSIDIAN_PLATFORM="/path/to/android-sdk/platforms/android-35"
export SAVE_OBSIDIAN_TOOLS="/path/to/android-sdk/build-tools/35.0.0"
python3 build.py
```

Output: `build/Save-to-Obsidian.apk`.

The build script runs JVM checks, compiles vector resources and Java, creates DEX, aligns the APK, signs it, verifies the signature, and prints manifest/package information. Generated files are ignored by Git. For a clean rebuild, remove only the generated `build/` directory before running the script.

The tools used on the original Mac were extracted temporarily at:

```bash
export SAVE_OBSIDIAN_JDK="/tmp/obsidian-android-build/jdk-17.0.21+9/Contents/Home"
export SAVE_OBSIDIAN_PLATFORM="/tmp/obsidian-android-build/android-35"
export SAVE_OBSIDIAN_TOOLS="/tmp/obsidian-android-build/android-15"
```

The last directory is named `android-15` by the downloaded build-tools archive, although it contains build-tools 35. These temporary paths may disappear; use a persistent SDK installation for ongoing development.

### Signing and updates

The build script keeps its signing key and password outside the repository:

```text
~/.local/share/save-to-obsidian-signing/
```

Do not commit, upload, or print those files. On the same Mac, another coding agent can run the build script using the existing signing identity without reading the password. A fresh environment creates a new signing identity; its APK cannot update the existing installation in place. Uninstalling the existing app removes its configuration and private recovery queue, so inspect pending captures first.

For updates, preserve the application ID and existing signing identity, and increment the version code/name in `AndroidManifest.xml`.

### Install through USB

```bash
adb devices -l
adb -s YOUR_DEVICE_SERIAL install -r build/Save-to-Obsidian.apk
```

The phone must authorize USB debugging. OnePlus may also show an install confirmation screen. Use `-r` to retain the existing file selection and app data when updating with the same signer.

## Permissions and data handling

Declared permissions:

- `INTERNET`: retrieve webpage titles.
- `POST_NOTIFICATIONS`: progress, completion, and failure feedback.
- `FOREGROUND_SERVICE`: a short user-initiated capture task.
- `POST_PROMOTED_NOTIFICATIONS`: request Android Live Updates where supported.

Persistent file access comes from the Storage Access Framework grant for the selected document. There is no all-files, accessibility, clipboard-monitoring, or Google account permission. No Drive tokens are embedded.

The destination URI is stored in private shared preferences named `capture`, key `inbox`. Pending jobs are private JSON files under `files/pending/`, containing the URL, supplied title, capture time, phase, and eventually rendered entry.

Job phases are `queued` → `prepared` → `writing`, followed by deletion on verified completion, or `failed` / `uncertain` on error. A persistent write-attempt flag prevents reappending uncertain jobs. Retry checks for the exact complete rendered entry; a URL-only or partial match cannot clear uncertainty. Old 1.2 failed jobs without a write-attempt flag are conservatively check-only. Uncertain/unreadable queued jobs block new appends until resolved. Captures retain the original shared payload, including captions and additional URLs; only the first URL is the primary link. A duplicate's differing original payload is retained privately for export. New captures are processed by creation time with a filename tie-breaker. Do not promise exactly-once delivery across every possible crash.

Capture writes open the inbox with append mode `wa`, never a truncating mode. Annotation/deletion uses the separately guarded rewrite described above. Verification reads are limited to 32 MiB. The app does not lock out other applications editing or synchronizing the file: a concurrent change can cause verification to fail even if the append itself succeeded. In that case inspect the inbox before retrying. Do not implement an automatic rewrite/restore of the inbox to handle this error.

## Live Alerts implementation details

`CaptureService` uses a standard progress notification on channel `source-capture`, notification ID `100`.

- On Android 12+, `FOREGROUND_SERVICE_IMMEDIATE` prevents notification deferral from hiding a task that finishes quickly.
- On Android 16+, reflection requests promoted ongoing notification behavior and sets short critical text. This permits compiling with API 35; promotion is device/user controlled.
- Saving uses an indeterminate progress state; completion uses 100% progress with **Source added / Added**, retained briefly before removal.
- On Android 14+, the service declares and starts with type `shortService`.
- The share receiver has an empty task affinity and a translucent theme. Setup has a separate launcher activity so completing a share does not reveal an old setup screen.

Preserve these details when refactoring: both notification deferral and shared setup/share task behavior caused observable problems during phone testing.

## Validation and known limits

82 JVM checks passed for parsing, formatting, timestamp shape, invalid input, and rejection of truncated/changed/duplicated append results. APK v2/v3 signing verification passed.

Tested through USB on OnePlus CPH2767, reporting Android 17/API 37, on 2026-11-04:

- System document selection and persistent write permission.
- Shared-title HTML entity decoding and preservation of spaces, apostrophes, literal plus signs, and URL query parameters.
- HTML title retrieval when no shared subject was supplied.
- Background append while Obsidian was force-stopped, with no Obsidian process launched.
- Original inbox content preserved byte-for-byte and each test entry present once.
- Visual **Added** island acknowledgment while Chrome remained in the foreground.

The phone tests were performed manually through ADB; they are not an automated instrumentation suite. The JVM checks do not execute Android services, SAF, notification promotion, or network retrieval.

Known limits for the next coding agent:

- First URL only; no bulk sharing or attachments.
- Login-protected/JavaScript-only pages may yield a generic title or fall back to the URL. Publisher suffixes are retained.
- Title retrieval follows a bounded redirect loop, reads at most 512 KiB, and uses connect/read timeouts plus an approximately eight-second deadline; it is not a strict wall-clock cancellation mechanism.
- No independent cloud sync, boot rescheduling, WorkManager, or persistent automatic retry. Recovery export and confirmed dismissal are available in the app.
- New queue entries sort by creation time. Processing stops taking work after two minutes and stops the service at 140 seconds; the platform timeout callback also stops it. Remaining captures stay private. Provider I/O can still block and needs phone testing; large batches are not a supported bulk-import feature.
- Minimum/target API declarations are not evidence of testing every supported OS; only the phone above was tested end to end.
- Notifications can be disabled or demoted by the user/OEM. Saving can still work without a visible island.

When continuing development, start by reading the manifest, `ShareActivity`, and `CaptureService`. Keep local save confirmation separate from cloud-sync confirmation. Run `python3 build.py` after code changes, then test an actual share on the phone without launching Obsidian. Use clearly labeled test entries, preserve existing note content, and keep phone dumps, vault notes, and signing material out of Git.

## 1.2.1 stabilization

- Plain text is no longer truncated at 2,000 characters. A 1 MiB UTF-8 limit rejects oversized shares explicitly before queuing. New multiline/whitespace-sensitive notes use `Text:` with four-space continuation; blank lines and indentation survive. `Why:` and `Ideas:` preserve multiline whitespace too. Old entries remain readable; already-truncated historical content cannot be recovered from Markdown.
- Link captures retain original shared text under `Shared text:`. `si` removal is limited to YouTube hosts. URL extraction removes trailing sentence punctuation and unmatched closing brackets while keeping balanced brackets. Original payloads remain available if this heuristic is unsuitable for a URL.
- Parsing excludes frontmatter, fenced examples and ordinary Markdown lists. Editing uses a snapshot plus occurrence position. Unknown content is preserved. This is a small supported-format parser, not a general Markdown editor.
- Failed annotations stay open and drafts are kept atomically in private storage across recreation. Copy draft annotations lets you reapply a stale draft after refreshing. Saving/deleting disables duplicate submission. Open screens refresh on capture success/failure and retry completion. Filters and scroll position survive recreation; Show older removes the previous 50-item dead end.
- Optional notifications are separate from file readiness. Status text describes local saving. Overview/widget labels describe remaining inbox items and last-seven-day windows. Controls have 48dp minimum targets; filters expose selection; form labels and button roles are explicit. The chart has a per-day accessible description and respects disabled system animation. Insets handle system bars, cutouts and keyboard. Layouts reflow instead of squeezing the footer/stat cards.
- Recovery journals, drafts, retained captures and export staging remain private and excluded from backup. Export uses Android's create-document picker with JSON/Markdown MIME types, checks against the active inbox URI, and verifies the exported bytes. Keep exports outside the live vault inbox; different provider aliases cannot be proven to identify distinct files. Original/attempted journals are removed only after verified success or explicit recovery resolution. The earlier `inbox.bak` remains exportable.

See [stabilization validation](docs/stabilization-validation.md) for executed checks and device coverage.

## 1.2.2: focused inbox and Settings

The front page now contains the Sparkbox wordmark, Settings, the inbox title/count, Overview, filters and captures. Stronger capture titles, consistent row spacing and the existing annotation rail give the list a clearer hierarchy. The full file path, file picker, access check, Obsidian link, refresh, notification preferences, Live Updates, edit recovery, earlier backup and retained captures are grouped in Settings. Historical backups no longer take space above the inbox. Unresolved captures or edit recovery still produce a small Review notice on the inbox.

Settings reuses the native Activity and existing recovery actions. System Back and Back to inbox return to the list; filters and page position are kept, including across Activity recreation. No new dependencies, storage format changes or capture-service changes are introduced by this UI release.

## 1.7.0: knowledge base

Sparkbox now reads the whole vault, not only the inbox. Open **Knowledge** from the menu and choose the vault folder once (Android's folder picker, read-only).

- **Scope.** Only `Knowledge/` (including `Knowledge/Learnings/`) and `Sources/` are read (not `Sources/Inbox.md`). Dot-folders, Daily, Health and every other folder are never read, indexed or sent anywhere.
- **Browse.** Folders, notes, a topic and people chip row from frontmatter, and per note: **Read** (links are tappable), **Links** (links to, linked from, concepts with no page yet) and **Similar** (nearest by meaning). Notes with no links have their own list.
- **Links.** `[[Path/To/Note]]`, `[[Note]]`, `[[Note|alias]]` and `[[Path\|alias]]` in tables resolve by exact path first, then by a name only one note has. An ambiguous name stays unresolved instead of being guessed.
- **Search.** Word match shows at once. With an OpenRouter key, closest-meaning results are added. Note vectors live in `vault.bin`, separate from the inbox `index.bin`. The first index pass embeds 640 notes per background run.
- **Ask.** The **Ask** button sits beside the capture **+** on the home screen, and a search in Knowledge offers "Ask your notes". Needs the OpenRouter key and a model that supports tool calls (default `anthropic/claude-haiku-5.5`, changeable in Settings). There is no agent SDK: `Ask.java` is a plain tool loop (about 150 lines) over OpenRouter chat completions. The model gets five read-only tools, `search`, `read`, `list`, `links` and `inbox`, and takes up to 8 steps. It covers Knowledge (with Learnings inside it), Sources and the inbox, and answers only from them. Each claim is cited as `[[path|Title]]`: a note citation opens the note, an inbox citation opens the inbox filtered to that capture. Long-press an answer to copy it. The searches and the text it reads are sent to OpenRouter.
- **Search ranking.** Word match scores rarer words higher and title words triple (`Vault.search`). A query of up to three words puts the word match first and adds what meaning found; a longer question fuses both lists (`Vault.blend`).
- **Obsidian.** A note's **Open in Obsidian** link uses the `obsidian://open` scheme with the vault folder's name.
- **Read-only.** Nothing in this feature writes to the vault. The inbox keeps its own guarded append and edit path.
- **Cache.** `vault.json` keeps parsed notes keyed by size and modified time. Bump `VaultStore.CACHE` whenever `Vault.parse` changes what it extracts.

Measured on the OnePlus with the 426-note vault: first scan 2.6 s, cached scan about 0.4 s, Knowledge screen first frame about 50 ms (draw 16 ms). Opening it was slow at first because the recent list parsed dates with a fresh regex on every sort comparison and the search index was read on the main thread; dates are now memoised, the index count runs in the background, and the snapshot is preloaded when the inbox opens. A 42 KB note renders in 12 ms and lays out in 42 ms.

The JVM checks cover parsing, link resolution, search ranking and result blending (138 checks). On the phone, by hand: browse, Links, Similar, search ranking, Ask with a note citation, Ask over an inbox capture with an inbox citation, Ask from a search, the no-links list and Open in Obsidian. Not tested: light theme, large font scale, rotation (the two screens handle it without recreating) and Ask with no network.
