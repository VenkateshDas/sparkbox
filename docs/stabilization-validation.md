# Sparkbox 1.2.1 stabilization validation

> Historical: validation of release 1.2.1. Current checks and measurements are in [baseline.md](baseline.md).

## Executed locally

- 82 runnable JVM checks pass, including the existing capture/append checks.
- New checks cover full long/multiline note round trips, blank lines/indentation, metadata-like note contents, frontmatter/code-fence exclusion, unrelated Markdown preservation, selecting the second identical entry, rejecting ambiguous raw-only edits, complete versus partial recovery matching, original link payloads, host-specific URL cleanup, punctuation and balanced brackets.
- Fault checks exercise the same VerifiedEdit sequence used by the Android service: successful verification, stale snapshot, external change after backup, partial write exception, external content during verification, failed backup, and no-op edits. They assert that no automatic rollback overwrites external content and both original/attempted versions reach the backup callback before writing.
- Android resources and all Java sources compile with platform 35 and JDK 17. The complete build, DEX packaging, APK alignment, and APK v2/v3 signature verification pass using the existing signing identity.

The JVM fault checks use in-memory storage callbacks. They do not execute Android document providers, notifications, Activity lifecycle, AtomicFile, or OEM behaviors.

## Device acceptance checklist

Use a disposable Markdown inbox, never append test data to the real vault. Record installed version and original destination before testing, then restore the original destination afterwards.

- Install signed update in place; configuration retained.
- Share a link while another app stays foreground; Added follows verified local write.
- Keep Obsidian stopped during capture.
- Inspect complete long/multiline note and copy text.
- Save annotations to the second identical entry; first remains unchanged.
- Make an external change while editor is open; edit refused and draft retained.
- Delete with confirmation; original file content otherwise preserved.
- Exercise recovery export to a new file and uncertain/partial captures using synthetic files only.
- Check light/dark, large font, keyboard, navigation insets, overview and widget.
- Restore original inbox selection and device settings.

## Remaining platform boundary

SAF does not offer a cross-app transaction. An external write between final comparison and rewrite remains a race. Sparkbox does not claim to prevent every concurrent edit, to recover historical text already truncated by 1.2, or to confirm cloud sync. Avoid concurrent edits of the same inbox.

## Phone results — 5 November 2026

The final 1.2.1 APK installs over 1.2 on the connected OnePlus CPH2767 (Android 17 / API 37), retaining app configuration. Tests used a separate synthetic Markdown inbox with 62 initial entries, duplicate notes, and YAML frontmatter.

Passed on the phone:

- Show older reaches entries beyond the original 50-item cap; the YAML list is not counted as a capture.
- Annotating the second identical entry leaves the first unchanged.
- A note longer than 2,000 characters preserves its complete ending, blank lines and indentation, appends exactly once, and leaves all earlier bytes intact. Full body appears in the entry dialog.
- An external file change while the editor is open rejects the edit without changing those external bytes. Annotation draft remains available and survives background process recreation.
- Confirmed deletion removes only the selected synthetic entry.
- Sharing a titled link returns to the originating browser (Microsoft Edge on this phone). Original captions, additional URL and a required non-YouTube `si` parameter are preserved. Obsidian stays stopped.
- The Added island acknowledgment is visible over the browser. Captured screenshot was inspected.
- Temporarily making the synthetic destination unavailable retains the capture. Recovery export contains the entire queued job; retry after restoring the destination saves it once.
- The final MIME fix exports a `.json` recovery file without the earlier unwanted `.txt` suffix.
- The final live-refresh fix updates the open screen on both failed saves and successful retries without reopening the app.
- Dark-mode entry/inbox and light-mode inbox/overview at 200% font were inspected. Stats stack vertically; long labels wrap. Original font and night-mode settings were restored.

The original inbox selection was restored. Its SHA-256 was identical before and after testing. No pending-capture warning remained. Original font scale (0.85) and night mode (yes) were restored, and the three synthetic phone files were removed. Screenshots, UI dumps and test payloads remain outside Git in a private temporary directory.

Not claimed: a complete TalkBack walkthrough, widget placement/resizing on the launcher, every Android version, notification-denied behavior, exhaustive provider crash tests, or large-batch/foreground-service timeout verification. The custom chart exposes an accessible per-day description, but this was not validated with TalkBack. Storage fault behavior is covered by the JVM seam, not by inducing failures in the real vault.


## 1.2.2 inbox polish — 5 November 2026

- Full signed build passes with 82 JVM checks and APK v2/v3 verification. Installed over 1.2.1 on the same OnePlus.
- The front page contains Settings, Inbox, Overview, filters and captures. Earlier backup, full storage path, notification and file-management controls are absent from the normal inbox.
- Settings exposes the current file path, change-file picker, access check, Obsidian action, refresh, notification preferences and the earlier-backup export. The existing export/recovery implementation is reused.
- System Back and Back to inbox return to the inbox. Selected filter survives a Settings round trip. Settings restores after background process recreation, including at 200% font size.
- Dark inbox and Settings recovery, plus light inbox and Settings at 200% font, were inspected on the phone. Existing palette and annotation rail were retained; title hierarchy and row spacing were refined.
- Original font scale and night mode were restored. The original inbox SHA-256 remained unchanged. No inbox selection or note content was changed during this UI pass.
- UI evidence remains private under /tmp; no screenshots, UI dumps or note contents were added to the repository. No new test framework, dependency or storage schema was added.
