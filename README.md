# Sparkbox

A personal Android app that catches links, thoughts, voice notes and files, and turns them into an Obsidian knowledge base: Source notes, Topics, People and recall cards. It works without opening Obsidian. The Markdown vault on the phone is the only place knowledge lives; the app keeps nothing of its own except its queue, settings and caches.

Repository: https://github.com/VenkateshDas/sparkbox. The package ID keeps its original name (`me.venkatesh.saveobsidian`) so updates install in place.

Version `1.10.0` (version code `15`). Min API 26, target API 35, compiled against API 37.

## What it does

**Capture** (always local, never waits for the network or a model)
- Share a link, text, photo, PDF, document, audio or video to **Sparkbox** from any app. A **Source added** notification (an island on OnePlus) confirms within a second.
- The **Quick capture sheet** (widget, Quick Settings tiles, launcher shortcuts, the Feed's **+**) writes a thought or records a voice note.
- **Sparkbox Capture** is a separate 2×1 home-screen widget with Write and Speak icons. It follows system light/dark appearance and switches to a timer and Stop while recording.
- Captures are queued in private app storage first. **Save to** keeps Inbox as default, or sends text/voice to an existing note or a new personal Topic. Existing notes receive your exact words under My Notes. New notes use your title, or an AI title when blank; AI titling and voice transcription wait safely when offline.
- Direct captures bypass Inbox processing. Failed captures retain their text/recording, offer Retry, and allow a destination/title change before a note write has been attempted.

**Processing** (in the background, needs an OpenRouter key)
- Processing runs only inside a persisted Android job, including Retry. Fresh captures request expedited execution with an ordinary-job fallback; interrupted jobs resume after Android allows work again. Force stop requires user interaction before processing resumes.
- Article figures and Instagram photo/carousel media are read visually before planning. Each visual result is checkpointed, large carousels continue across bounded passes, and every inspected item is represented in the plan. Private media bytes go to OpenRouter; Instagram cookies stay on the phone.
- **Ingest** turns each link into a Source note, adds claims to existing Topic pages, creates new Topics, People and Entities, updates the indexes and the log, and removes the capture from the inbox. Each step is checkpointed, so a stopped or killed run resumes without paying for the model again.
- A second, cheaper model files each Source under **Craft** (work) or **Life**.
- **Why** and **My ideas** written on a capture are instructions to the model.
- **Make cards** writes recall cards into existing notes; voice notes are transcribed; photos and PDFs can be described.
- **Instagram reels** are read from their public embed data, downloaded and watched by GLM 5.3 Flash through OpenRouter. Whisper supplies the speech transcript separately. The caption, video description and transcript are cached for ingest and included in the Source note. Private, unavailable or blocked posts stay queued with a readable error. Video downloads are capped at 24 MB.
- When a reel cannot be read publicly, Sparkbox falls back to Instagram's current metadata format. Use **Settings → Instagram access** to sign in for reels your account can access. That session is separate from the Instagram app's login. Access failures pause automatic retries and offer an Instagram access action on the capture. Session cookies stay on the phone and are sent only to Instagram, never OpenRouter.
- Shared video files can be summarized and tagged through **Settings → Describe photos, PDFs and videos**, using the same video and transcription path. The original file stays in the attachments folder.

**Use**
- **Feed**: captures and notes as cards, with each capture's processing steps shown live on its card. Filter by Craft, Life, Sources, Topics or People. The visible **⋮** menu opens **Edit note** (full Markdown), **Cards**, and **Keep**; Sources also offer lane changes and trash. **Kept** collects bookmarked notes, including older Topics and People without recall cards. Keep never changes Feed or Spark order. Pull down to rescan the vault.
- **Spark**: a daily spaced-repetition review of the cards (the FSRS-4.5 scheduler, a daily cap, park and flag).
- **Search** (top of the Feed, with the same filters): word match first, meaning-based matches merged in.
- **Ask**: a chat over your notes. Answers cite the notes they come from, and say "Not in your knowledge base" instead of guessing.
- **Source** and **Topic** pages mark each section as *from the source*, *AI* or *yours*. Your own words are edited in place. The full Markdown editor retains drafts, checks for concurrent edits, and keeps recovery copies when a save cannot be verified.
- **Cards** supports creating, editing and deleting user or AI cards. Topics group their own cards with linked notes; changes affect the owning note. Parked and flagged cards remain manageable.
- **Vault browser** (menu → Browse vault): folders, notes, Read / Links / Similar.
- **Settings**: vault and inbox, processing switches, models, key, Spark, theme (System, Dark, Light), recovery. Model roles use a searchable OpenRouter catalog, with offline cache, Refresh and Custom ID.

### Dashboard and local tracking

Open **Feed → menu → Dashboard**, or **Settings → Dashboard**. Seven native views cover the current vault, habits, recall, Scout, costs and health. Costs has date/model/workflow filters, input/output and cache/reasoning breakdowns, daily history, retries/failures, completed-operation averages, card economics and request/operation receipts. Export the filtered numerical data locally.

New requests are measured across streaming and non-streaming calls, including embeddings, vision and transcription. Capture processing, standalone cards, direct notes, search, Ask, recall feedback and Scout interactions get operation attribution. Initial cards supplied by an ingest plan share that request's cost; their isolated drafting cost is unavailable. Background indexing is a shared batch and is shown separately. Retained Scout request journals are merged by existing request ID, so the shared ledger does not add them twice. Missing charges/tokens stay unavailable; independently billed tools and credit fees are excluded.

Tracking is private, enabled locally by default, and can be paused or cleared in Dashboard Options. Detailed app metadata retains 90 days; existing Scout conversations retain their own history and can still supply usage after dashboard history is cleared. Deleting a Scout conversation removes its mirrored dashboard statistics. No new copies of prompts, notes, source URLs, recordings, API keys or error bodies are stored. The dashboard never invokes a model, reprocesses a capture or changes vault content on open. New daily activity cannot reconstruct earlier lifetime usage.

### Scout session usage

The line above Scout’s composer shows estimated context tokens, the advertised model capacity and percentage (when available), and reported OpenRouter session cost. Tap it, or choose **Usage** from a chat’s menu, for context history, expandable token/cost totals, and individual requests. Context is the next input; cumulative processed tokens count repeated history on every model call.

Usage includes conversation, continuations, retries, compaction and chat naming. Missing charges remain unknown; older sessions show partial historical coverage. The detail screen can recover up to five missing charges per refresh using saved generation IDs. Metrics stay in the local session JSONL and are never added to model context. Independently billed tools and credit purchase fees are excluded. Model metadata refreshes at most daily when available. Scout uses the known model capacity for compaction and a conservative fallback when capacity is unknown.

### Scout testing candidate

Scout now has durable directions and per-chat drafts, individually reviewed note changes, source previews, native table/code rendering, Keep/source/topic/card previews, local chat export, and finite manual discovery. Use **+** for context, Research, source scope and Discover. Vault-only is the default. Configure explicit **Read-only research tools** in Settings before using automatic external research; server hints alone do not grant permission.

Schedules remain disabled pending device qualification. This is not yet the complete qualified Scout release: the remaining evidence-browser, reading-restoration, discovery-normalization and schedule-recovery work is recorded in [Scout qualification](docs/scout-qualification.md), together with the phone test matrix. A stopped or killed run needs explicit continuation. A write already admitted before Stop finishes verification; Stop does not roll it back.

## Privacy

- No server, accounts, ads or analytics. Feature counters stay on the phone.
- Only `Knowledge/` and `Sources/` in the vault are read; nothing else is read or sent anywhere.
- What goes to OpenRouter: source images, the page text and your Why/Ideas when a capture is processed, reel videos and shared videos when described, questions and the notes Ask reads, card prompts, voice clips for transcription, and text to embed for search. The key is stored encrypted on the phone.
- Write access is granted through Android's folder picker. The app writes only under `Knowledge` and `Sources`, adds to pages rather than rewriting them, reads every write back, and keeps the previous text privately for two weeks.

## Build

Requires JDK 17 or newer and an Android SDK with platform 37 and build-tools. Put the SDK path in `local.properties`:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

Build the signed release APK (R8 and resource shrinking on):

```bash
./gradlew assembleRelease
```

The APK lands in `build/gradle/outputs/apk/release/sparkbox-release.apk`. Run the JVM/state checks and lint against `lint-baseline.xml`:

```bash
./gradlew check
```

`assembleRelease` runs the JVM checks first and stops if one fails. `python3 build.py` still runs the same JVM checks without Gradle, but no longer builds the APK because the UI is Kotlin.

### Signing

Both builds sign with the key in `~/.local/share/save-to-obsidian-signing/`. Never commit, upload or print it. A build signed with any other key cannot update the installed app, and uninstalling loses its settings and private queue.

### Install on the phone

```bash
adb install -r build/gradle/outputs/apk/release/sparkbox-release.apk
```

`-r` keeps settings, the queue and pending captures.

## Testing on a phone

The JVM checks cover parsing, editing, scheduling, citations, cancellation, run gating, the vault lock, the input hash, the concurrency limits and failure classification. They do not run Android services, the Storage Access Framework or the network, so test a real share on the phone after any change to capture or processing.

The release gates and how to measure them are in [docs/baseline.md](docs/baseline.md):
- **Startup:** the Feed must be usable within 291 ms (`adb shell am start -W`, plus the `Sparkbox: feed usable N ms` log line).
- **Scrolling:** at most 1% janky frames while a capture processes (`dumpsys gfxinfo`).
- **Kill during processing:** no capture is ever lost.

The inbox and vault on the phone are real. Copy the vault off the phone before a test that processes captures, then put it back and check with `diff -r`.

## Project layout

| Area | Files |
|---|---|
| App shell and navigation | `MainActivity.java` (tabs, back stack, theme), `Screen.java` |
| UI (Kotlin, Jetpack Compose, Material 3) | `SparkTheme.kt`, `FeedView.kt`, `SparkView.kt`, `AskView.kt`, `SettingsView.kt`, `QuickView.kt`, `PageView.kt` (shared detail-page blocks) |
| Screens (Java: data and actions) | `FeedScreen`, `SparkScreen`, `AskScreen`, `SettingsScreen`, `ProfileScreen`, `UnitScreen` (Source), `TopicScreen`, `NotesListScreen`, `CardsScreen`, `CardGenScreen`, `CaptureScreen`, `CaptureMapScreen`, `VaultActivity`, `QuickActivity` |
| Capture | `ShareActivity`, `CaptureService`, `RecordService`, `QuickTile`, `InboxWidget`, `Capture`, `InboxFile`, `AppendCheck` |
| Processing | `BackgroundJob`, `Tagger` (the background run), `Ingest`, `IngestText`, `Classify`, `Enrich`, `CardGen`, `Transcript`, `Fetch`, `Web`, `Content`, `Model` (OpenRouter) |
| Vault | `VaultStore` (scan, cache, embeddings), `Vault` (parsing, links, search), `VaultWrite`, `VerifiedEdit`, `NoteEdit`, `Edits`, `Layout`, `Lane` |
| Safety and concurrency | `Cancel`, `RunGate`, `VaultLock`, `Limits`, `Failures`, `Net` |
| Cards and review | `Cards`, `CardStore`, `CardSheet`, `Sched`, `Spark` |
| Ask | `Ask`, `Cites`, `Md` (Markdown renderer for the note reader) |
| Tests | `tests/CaptureTest.java` |

Core logic stays in Java; only the UI layer is Kotlin. See [docs/architecture.md](docs/architecture.md) for how the pieces fit, the storage layout, and the safety and concurrency rules.

## Docs

- [docs/architecture.md](docs/architecture.md): current architecture, storage, safety and concurrency model, failure handling.
- [docs/baseline.md](docs/baseline.md): measurements per milestone and the release gates, with screenshots in `docs/baseline/`.
- [docs/archive/history.md](docs/archive/history.md): the earlier README (capture path internals, Live Alerts details, releases 1.2 to 1.8).
- Design-era documents archived in [docs/archive/](docs/archive/) (`product-brief.md`, `implementation-spec.md`, `inbox-to-knowledge.md`, `designer-feedback-round*.md`, `stabilization-validation.md`, `storage-sync-current.html`) record decisions at the time they were written.
