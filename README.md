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
5. Skip the write and show "Already in inbox" if the URL is already a line in the file.
6. Append to the user-selected inbox with one blank line between entries, flush the write, and read it back to verify that all existing bytes plus the exact new entry are present.
7. Show a brief completed progress acknowledgment, then stop the foreground service. A failed save keeps the capture privately, with Retry in the notification and on the home screen. Retrying is safe because step 5 finds an entry that did land.

Obsidian is not launched. On the tested OnePlus, the notification title is **Source added** (**Already in inbox** for a duplicate) and the compact island text is **Added**. The acknowledgment clears after approximately 3.5 seconds once processing finishes. Other devices may show a regular notification rather than an island.

**Saving is local.** The existing GSync plugin uploads entries when Obsidian next opens and syncs. This app does not independently synchronize with Google Drive.

## Install and configure

Install `build/Save-to-Obsidian.apk` on the phone. The APK is personally signed, not published on Google Play.

1. Open **Sparkbox** from the launcher. The home screen shows a setup checklist until the inbox and notifications are ready.
2. Tap **Choose inbox file** and select the existing local inbox (any `.md` file) using Android's document picker.
3. Allow notifications.
4. On OnePlus, open the app's notification settings and enable **Show Live Updates on Live Alerts**. Enable banners if a standard notification fallback is desired.
5. Share a webpage, video, post link, or plain text and select **Sparkbox**.

The home screen lists the entries currently in the inbox file, newest first. The card heading shows the total (for example "Inbox · 42") with a line below: links, notes, and how many have a Why or Ideas. It re-reads the file every time you open the app, so entries you delete or process in Obsidian disappear from the list. Tap an entry to write **Why I saved this** and **My ideas** (saved as `Why:` and `Ideas:` lines under the entry) or to **Delete** it from the file; long-press copies the link. It also lists captures that need attention (Retry, Copy, Discard), **Open Inbox in Obsidian**, and **Check inbox access**.

**Editing rewrites the file.** Saving a share is still append-only, but delete and notes rewrite `Inbox.md`. Before each rewrite the app keeps the previous bytes in a private copy, refuses if the file changed since it was read, verifies the result, and restores the old bytes if verification fails. Edits made in Obsidian between opening the app and tapping Save are refused with "Refresh and try again". It follows the system dark mode.

The original setup uses `Tiny experiments/Sources/Inbox.md`. The picker initially opens `Documents/Obsidian/Tiny experiments/Sources`. That path is a navigation hint, not a direct filesystem permission. The actual destination is the persisted URI of the selected file. The picker accepts any `.md` file.

The old Automate flow is no longer required.

## Project layout

| File | Responsibility |
|---|---|
| `AndroidManifest.xml` | App identity, permissions, share intent filter, activities, short foreground service |
| `src/me/venkatesh/saveobsidian/ShareActivity.java` | Receive shares, queue them privately, start the service |
| `src/me/venkatesh/saveobsidian/SetupActivity.java` | Launcher home screen: setup checklist, live inbox list with edit/delete, needs-attention list, inbox picker |
| `src/me/venkatesh/saveobsidian/InboxFile.java` | Pure inbox parsing: list entries, delete one, set Why/Ideas |
| `src/me/venkatesh/saveobsidian/CaptureService.java` | Private queue, title lookup orchestration, append/verification, progress and completion notifications |
| `src/me/venkatesh/saveobsidian/PageTitle.java` | Bounded HTTPS HTML title retrieval |
| `src/me/venkatesh/saveobsidian/Capture.java` | URL extraction, title cleanup, local timestamp, Markdown formatting |
| `src/me/venkatesh/saveobsidian/AppendCheck.java` | Exact byte comparison of original content plus appended entry |
| `tests/CaptureTest.java` | Runnable JVM checks; no test framework required |
| `res/drawable/` | App and notification vector icons |
| `build.py` | Compile, test, package, sign, and verify without Gradle |
| `docs/design.md` | Original design plus the later background-capture extension; the extension supersedes URI delivery |

App ID: `me.venkatesh.saveobsidian`. Current version: `1.2`, version code `3`. Minimum API: `26`. Compile/target API: `35`.

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

Job phases are `queued` → `prepared` → `writing` → `saved`. Successful jobs are deleted. Jobs still at `queued` or `prepared` can be processed on a later service start. A job found at `writing` is treated as uncertain and never appended automatically again. Setup shows selectable recovery text; there is currently no dedicated retry/delete recovery UI. Do not promise exactly-once delivery across every possible crash.

The inbox is opened with append mode `wa`, never a truncating mode. Verification reads are limited to 32 MiB. The app does not lock out other applications editing or synchronizing the file: a concurrent change can cause verification to fail even if the append itself succeeded. In that case inspect the inbox before retrying. Do not implement an automatic rewrite/restore of the inbox to handle this error.

## Live Alerts implementation details

`CaptureService` uses a standard progress notification on channel `source-capture`, notification ID `100`.

- On Android 12+, `FOREGROUND_SERVICE_IMMEDIATE` prevents notification deferral from hiding a task that finishes quickly.
- On Android 16+, reflection requests promoted ongoing notification behavior and sets short critical text. This permits compiling with API 35; promotion is device/user controlled.
- Saving uses an indeterminate progress state; completion uses 100% progress with **Source added / Added**, retained briefly before removal.
- On Android 14+, the service declares and starts with type `shortService`.
- The share receiver has an empty task affinity and a translucent theme. Setup has a separate launcher activity so completing a share does not reveal an old setup screen.

Preserve these details when refactoring: both notification deferral and shared setup/share task behavior caused observable problems during phone testing.

## Validation and known limits

13 JVM checks passed for parsing, formatting, timestamp shape, invalid input, and rejection of truncated/changed/duplicated append results. APK v2/v3 signing verification passed.

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
- No independent cloud sync, boot rescheduling, WorkManager, persistent automatic retry, or dedicated recovery management screen.
- Queue order follows directory enumeration, not an explicit FIFO sort. Large batches and foreground-service timeout behavior need additional work before general distribution.
- Minimum/target API declarations are not evidence of testing every supported OS; only the phone above was tested end to end.
- Notifications can be disabled or demoted by the user/OEM. Saving can still work without a visible island.

When continuing development, start by reading the manifest, `ShareActivity`, and `CaptureService`. Keep local save confirmation separate from cloud-sync confirmation. Run `python3 build.py` after code changes, then test an actual share on the phone without launching Obsidian. Use clearly labeled test entries, preserve existing note content, and keep phone dumps, vault notes, and signing material out of Git.
