# Sparkbox architecture

Current as of the 2026-11 modernization (version 1.9.0). For measurements see [baseline.md](baseline.md). For the older capture-path notes see [history.md](history.md).

## Principles

- **The Markdown vault is the source of truth for knowledge.** Pending captures, processing checkpoints and unsaved edits live in private app storage. Search vectors, the vault cache and thumbnails are caches that can be rebuilt.
- **Saving a capture never depends on the network or a model.** A capture is written to the private queue before anything else happens.
- **Core logic is Java, the UI is Kotlin/Compose.** Java screen classes keep data and actions and publish immutable state; Compose files only draw it.
- **Small and explicit.** There is no task engine, no Room, WorkManager or OkHttp. Concurrency is a few semaphores and one lock.

## Layers

```
Compose UI (*.kt)            FeedView, SparkView, AskView, SettingsView, QuickView, PageView, SparkTheme
   ▲ state (mutableStateOf)   ▼ actions (Java interfaces)
Screens (Java)               FeedScreen, SparkScreen, AskScreen, …, VaultActivity, QuickActivity
   │
Core (Java)                  Capture/Inbox, Ingest/Tagger, Model, VaultStore/VaultWrite, Cards/Sched, Ask
   │
Platform                     Storage Access Framework (vault), JobScheduler, foreground services, notifications
```

- **Shell.** `MainActivity` is a `ComponentActivity` with three tabs (Feed, Spark, Ask; search is at the top of the Feed) and a back stack of detail screens. Back goes through the androidx `OnBackPressedDispatcher`. Changing the theme calls `retheme()`, which rebuilds the shell in place and keeps the open screens.
- **Screens.** Each `Screen` builds a `ComposeView` on top of a state object (`FeedState`, `PageState`, `AskState`, …). A tab's composition is let go when the tab is left and rebuilt from the state object when it returns, so nothing is lost and nothing piles up.
- **Detail pages.** Source, Topic, Profile, Cards, the capture map, Make cards, Capture detail and the vault browser are lists of blocks (`PageView.kt`: Title, Panel, Heading, Bullet, Group, Editor, Field, Tabs, Rich, …). A Java screen builds the list and publishes it.
- **Provenance.** Panels and headings carry an origin (`Origin.SOURCE`, `AI`, `YOU`) and show it as a small tag.
- **Feed rows.** The Feed's rows are data classes (`ItemRow` holds the step, seconds, problem and kept state). When a capture moves to its next step, only that row differs, so only that row is redrawn. Feed data is built off the main thread: the vault cache read and `Feed.build` take about 100 ms.
- **Still on the View system:**
  - the bottom tab bar in `MainActivity`;
  - the note reader, which embeds `Md.render` output (Spannable links) in a TextView inside Compose;
  - link-preview images (`Thumb`, which has a memory cache, a disk cache and 480 px downsampling);
  - the home-screen widget (RemoteViews);
  - the platform dialogs (card editor, capture text, key, model, confirmations), which take a Material look from `res/values*/styles.xml`.

## Capture path

1. `ShareActivity`, `QuickActivity` and `RecordService` put each capture in the private queue (`files/pending/*.json`) before any other work.
2. `CaptureService`, a short foreground service, fetches a title, appends the entry to the inbox file with append mode, reads it back to verify, then removes the job. Failed or uncertain jobs stay queued and show under Needs attention on the Feed (Retry or Dismiss).
3. `BackgroundJob` schedules the background run. It is scheduled on capture, when settings change, and every time `MainActivity` opens; a force-stop drops scheduled jobs, so opening the app restarts processing.

## Background run (`Tagger.run`)

One run at a time, guarded by `RunGate`. Its stages run one after another, so backfills never overlap: sweep, transcribe, tag, backfill, file (Ingest), cards, describe, embed.

**Ingest of one capture** (`Ingest.process`):

1. **Ask the model once.** The answer, the plan, is saved as `plan:<key>` in the `ingest` prefs, together with `planHash:<key>`, a hash of the Why and Ideas it was asked with. While this call runs, the Craft/Life check runs on a second thread.
2. Tag the inbox entry from the plan.
3. **Check the inputs.** If the Why or Ideas changed since the plan was made, the plan is dropped and the capture is queued again. A result made for older instructions is never saved.
4. **Write the vault under `VaultLock`.** In order: Source note, Topics, People, Entities, cards, the indexes, the log, then removal from the inbox. Each step is recorded in a set of done steps, so an interrupted run resumes where it stopped and never calls the model again.

**Retries.**
- Up to 6 tries, waiting 0, 2, 10, 30, 120 and 360 minutes.
- An offline failure does not use up a try.
- A bad key stops the retries; a rate limit waits at least 10 minutes (see Failures).

## Safety and concurrency

