# Scout scrolling and rendering repair

Investigated 2026-11-17. No Android device or emulator was connected; frame timing and touch behavior still require device verification.

## Findings and changes

- The follow policy treated programmatic scrolling as a user gesture. Its regression test fails with the original rule: `Programmatic scrolling must not disable following`. Only a user drag now pauses following; reaching the bottom or pressing Jump to latest resumes it. Each chat preserves its own position and follow intent.
- Scrolling depended on row count and every 200 streamed characters. This missed later Markdown measurement and keyboard resizing. Following now observes actual list layout and targets a stable trailing item. Saved reading offsets wait for their row to be measured. An empty loading layout does not consume the saved position.
- Every live chunk invalidated the whole screen. Live text now collects updates in its own composable; history and composer observe state with live text excluded. The progress indicator and clock remain separate leaves. Running rows have a stable key and message types have explicit reuse types.
- Event bursts rebuilt history and spawned usage threads. Refreshes now coalesce; completed answer objects and citation callbacks are reused. Usage reads are delayed and serialized. Equal question/work rows do not emit another layout update.
- Main-thread history reads competed with journal serialization and disk synchronization. A worker captures a detached, non-writable session snapshot and builds history rows. Rendering reads that copy, without waiting on the live writer. Stale results from navigation, hidden screens, or replaced runs are rejected.

## Verification

Run `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew captureTest askStateTest lintDebug assembleDebug --console=plain`.

JVM checks cover automatic versus user scrolling, following after content growth, restored offsets, independent saved positions, live-text isolation, unchanged history emissions, and detached snapshot reads while the live session monitor is held. These checks validate policy and state behavior, not actual Compose frame rates. Build and lint also pass.

On a phone, verify a long streaming answer while opening/closing the keyboard, dragging into older messages, pressing Jump to latest, switching chats, and returning from another tab. Following should track new output only when enabled; reading older messages should keep the current position.