| Piece | What it guarantees |
|---|---|
| `Cancel` | Each request or run has its own stop switch. Cancelling Ask closes only Ask's connection. Halting a run lets the call in flight finish, then stops the loops. Threads a run starts inherit its switch. |
| `RunGate` | One background run at a time, each with its own `Cancel`. A run silent for 20 minutes is told to stop and waited for (up to 30 s). If it is still alive, the new run is skipped; two runs never overlap. |
| `VaultLock` | One in-process `ReentrantLock` held over read, edit, compare, write and read-back. It covers `VaultWrite`, `VerifiedEdit`, `CardStore` writes, lane changes and Ingest's whole write phase. Obsidian and sync apps are outside it, so the compare, backup and read-back checks stay. |
| `Limits.FETCH` | At most 3 source fetches at once (`Fetch.page`). |
| `Limits.AI` | At most 2 background model calls at once (`Model.post`). Interactive calls (Ask, Search, vault search, Similar) set `Limits.interactive(true)` on their thread and never wait behind background work. Ask streams on its own connection. |
| Vault scan | One scan at a time (`VaultStore.scan`). A caller that waited for a scan which started after it asked reuses that result. |

**Plan input hash.** When processing finishes, the hash of the current Why and Ideas is compared with the saved one; a mismatch drops the result and queues the capture again. Plans saved before this check existed have no hash and are kept.

## Failures

`Failures.kind(message)` sorts an error into one of six kinds, and each kind gets its own text in the Feed and in Ask:

| Kind | Example | What happens |
|---|---|---|
| offline | no route, failed lookup | retried by itself about every 2 minutes; no try used |
| key | 401, 402, 403, out of credit | retries stop until the owner retries from the card or Settings |
| rate | 429 | waits at least 10 minutes |
| malformed | no topics, invalid JSON, refused request | normal retries; from the third try a fallback model is used |
| conflict | a note or the inbox changed during the edit, read-back differs | normal retries; the saved plan is reused, so no new model call |
| other | anything else | normal retries, with the raw message shown |

## Storage

| Where | What |
|---|---|
| Vault: `Knowledge/`, `Knowledge/Sources/` (older `Sources/`) | Topics, People, Entities, Learnings, Source notes, `index.md`, `log.md`, `Knowledge/Sources/Inbox.md` |
| `files/pending/` | capture queue (one JSON job per capture) |
| `files/content/` | saved page text per link, dropped when the capture leaves the inbox |
| `files/pending-cards.jsonl` | card edits waiting to be written into notes |
| prefs `capture` | settings: vault and inbox URIs, switches, models, theme, Feed filter, Ask history |
| prefs `ingest` | per capture: tries, next try, error, current step, done steps, plan, plan hash |
| `vault.json`, `vault.bin`, `index.bin` | vault parse cache, note vectors and inbox vectors (all rebuildable) |
| Private edit journals and backups | the text before each vault edit, kept 14 days |

`allowBackup` is off.

## Build

- Gradle 9.9.1 with AGP 9.5.1 and its built-in Kotlin, Compose BOM 2026.09.00 and Material 3. Versions are in `gradle/libs.versions.toml`.
- Java and Kotlin sources live together in `src/`; the manifest and `res/` stay at the root.
- `compileSdk` is 37 (androidx needs 36 or newer). `targetSdk` stays at 35, so the app's runtime behaviour doesn't change. `minSdk` is 26.
- Release builds use R8 with resource shrinking (`proguard-rules.pro` only silences jsoup and org.json warnings).
- `captureTest`, the JVM checks, runs before every build and as part of `check`. Lint findings that predate Gradle are in `lint-baseline.xml`.

## Scout execution and review

`Session.context()` is exclusively the provider projection; `retained()` supplies visible history. `ScoutState` reduces accepted directions, consumption and cumulative budgets from the same synced JSONL. Private per-chat AtomicFile drafts are not conversation history.

`ScoutChanges` stores proposals, decisions, intents, receipts and remote dispatch outcomes. `Agent` suspends without fabricating a tool result; completed siblings are saved and remaining siblings are deferred. `VaultWrite.writePinned` performs actual SAF access through the approved tree. `Cancel` owns the write-admission monitor as well as transport cancellation and interruptible backoff.

`ScoutEvidence` supplies host source identities and bounded retained excerpts. `ScoutMarkdown`/`ScoutAnswerView` serve Scout only; the note viewer keeps its prior rendering path. `ScoutDiscovery` stores explicit interests and a bounded seen ledger. `ScoutJob`/`ScoutScheduleReceiver` are separate from Tagger, Reminder and the manual foreground owner. Their release gate remains disabled.

See [Scout qualification](scout-qualification.md) for implemented behavior, known gaps and the unexecuted device matrix. Compilation and JVM checks do not qualify scheduling or answer quality.
