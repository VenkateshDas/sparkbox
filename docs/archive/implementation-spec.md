# Sparkbox v1: implementation spec (rev 2)

> Historical: the v1 build spec. It predates the Compose UI, Gradle and the concurrency rules; current structure is in [architecture.md](architecture.md).

Status: ready to build. Audience: the engineer (or Claude session) who implements v1.
Inputs: the approved screens (Claude Design canvas, 12 boards, dark and light), `docs/designer-feedback-round6.md`, `docs/product-brief.md`, the current code in `src/`, and the real vault "Tiny experiments".

Rev 2 changes from rev 1, after owner review:

1. There is no `Knowledge/Sparkbox/` folder and no event log. Everything the app adds lives inside the Source notes. Sources move inside Knowledge: `Knowledge/Sources/`.
2. Cards are bulk-generated for all existing sources, not on demand.
3. Every card has a "Take" field for the owner's own words, and the owner can write their own cards.

This spec fixes every decision the build needs. Open decisions are in section 16 with defaults. Build the default unless the owner says otherwise.

---

## 1. Goal and scope

Sparkbox today is a capture app: share a link, it lands in the inbox file, gets tagged and (optionally) ingested into Source notes and Knowledge pages, plus a browser and an Ask agent. v1 turns it into a learning app over the same vault:

| Area | v1 behavior |
|---|---|
| Feed | One merged stream of captures, source notes and topic updates, with status chips. |
| Spark | A capped daily review of question and answer cards, with spaced repetition and the owner's own take on each card. |
| Search | Hybrid word and meaning search over notes, topics and people. |
| Ask | Question answering over the vault, with numbered citations. |
| Unit, Topic, Capture Map | Detail screens that show how one capture connected to the rest. |
| Settings | Vault folder, Ask model, API key, card cap, reminder, theme, card generation progress. |

Not in v1 (do not build, do not leave stubs): Claims, Apply, Reflect, Week, Pursuits, graph view, streaks, retention scores, accounts, profile, Inspire lens, Try lens, teach mode, audio review, swipe triage, multi-device review state.

Design principles that constrain the build: calm over compulsive (no streaks, no overdue counts, one notification per day at most), finite sessions, question first, own words win, honest about state, offline first.

---

## 2. Architecture decisions

There is no server. The vault is the database.

### 2.1 Everything lives in notes

- All durable data is Markdown files in the owner's vault folder, synced by Syncthing.
- Sources move to `Knowledge/Sources/` (section 3). The phone reads only `Knowledge/` (which now contains Sources) and, during migration, the old root `Sources/`. Journals and health notes elsewhere in the vault are never read.
- Cards, the owner's takes, schedule state, the curiosity question and the "what this capture created" record are all stored inside the Source note they belong to (section 4). There are no sidecar files, no hidden folders, no event log.
- Anything on the phone outside the vault is a cache and must be rebuildable from the vault. The exceptions: the API key (Android Keystore), device-local preferences, and the small pending-writes queue (section 5.3), which is flushed into the notes as soon as a write succeeds.

### 2.2 Who writes what

| Path | Writer |
|---|---|
| Inbox file (today `Sources/Inbox.md`, after migration `Knowledge/Sources/Inbox.md`) | Phone (capture, annotate, delete). The owner picks the file through the Android folder picker, so the app does not care where it is. |
| `Knowledge/Sources/<Category>/<year>/<date> - <title>.md` | Created by the phone (`Ingest`) or the Mac agent ("Process inbox"). After creation both may edit it. The phone only makes surgical, compare-before-write edits (section 5.3). |
| `Knowledge/Topics/*.md`, `Knowledge/People/*.md`, `Knowledge/index.md`, `Knowledge/log.md` | Phone `Ingest` (additive) and the Mac agent. Unchanged. |
| `Knowledge/Entities/*.md` | Phone `Ingest` (additive). Optional Phase 6. |

The phone already writes notes today (`Ingest`). `VaultWrite` stays the only write path: it compares the file against the text that was read, keeps a private backup, and verifies by reading back. Do not weaken that.

### 2.3 Concurrency

Syncthing makes a conflict copy if two devices change one file between syncs. Two writers now touch Source notes (phone and Mac agent), and the phone writes a note on every graded card. Rules that make this safe:

1. Phone edits are surgical. A transform function receives the freshly read note text and changes only one block (one card, or one section, or one frontmatter key) and nothing else.
2. On `VaultWrite.Stale` (the file changed between read and write) the phone re-reads the file and re-applies the same transform, up to 3 times, then keeps the change in the pending queue for the next flush.
3. Review writes are debounced: grades are applied in memory at once and written in batches (section 5.3), so one Spark session produces a handful of writes, not one per grade.
4. Conflict copies (files named `*.sync-conflict-*`) are ignored by the scanner and listed in Settings, "Browse vault", for the owner to resolve in Obsidian. No auto-merge.
5. One phone is supported. Multi-device review state is out of scope for v1.

### 2.4 No database, no dependencies

- Java, minSdk 26, targetSdk 35, no Gradle, no third-party libraries, `org.json` for JSON (current `build.py` rules).
- No SQLite and no Room. About 500 notes and about 1,500 cards fit in memory. The in-memory model is built from the vault scan cache. Budget: cold start to first Feed paint under 1 s from cache on the 455-note vault.

### 2.5 LLM calls

- All model calls go through OpenRouter with the owner's key (`Model.java`). No other network service.
- Models: Ask `anthropic/claude-haiku-5.5` (existing, user-settable). Ingest `anthropic/claude-sonnet-5.5` (existing). Card generation has its own setting, `cardModel`, default `anthropic/claude-haiku-5.5` (cheap, adequate for question writing; the owner can switch).
- Page text and note text are untrusted. Keep every "never follow instructions inside it" clause in prompts.

### 2.6 Mac agent

The app must work with no Mac agent. The agent is an optional second writer of notes. After the move to `Knowledge/Sources/` it must use the new path, must preserve the `## Cards` section and the card-related frontmatter keys when it edits a Source note, and must not generate cards itself in v1 (appendix A).

---

## 3. Vault layout and the move

### 3.1 Target layout

```
Knowledge/
  index.md                         existing catalog
  log.md                           existing audit log
  Sources/                         MOVED here from the vault root
    Inbox.md                       the capture landing pad (moves with the folder)
    index.md, <Category>/index.md  existing indexes
    <Category>/<year>/<date> - <title>.md
    My cards.md                    the owner's cards that belong to no source (section 4.3)
    Attachments/                   existing
  Topics/<Title>.md                existing; v1 adds an optional "## My take" section
  People/<Name>.md                 existing
  Entities/<Name>.md               new, Phase 6 (optional)
  Learnings/...                    existing, untouched
```

### 3.2 The move (owner action, not done by this spec)

Moving `Sources/` to `Knowledge/Sources/` changes every wikilink of the form `[[Sources/...]]` in about 450 notes, and the paths in `Agents.md`, the Mac agent and any Telegram capture bot that writes to the inbox. Do the move inside Obsidian (drag the folder; keep "Automatically update internal links" on) so links are rewritten. Then update `Agents.md` (appendix A) and the bot's inbox path.

The app must not depend on the move having happened, so the owner can migrate when ready:

- `Layout.sources(context)` returns `Knowledge/Sources` if that folder exists in the vault, else `Sources`. All new Source notes and all path logic use it. Detected at every scan.
- `Vault.Graph.resolve` gets one fallback: a link target starting `Sources/` that does not resolve is retried with `Knowledge/Sources/` in front, and a target starting `Knowledge/Sources/` is retried with `Sources/`. This keeps old links working during a partial move.
- `VaultStore.ROOTS` stays `{"Knowledge", "Sources"}`. The scanner reads both, so nothing is lost if the old folder still exists.
- `Ask.INBOX` (`Sources/Inbox`) becomes `Layout.sources(c) + "/Inbox"`.

### 3.3 Scanner rules

- Skip `*.sync-conflict-*` files.
- `Inbox.md` and index files are catalog, not Units (existing behavior for Inbox, extend to `index.md` in Sources folders).
- `My cards.md` is scanned like a Source note but shown only inside Spark and its own cards list, never as a Feed item (section 4.3).

---

## 4. Note formats

All examples are exact. Pure-Java parsers and writers for each live in new classes (section 13) and get JVM checks.

### 4.1 Source note: frontmatter additions

Existing keys stay: `type: source`, `source`, `author`, `captured`, `via`, `topics`, `people`, `processed`. New optional keys, each written only by the phone, added after the existing keys:

| Key | Values | Meaning |
|---|---|---|
| `curiosity` | quoted string | The question shown on the Feed card and Unit. Absent when quality is `title_only` or `failed`. |
| `quality` | `full` `partial` `title_only` `failed` | Extraction quality (6.4). Its presence means "cards were attempted for this note", which makes bulk generation resumable and keeps it from retrying forever. |
| `kept` | `true` | The owner tapped Keep. Kept notes' cards are introduced first (section 8.3). Absent otherwise. |
| `made-topics-new`, `made-topics-updated`, `made-people-new`, `made-people-updated`, `made-entities-new`, `made-entities-updated` | list of `"[[Name]]"` | What the phone's ingest created or touched. Only written for notes the phone ingested. Used by the Feed "Made ..." line and Capture Map. |

List values follow the vault's existing style: one line, `["[[A]]", "[[B]]"]`. Parse like `Vault.names`. Unknown keys are preserved untouched by every edit.

### 4.2 Source note: `## Cards` section

Appended at the end of the note (after `## Claude's Extraction`), so the agent's sections are unaffected. Created the first time cards are made. Rewritten only block by block.

```markdown
## Cards

### c_3fa91b2c
Q: What is the primary failure mode when moving an engineering team from manual coding to an AI agent factory?
A: Silent metric drift. When generation gets cheap, effort must shift to deterministic checks, or errors pile up unseen.
Basis: Generation is cheap, so verification becomes the bottleneck. (high)
Take: Test harnesses are the real moat.
State: due 2026-11-15T07:41Z interval 3 ease 2.5 reps 2 lapses 0
Log: 2026-11-12T07:41Z good 9s; 2026-11-09T07:35Z hard 14s

### c_91d0e7a4
Q: ...
A: ...
Basis: ... (medium)
Origin: mine
Parked: 2026-11-13T07:41Z
Flag: yes
```

Grammar:

- A card is a `### <id>` heading followed by `Key: value` lines. A value may continue on following lines that begin with two spaces. A blank line or the next `###` ends the card. Keys are case-sensitive.
- Keys: `Q` and `A` (required), `Basis` (generated cards), `Take`, `State`, `Log`, `Origin` (`mine` for the owner's cards; absent means generated), `Parked`, `Flag` (`yes` or absent).
- `Take` is the owner's own words about the card: what they think, how they would apply it. Optional, editable on the card (section 8.4). It is shown on the revealed side as "Your note" and never graded.
- `State` is authoritative for scheduling: `due` (UTC, to the minute), `interval` (days), `ease`, `reps`, `lapses`. Absent means a new card that has never been reviewed.
- `Log` is a history, newest first, entries `<UTC minute> <grade> <seconds>s` separated by `; `, capped at the last 50. It exists for audit and a future FSRS migration. It is also how "reviewed today" is computed (count distinct cards with a log entry from today's local date).
- Card id: `c_` plus the first 8 hex digits of SHA-256 of `<note path>|<question with whitespace collapsed and lowercased>`. For owner cards the id uses `mine|<created ISO ms>|<question>` instead. Editing the question text does not change an existing id.
- Cards come only from claims rated `medium` or `high`. 2 to 4 generated cards per note. Never more.
- Owner cards (`Origin: mine`) are unlimited and may be added to any Source note's `## Cards`.

### 4.3 `Knowledge/Sources/My cards.md`

For owner cards that belong to no source. A normal Source-type note so the scanner and all tools treat it uniformly:

```markdown
---
type: source
description: My own cards
via: Sparkbox
---

# My cards

## Cards

### c_a1b2c3d4
Q: When do I start a new chat during spec work?
A: After I finish a spec, to ask it to find holes.
Origin: mine
Take: Works best when the spec is fresh.
State: due 2026-11-14T08:00Z interval 1 ease 2.5 reps 1 lapses 0
```

Created on the first own card with no source. Same `## Cards` grammar as 4.2. It is excluded from Feed, Search and Ask results (it is not knowledge, it is the owner's drill file) but included in Spark.

### 4.4 Source note: `## My ideas`

"In my words" on a Unit is the existing `## My ideas` section (written from the inbox `Ideas:` line by `IngestText.sourceNote`). Create it after `## Why I captured this` if missing, else replace its body. Only the owner's words go in.

### 4.5 Topic page: `## My take`

"Your take" on a Topic is a `## My take` section, placed after `## Core Mechanism` (or before `## Sources that built this page`, else at the end if neither exists). Body is the owner's text plus a last line `Edited: 2026-11-12` (ISO date) so the app can show "edited 4d ago". Never reorder or delete other sections. The Mac agent must preserve it (appendix A). Topic pages live in `Knowledge/Topics/`, not in Sources, because that is where Topics already are.

### 4.6 Why a Source note and not a separate file

All of a capture's learning data now sits in one readable note the owner can open in Obsidian: what it says, what the agent extracted, the questions made from it, and the owner's own take. Deleting the note deletes its cards. Nothing hidden to explain or clean up.

---

## 5. Data layer on the phone

### 5.1 Classes

Pure Java (no `android.*` imports, so `build.py`'s JVM step compiles and tests them; add each to the explicit `javac` list in `build.py`):

| Class | Responsibility |
|---|---|
| `Cards` | Parse and write the `## Cards` section and individual card blocks. Surgical transforms: `setState`, `setTake`, `setFlag`, `park`, `edit`, `add`, `remove`. Card id function. |
| `NoteEdit` | Surgical frontmatter key set and section replace. `setKey(text, key, value)`, `setSection(text, heading, body, afterHeading)`. Idempotent. |
| `Sched` | Pure scheduler: next state from a grade, interval preview labels. |
| `Spark` | Today's queue and session state machine from a list of cards. |
| `Feed` | Build the merged feed list from notes, inbox entries and topic activity. |
| `Cites` | Turn `[[path|Title]]` citations in an answer into numbered markers and a source list. |
| `Lane` | Folder to lane mapping (6.2). |
| `CardPrompt` | Prompt text and validation of the model's card JSON (section 7). |

Android side:

| Class | Responsibility |
|---|---|
| `Layout` | `sources(context)` (3.2). |
| `CardStore` | Holds cards in memory, applies changes at once, owns the pending queue and flushes to notes through `VaultWrite`. |
| `CardGen` | The bulk and single-note generator (section 7). |
| `Reminder` | The daily notification (section 11). |
| `MainActivity` and screen classes | The new UI shell and screens (section 10). |

Changed: `VaultStore`, `Vault`, `VaultWrite`, `Ingest`, `IngestText`, `Ui`, `Ask`, `Tagger` (to run card generation), `AndroidManifest.xml`, `res/values*/colors.xml`, `build.py`.

### 5.2 In-memory model

1. `Vault.Graph` (existing) for notes and links. `Vault.parse` also extracts each note's cards (via `Cards.parse`) and the new frontmatter keys, and the vault cache stores them (bump `VaultStore.CACHE` to 3). A note re-read happens only when its size or modified time changes, as today.
2. `InboxFile.parse` of the inbox for captured items.
3. `Feed.build(graph, inbox)` returns the ordered Feed items.
4. `CardStore` exposes all cards across all notes with their state. Grading updates memory first.

Refresh triggers: app start, return to foreground after more than 60 seconds, "Check vault folder now", and the existing `CaptureService.CHANGED` broadcast. "Vault checked 8m ago" is the age of the last completed scan.

### 5.3 Writing: pending queue and flush

Writes must never block the UI.

1. Every change (grade, take, flag, park, edit, keep, new card) is applied to the in-memory card at once and appended to a private file `files/pending.jsonl` as `{path, cardId or key, op, args, ts}`.
2. A flush groups pending changes by note path. For each note: read it, apply every pending transform in order to the fresh text, write with `VaultWrite` compare-before-write. On `Stale`, re-read and re-apply (up to 3 times, 2.3). On success remove those entries. On any failure keep them.
3. Flush runs: 2 seconds after the last change, on `onPause`, at the end of every `Tagger.run` pass, and at app start.
4. Card state and log edits use `VaultWrite.writeNoBackup(c, path, before, after)`: same compare-before-write and read-back verification as `write`, without copying the whole note into `ingest-backup/` every time (some notes are hundreds of KB). Anything that changes more than the `## Cards` section (frontmatter keys, `## My ideas`, `## My take`) uses the normal `write` with backup.
5. Because every change is a surgical transform of fresh text, a Mac-side edit to the same note in the meantime is preserved, not overwritten.
6. If the process dies with pending entries, the next start flushes them. Applying a transform twice is a no-op (all transforms are idempotent, covered by tests).

### 5.4 Failure behavior

- Vault folder not granted or not writable: reading works; Keep, grading, takes and card writes are blocked with "Allow write access to your vault folder in Settings." Spark shows its screen but disables Show answer and grading with that one line.
- Note changed repeatedly (Stale after 3 tries): the change stays pending and shows nothing to the owner until a later flush succeeds. After 24 hours still pending, show one line in Settings: "1 change could not be saved. Open the note to check."
- A `## Cards` section that fails to parse: treat the note as having no cards, never rewrite it, log it. A single malformed card block is skipped and preserved verbatim by every edit.

---

## 6. Derived fields

### 6.1 Unit status (Source)

| Status | Condition | Chip |
|---|---|---|
| Captured | An inbox entry exists with no Source note yet | `Captured` (neutral) |
| Ingested | A Source note exists and `processed` is not `true` | `Ingested` |
| Processed | A Source note exists and `processed: true` | `Processed` (green) |
| Failed | A capture job failed (existing "Needs attention" list from `CaptureService` and `BackgroundJob`) | `Capture failed` (red), shown in Needs attention only |

"Synced to Mac" is not detectable by the phone. Do not show it. An inbox entry that has a matching Source note (same `captured` time and URL) shows once, as the note.

### 6.2 Lane

Lane chip comes from the Source's subject folder (the folder directly under the Sources root):

| Folder | Lane |
|---|---|
| AI Systems, Models & Training, Data & Knowledge, Developer Tools, Research Workflow | Craft |
| Health & Biology, People & Society | Life |

A `lane: craft` or `lane: life` frontmatter key overrides the folder. Topics inherit the lane of their most recent source. People have no lane. Implement as `Lane.of(path, frontmatter)`.

### 6.3 Fields the feed and Unit screens need from `Vault.parse`

`Vault.Note` already has `path`, `title`, `type`, `description`, `updated`, `topics`, `links`, `digest`, `mtime`. Add, in the same parse pass:

| Field | From |
|---|---|
| `sourceUrl`, `author`, `via` | frontmatter `source`, `author`, `via` |
| `captured` | frontmatter `captured` as ISO date or date-time; also the legacy `dd/mm/yy` form (existing `VaultActivity.DMY`) |
| `processed` | frontmatter `processed` equals `true` |
| `people` | frontmatter `people` (kept as its own list as well as merged into `topics`) |
| `why` | body `## Why I captured this`, first 160 characters |
| `words` | word count of `## Raw Content` |
| `curiosity`, `quality`, `kept`, `made-*` | frontmatter (4.1) |
| `cards` | parsed `## Cards` (4.2) |

`source_name` for display is the host of `sourceUrl` without `www.`, or `via` when there is no URL.

### 6.4 Extraction quality

By the length of the note's `## Raw Content` (or, for the phone's own ingest, the saved page text): `full` 1,500 characters or more; `partial` 300 to 1,499; `title_only` under 300; `failed` when the page could not be fetched at all. Written as the `quality` key.

---

## 7. Generating cards and the curiosity question

### 7.1 New sources (during ingest)

Extend the existing `Ingest.SYSTEM` JSON reply with, and only with:

```
"curiosity_question": "one question under 140 characters that opens a gap the page answers; empty if the page is thin",
"recall_cards": [{"q": "...", "a": "...", "basis": "the key claim this tests", "confidence": "high|medium"}]   (0 to 4)
"entities": [{"name": "...", "kind": "model|tool|org|dataset|paper|other"}]   (0 to 4, Phase 6)
```

Prompt rules: questions must be answerable from the page alone, specific, one idea each, never "what is the main idea". Answers are one to two sentences in the model's own words, no quote over 12 words. A card is allowed only if its basis claim is high or medium confidence. Empty arrays are correct for thin pages. Never pad.

In `Ingest.process`, after the existing `source` step, add a recorded step `cards` that adds the `## Cards` section and the `curiosity`, `quality` and `made-*` keys to the note the step just wrote (so it can be done in the same write as the note when possible, otherwise as a following `NoteEdit`). Resume-safe like the other steps.

### 7.2 Existing sources (bulk generation)

All existing Source notes get cards. `CardGen.run(context)` is a resumable background job.

Selection: every Source note (not `index.md`, not `Inbox.md`, not `My cards.md`) with no `quality` key. Newest `captured` date first. The `quality` key being present is what marks a note done, so the job resumes after any interruption and never repeats work.

Per note:
1. Read the note (`VaultWrite.read`). Input text: `## Raw Content` up to 12,000 characters, plus the Key claims, `## Why I captured this` and `## My ideas`. If there is no raw content, use the Key claims and the summary only and mark quality `partial`.
2. If under 300 characters of usable text: write `quality: title_only`, no cards, no curiosity. Done.
3. Otherwise one OpenRouter call with `cardModel` asking only for `curiosity_question` and `recall_cards` (same rules as 7.1).
4. Validate (7.3). Write `## Cards`, `curiosity`, `quality` using `NoteEdit` and normal `VaultWrite.write` (with backup; this is a one-time first edit of each note).
5. A failed call (network, provider error, bad JSON) writes nothing and the note is retried on a later run. Three failures in a row for one note: write `quality: failed` so it is not retried forever. Provider-wide errors (bad key, quota, 429) end the run and show in Settings.

Pacing and cost control:
- Runs inside the existing `Tagger.run` background job, at most 10 notes per run, stopping when Android halts the job (`Tagger.halt`), the same way ingest does.
- Starts only after the owner confirms once. The confirmation screen (Settings row "Make cards for existing notes") shows the note count, total characters and an estimated token count computed from the actual vault, the model name, and "Estimated cost" as a range from OpenRouter's published price for `cardModel` (fetched once, cached; if unavailable, show tokens only). Buttons `Start` and `Not now`. A Pause switch stops it any time.
- Progress row in Settings: "Making cards: 120 of 312 notes". When done: "Cards made for 312 notes".
- Rough size for planning: 312 notes at 12,000 characters or less each is about 1 million input tokens and about 150 thousand output tokens in total. Show the live estimate, do not trust this figure.
- New notes from ingest (7.1) are never in the backlog because they already have `quality`.

### 7.3 Validation (in `CardPrompt`, pure and tested)

Trim and collapse whitespace; drop cards with empty q or a; drop answers that equal the question; reject questions over 240 characters; reject any q or a containing `[[`, a raw URL, or a line starting `###`; drop duplicate ids; cap at 4; require `confidence` of `high` or `medium`; curiosity question must end with `?` and be under 160 characters or it is dropped. If no card survives, write `quality` only.

### 7.4 Skips

Image, PDF, voice-note and other non-link captures get no cards in v1 (written as `quality: title_only`).

### 7.5 Regeneration

Not in v1. Flag marks a card bad and removes it from rotation; Edit rewrites it. To regenerate a whole note, the owner deletes its `quality` key and `## Cards` section.

---

## 8. Spark: scheduler, queue and session

### 8.1 Card state

Per card, from its `State` line: `due`, `interval` (days), `ease` (default 2.5), `reps`, `lapses`. No `State` line means a new card.

All cards are active, including bulk-generated ones. A card is excluded from the queue when any holds: it has `Flag: yes`; `Parked` is in the future; its note no longer exists; its note is `My cards.md` with no `Q` or `A`.

### 8.2 `Sched.next(state, grade, now)`

Pure function. Deterministic, no fuzz.

| Grade | New card (reps 0) | Learning or review card |
|---|---|---|
| Again | `interval 0`, `due = now + 10 min`, `ease = max(1.3, ease - 0.2)` if it had been reviewed before, `lapses +1` if it had reps > 0, `reps = 0` | same |
| Hard | `interval = 1`, `reps = 1` | `interval = max(1, round(interval * 1.2))`, `ease = max(1.3, ease - 0.15)`, `reps +1` |
| Good | `interval = 1`, `reps = 1` | reps 1 becomes 3 days; reps 2 or more becomes `round(interval * ease)`; `reps +1` |
| Easy | `interval = 4`, `reps = 1` | `interval = round(interval * ease * 1.3)`, `ease = ease + 0.15`, `reps +1` |

For Hard, Good and Easy, `due = now + interval * 24 h`. A card that was Again and then answered Good goes to 1 day.

`Sched.preview(state, grade)` returns the label on each grade button: Again is always `later today`; others are `1d`, `3d`, `2w`, `3mo` (days under 14, weeks under 60, months after). Labels come from the same function as the real update.

SM-2 is the first scheduler. `Log` lines keep `grade`, time and seconds so FSRS can replace it without a migration.

### 8.3 Daily cap and queue

- Cap: preference `cardsPerDay`, default 7, range 3 to 20.
- A "day" is the device's local calendar day.
- `reviewedToday` = number of distinct cards with a `Log` entry whose local date is today. An Again repeat the same day does not count twice.
- `remaining = cap - reviewedToday`. If `remaining <= 0` the cap is reached.
- Build the queue when Spark opens and after each grade:
  1. Due cards (`due <= now`), oldest `due` first.
  2. Then new cards: cards of notes with `kept: true` first (oldest keep order is not stored, so order by note `captured` newest first), then all other new cards, cards from the newest note first, a note's cards together in file order.
  3. Take the first `remaining`.
- An Again card re-enters the end of the session after 10 minutes and uses no extra cap.
- With about 900 generated cards and a cap of 7, the backlog of new cards takes months to introduce. That is intentional pacing. Due cards always come first, so when the owner is behind, new cards pause. Never show a count of overdue or unintroduced cards. The Spark strip and Spark screen show only today's allowed count.

### 8.4 Spark screen states

| State | Condition | Content |
|---|---|---|
| Question | A card is current, answer hidden | Progress bar, "Card 3 of 7, about 2 min left", lane chip, source title (opens the Unit), question, "Show answer". No Guess/Unsure/Sure. |
| Revealed | After "Show answer" | Answer, then **Your note**: the card's `Take` (tap to add or edit; saves to the card), a "Source" row that opens the Unit, "How well did you recall it?" with four grades and preview labels. Tapping a grade never requires a Take. |
| Caught up | `reviewedToday >= 1`, queue empty, cap not reached | "All caught up." Next due time. "Write your own card". |
| No cards | The vault has zero active cards (bulk generation not started or still empty) | If generation has not been started: "No cards yet. Make cards from your notes." with `Make cards` (opens the 7.2 confirmation). If running: "Making cards: 120 of 312." Plus "Write your own card". |
| Cap reached | `reviewedToday >= cap` and more remain | "Done for today. You reviewed N cards." No count left. "Write your own card" allowed. |

Footer row on Question and Revealed: `Park 24h`, `Flag`, `Source`, `Edit`. Each is 48 dp high, one line. `Flag` writes `Flag: yes` and moves on. `Park 24h` writes `Parked: <now + 24h>` and moves on. `Edit` opens a sheet with Question, Answer and Take; saves to the card block.

**Owner's own cards and takes.**
- "Write your own card" opens a sheet: Question, Answer, Your note (optional), and a source picker (search a note, or "No source"). With a source, the card is added to that note's `## Cards` with `Origin: mine`. With none, it goes into `Knowledge/Sources/My cards.md` (created if missing). It enters the queue as a new card.
- From the note reader (full text), selecting text offers "Make card" in the selection menu. It opens the same sheet with the Answer prefilled from the selection and the source set to the open note.
- On the Unit's card list, each card shows Q, A and Take, and every field is editable.

Time estimate: 30 seconds per card, rounded up to whole minutes.

### 8.5 Rules from the brief that are enforced

No streaks. No overdue count. No retention scores. Daily load capped. Every card shows its source and a way to open it. Cards from low-confidence claims are never created. One notification per day at most.

---

## 9. Feed, Search, Ask and detail screens

### 9.1 Feed

Header: "Feed", subtitle "Vault checked <age> ago", search icon (opens the Search tab). Offline banner when `ConnectivityManager` reports no network: "Offline. Changes save to your vault and sync later." Capture, Spark and edits still work offline. Only LLM calls need the network.

Filter chips, single select: `All`, `Craft`, `Life`, `Sources`, `Topics`, `People`. `Craft` and `Life` filter by lane across kinds. `Sources`, `Topics`, `People` filter by kind. Selection persists in preferences.

Order: newest first by `captured` (Sources), by newest source added (Topics), by note modified time (People). Captured-only items sort by their `Captured:` time.

| Item | Shows |
|---|---|
| Source card | Status chip, lane chip, age, title, source name, curiosity block (only if `curiosity` exists), "Why saved: ..." (the note's `## Why I captured this` or the inbox `Why:`), the "Made ..." line (only when `made-*` exist), actions `Keep`, `N cards` (opens the Unit's cards), `Ask`. A Captured or Ingested item shows its waiting line ("Imported note. Not processed yet.") and hides `N cards`. |
| Topic card | `Topic` chip, "<N> new sources", title, `Ask`. A topic gets a card when at least 2 of its sources have `captured` in the last 7 days. One card per topic, at the time of its newest such source. |
| Person card | `Person` chip, name, "Mentioned in N notes". Shown only under the `People` filter. |

`Keep` sets `kept: true` on the note (normal `write` with backup). It is a priority marker: that note's cards are introduced before other new cards (8.3). It does not gate card creation. Tapping `Kept` removes the key. `N cards` shows the note's generated and own card count.

Spark strip above the list, on `All` only: "Morning Spark. 6 cards due, about 3 min. Start". Hidden when `remaining` is 0 or there are no cards.

Needs attention (above the list when present): failed captures from the existing job store with `Retry`, `Edit link`, `Dismiss`. Move the existing logic out of `SetupActivity`, do not rewrite it.

Empty state: "Nothing here yet. Share a link or text to Sparkbox from any app. It shows up here." and `Check vault folder now`.

Lists use `ListView` with a `BaseAdapter` and view types (framework class, no `RecyclerView`).

### 9.2 Search

Keep `Vault.search`, `VaultStore.semantic`, `Vault.blend`. Add:

- Placeholder exactly "Search notes, topics, people".
- Chips `All N`, `Sources`, `Topics`, `People`. Kind from folder: Sources root is Source, `Knowledge/Topics/` Topic, `Knowledge/People/` Person, everything else (Learnings, Entities) under `All` only. `My cards.md` is excluded.
- Result row: kind chip, lane chip (sources, topics), age, title, source name or "3 sources", one-line snippet (about 90 characters of the digest centered on the first matching word, matching word in semi-bold).
- Debounce typing 250 ms. Word results show at once, meaning results merge in when they arrive. No key or offline: word results only, no error shown.
- No results: "No results. Nothing matches this word. Try another, or ask your vault." and `Ask your vault` (opens Ask with the query).

### 9.3 Unit (Source detail)

Sections in order, hiding empty ones:

1. Header: status chip, lane chip, age. Title. Source name (opens `sourceUrl`). Overflow: `Open in Obsidian`, `Open original`.
2. Curiosity prompt block (`curiosity`).
3. Summary: `## Summary` if present, else the first Key claim, else `description`.
4. Key notes: Key claims, one line each; a small `low` label only on low-confidence ones.
5. **In my words**: a text box bound to `## My ideas`. Save button appears only when changed.
6. "Added from this note": Topics, People, Entities with a "New" badge when in `made-*-new` and a count line ("Topic, 4 other sources"). The header opens Capture Map.
7. Cards: "N cards" row opens the card list (Q, A, Take, flagged state; all editable; "Add card" button).
8. Full text: collapsed row "Full text, 850 words" (existing reader, with "Make card" on text selection).
9. `Ask about this`: opens Ask with the note preloaded (existing `AskActivity.QUESTION` extra).

Unit, not processed: status `Ingested` or `Captured`. Banner: "Not processed yet. The agent on your Mac has not read this note." (phone ingest off) or "Not processed yet. It will be processed when you are online." (ingest on and key set). Shown: Description, Topics (frontmatter), Cards if any exist, Full text, Ask about this. No curiosity, key notes or entities.

Thin captures (`quality` is `title_only` or `failed`): banner "Only the title was saved. No cards were made." above whatever sections exist.

### 9.4 Topic page and Capture Map

Topic page, in order: chips (`Topic`, lane), title, core sentence (first sentence of `## Core Mechanism`), `Ask about this`, "Your take" card (reads and edits `## My take`, "edited 4d ago" from `Edited:`, `Edit` opens a sheet), then one list card with rows `Appears in` (Sources linking the topic), `Related topics` (`related-topics`), `People mentioned`, `Open questions` (bullets under `## Contradictions / Open Questions`), each a count and chevron. `Plan to try` is removed in v1 (its Apply flow is cut).

Capture Map ("What was added"): from Unit "Added from this note" or the Feed "Made ..." line. Source card, then `Topics`, `People`, `Entities` (each hidden when empty), rows with name, count and a "New" badge when created by this note. Footer `View source note`, `Done`. Data: `made-*`; fallback to the note's `topics` and `people` frontmatter without badges.

### 9.5 Ask

Existing: `Ask.run`, tools, history, citations as `[[path|Title]]`. Changes:

1. Header subtitle "<N> notes in your vault" (graph size, excluding catalogs and `My cards.md`).
2. `Cites.number(answer)`: scan `[[path|Title]]`, number by first appearance (de-dup by path), replace each with `[n]`, return the ordered source list. Render markers inline and the list as cited-note rows (title wrapping to two lines, meta "Craft, example.com, 3h ago" or "Topic, 3 sources", chevron).
3. "Single source: 1 note supports this." warning when exactly one distinct note is cited.
4. "Not in your knowledge base" state: if the trimmed answer starts with that exact phrase (the system prompt already demands it), show the state card with `Search vault` (opens Search with the question) and `Add a note` (opens `QuickActivity`). Do not show the model's free text. List any closest notes it named under "Closest notes".
5. Streaming: status line "Reading 2 notes..." (existing `Status.say`), live text, `Cancel`. Cancel sets a volatile flag checked by `Model.stream` and `Ask.run` between chunks and tool steps, closes the connection, keeps partial text plus "Cancelled."
6. Error: "Could not finish the answer. Reading your vault failed." (or the provider message) with `Retry` that resends the last question.
7. Offline: input disabled, banner "You are offline", history readable. Detect before send and on `UnknownHostException` or timeout.
8. Keep the existing persisted history (`AskActivity.save()`).

Ask also reads the cards sections, "Take" lines and `## My ideas` as the owner's own words, since they are plain note text. The system prompt already treats the user's own words as instructions and context; add one line telling Ask that `Take:` and `## My take` and `## My ideas` are the owner's opinions, not sourced facts, and must be labelled as "your take" when cited.

---

## 10. Frontend structure and UI system

### 10.1 Shell

- Launcher activity becomes `MainActivity`: four tabs (Feed, Spark, Search, Ask), bottom nav, and an in-activity back stack for detail screens (Unit, Topic, Capture Map, Settings, card list, card sheet).
- Views are built in code through `Ui`, as the project does today. No XML layouts for screens (widget layouts in `res/layout` stay).
- Each screen class has `View build()`, `void onShow()`, `void onHide()`. `MainActivity` owns navigation, theme, insets and the nav bar. Tab state (scroll, filter, search text) survives tab switches and rotation.
- `SetupActivity` stops being the launcher. Its first-run checklist (vault folder, inbox file, key) is a gate: until a vault folder and inbox file are chosen, `MainActivity` shows it (restyled by tokens), then Feed. Its advanced controls (ingest toggle and model, describe photos, widget pinning, usage counters, recovery exports) move behind one Settings row "Capture and ingest". That screen is not in the approved designs; restyle with tokens only.
- `AskActivity` stays as an entry point for widgets, shortcuts and deep links, with its UI extracted into an `AskScreen` view used by both.
- Keep `VaultActivity`'s note reader and folder browser, reached from "Full text" and from Settings as "Browse vault". Remove its old home screen.

### 10.2 Design tokens

Replace the graphite and green set in `Ui` and `res/values*/colors.xml` with the approved palette. Light primary is `#047857`, not the `#059669` in the designer's table, because white text on `#059669` fails 4.5:1 and the canvas was audited with `#047857`.

| Token | Dark | Light |
|---|---|---|
| bg (canvas) | `#0F141B` | `#F8FAFC` |
| card | `#171C23` | `#FFFFFF` |
| raised | `#1E242D` | `#F1F5F9` |
| border | `#28323F` | `#CBD5E1` |
| primary | `#10B981` | `#047857` |
| on-primary | `#0F141B` | `#FFFFFF` |
| primary container | `#064E3B` | `#D1FAE5` |
| on-primary container | `#6EE7B7` | `#065F46` |
| text | `#F1F5F9` | `#0F172A` |
| text secondary | `#94A3B8` | `#475569` |
| warning / bg | `#F59E0B` / `#2A2015` | `#B45309` / `#FEF3C7` |
| error / bg | `#FB7185` / `#2A1219` | `#BE123C` / `#FFE4E6` |

`Ui` fields map: `bg`, `surface`=card, `raised`, `ink`=text, `sub` and `muted`=text secondary, `line`=border, `accent`=primary. Add `onAccent`, `container`, `onContainer`, `warn`, `warnBg`, `err`, `errBg`. Widget colors (`w_*` and `values-night`) change to the same hex values.

### 10.3 Type, shape, spacing

- Font: system default (`sans-serif`, `sans-serif-medium` for 500, bold for 600 and 700). No bundled font in v1.
- Sizes in sp: 12 label and chips, 13 meta, 15 body, 17 card title, 20 screen title and question, 24 topic title. Line height 1.3 body, 1.2 titles. Sizes scale with system font size. No fixed-height text containers.
- Radius: card 12 dp, chip 12 dp (24 dp high), button and input 24 dp, inner block 8 dp. Spacing in multiples of 4 dp, side padding 16 dp, card padding 16 dp, gap between cards 12 dp.
- Touch targets 48 dp minimum, including filter chips (36 dp chip plus hit padding), icon buttons and the four Spark footer buttons.
- Bottom nav 64 dp, icon 24 dp over a 12 sp label, selected in primary weight 600. Header 64 dp, title 20 sp bold, optional 12 sp subtitle.
- Theme (System, Dark, Light): wrap the context in `attachBaseContext` with a `Configuration` whose `uiMode` night bits are forced when the setting is not System. `Ui` already reads `uiMode`. Recreate the activity when the setting changes.
- Target 35 enforces edge-to-edge: apply `WindowInsets` to the header and nav bar in `MainActivity`.

### 10.4 Reusable components

`Chip(kind)`, `Card`, `PrimaryButton`, `SecondaryButton`, `ListRow(title, meta, chevron)`, `Sheet` (bottom sheet for edit, write-card, take), `StatusBanner(kind, text)`, `ProgressBar`, `EmptyState(title, body, action)`. Each takes colors from `Ui` only.

---

## 11. Settings and notifications

| Section | Row | Behavior |
|---|---|---|
| Vault | Vault folder | Shows `VaultStore.label`. Opens the existing picker (write access). |
| Vault | Check vault folder now | Runs `VaultStore.scan`. Shows "Checked 8m ago. 455 notes." |
| Ask | Model | Text row, default `anthropic/claude-haiku-5.5`. Key `askModel`. |
| Ask | OpenRouter API key | "Saved" or "Not set". Tap to enter or replace. `Model.saveKey` (Keystore). Never displayed. |
| Spark | Cards per day | Stepper 3 to 20, default 7. |
| Spark | Daily reminder | Switch plus time (default 08:30). |
| Spark | Make cards for existing notes | Starts the 7.2 confirmation. After start shows progress "Making cards: 120 of 312 notes", a Pause switch, and the card model row. |
| Theme | System, Dark, Light | Key `theme`. |
| More | Capture and ingest | Existing advanced UI (10.1). |
| More | Browse vault | Existing folder browser. |
| Footer | "Sparkbox 1.0" | Version from the manifest. |

The "Make cards for existing notes" row and the card model row are additions to the approved Settings design. Tell the designer (section 17).

Reminder (`Reminder`): `AlarmManager.setWindow(RTC_WAKEUP, time, 15 min)`, inexact, no exact-alarm permission. Reschedule on boot (`RECEIVE_BOOT_COMPLETED` receiver), on app start and after each fire. On fire compute today's allowed count; if 0, post nothing; else one low-importance silent notification "6 cards due, about 3 min" that opens Spark. At most one per day. Ask for `POST_NOTIFICATIONS` the first time the owner turns the reminder on.

---

## 12. Entities (Phase 6, optional)

The designs show Entities. The vault has none. The UI hides any Entities line or section when the count is zero, so this can ship last or be cut.

- Entity page: `Knowledge/Entities/<Name>.md`, frontmatter `type: entity`, `kind: model|tool|org|dataset|paper|other`, `appears-in`, `last-updated`; body `## What it is` and `## Where it appears`. Created and updated additively by `Ingest`, like People pages. Names pass `IngestText.safe`; match existing by lowercase name.
- Add an `Entities` table to `Knowledge/index.md` (create the heading once) and append `[INGEST]` log lines.
- Needs the Agents.md change in appendix A.

---

## 13. Code changes by file

| File | Change |
|---|---|
| `AndroidManifest.xml` | `MainActivity` is the launcher. `SetupActivity` loses its launcher filter but stays exported. Add `Reminder` and boot receivers, `RECEIVE_BOOT_COMPLETED`. |
| `build.py` | Add new pure classes to the `javac` list and `CaptureTest` run. |
| `VaultStore.java` | Skip `*.sync-conflict-*`. Read `Layout.sources`. Bump `CACHE` to 3 and persist the new `Note` fields and parsed cards. |
| `Vault.java` | Parse 6.3 fields and cards. `Graph.resolve` `Sources/` and `Knowledge/Sources/` fallback (3.2). |
| `VaultWrite.java` | Add `writeNoBackup` (5.3). |
| `Ingest.java`, `IngestText.java` | Prompt extension, `cards` step, `made-*` bookkeeping, path from `Layout.sources`, optional entities. Pure sidecar-free writers for the new section and keys. |
| `Tagger.java` | Run `CardGen.run` after ingest and before embedding, bounded per 7.2. |
| `Ask.java`, `AskActivity.java` | Cancel flag, `Cites`, not-in-KB detection, `Layout` for the inbox path, own-words prompt line, extract `AskScreen`. |
| `Ui.java`, `res/values*/colors.xml`, `styles.xml` | Tokens and components. |
| `SetupActivity.java` | Split: first-run gate, needs-attention list (to Feed), advanced controls (behind Settings). |
| `VaultActivity.java` | Remove home screen. Keep reader (add "Make card" selection action), folder browser, links. |
| New | `MainActivity`, `FeedScreen`, `SparkScreen`, `SearchScreen`, `AskScreen`, `UnitScreen`, `TopicScreen`, `CaptureMapScreen`, `SettingsScreen`, `CardsScreen`, `Layout`, `Reminder`, `CardStore`, `CardGen`, `Cards`, `NoteEdit`, `Sched`, `Spark`, `Feed`, `Cites`, `Lane`, `CardPrompt`. |
| `tests/CaptureTest.java` | New check groups (section 14). |

The package stays `me.venkatesh.saveobsidian` and the APK name `Save-to-Obsidian.apk` so updates stay installable.

---

## 14. Testing

JVM checks (extend `tests/CaptureTest.java`, run by `build.py`):

- `Cards`: parse and write round trip; continuation lines; malformed block skipped and preserved verbatim; every transform (`setState`, `setTake`, `setFlag`, `park`, `edit`, `add`, `remove`) changes only its block and is idempotent; id stability and normalization; `Log` cap at 50; section absent and present.
- `NoteEdit`: set and replace frontmatter keys without touching other keys; add `## Cards` after the last section; `## My ideas` and `## My take` insertion positions; running twice changes nothing; CRLF and no-frontmatter notes.
- `CardPrompt`: every validation rule in 7.3, bad JSON, fenced JSON, empty arrays.
- `Sched`: each grade for a new card, first review, mature card, lapsed card; ease floor 1.3; labels; preview equals real result.
- `Spark`: queue order (due first, kept notes' new cards, newest-first), cap arithmetic including repeat grades, parked, flagged and missing-note exclusion, each session state, no overdue count anywhere.
- `Feed`: ordering; captured and matching note shown once; topic-card rule; filters; People hidden in `All`; `My cards.md` excluded.
- `Cites`: numbering, duplicates, malformed link left as text, no citations, `\|` in table cells.
- `Lane`: folder mapping and frontmatter override. `Layout`: both roots.
- `Vault.parse`: new fields on a modern note, a legacy `dd/mm/yy` note, a note with no frontmatter. `Graph.resolve` with `Sources/` and `Knowledge/Sources/` forms.
- Concurrency: apply a pending transform on text that an external edit changed elsewhere; the external edit survives.

Device checks (OnePlus over USB; commands in project memory `sparkbox-build-and-test.md`; delete test captures afterwards, the inbox is real):

1. Launch with the old `Sources/` layout, then with `Knowledge/Sources/`: Feed, Search, Ask and links all work in both.
2. Share a link: Captured, then Ingested, then Processed, with cards and curiosity on the new note; "Made ..." matches the vault diff.
3. Bulk generation: start from Settings, confirm estimate, pause, kill the app, resume; progress is correct, no note is processed twice, notes without text become `title_only`.
4. Spark: grade a session; the note's `State` and `Log` lines change and nothing else in the file; kill the app mid-session, relaunch, no loss. Add a Take, a Flag, a Park and an own card; check the note text in Obsidian.
5. Edit the same note on the Mac while the phone has pending changes: both edits survive.
6. Airplane mode: Feed, Spark, Take edits work; Ask shows offline; Search shows word results only.
7. Theme System, Dark, Light; font scale 1.3; no clipped text; 48 dp targets everywhere.
8. Settings: cap 3 changes the queue; reminder time reschedules; reminder fires once and not when nothing is due.
9. Ask: numbered markers match the list; Cancel mid-stream; Retry after a bad key; a not-in-KB question.
10. Cold start to first Feed paint under 1 s on the 455-note vault.

Acceptance for each phase: its device checks pass and `python3 build.py` is green.

---

## 15. Delivery phases

| Phase | Deliverable | Depends on | Done when |
|---|---|---|---|
| 0. Foundation | Tokens, theme override, insets, components, `MainActivity` shell with four tabs, `Layout`, `Graph.resolve` fallback, scanner skips | none | App launches dark and light in both vault layouts. Old features untouched. |
| 1. Feed and Unit | `Vault.parse` fields, `Lane`, `Feed`, Feed screen, Needs attention moved, Unit (both frames), Capture Map from frontmatter | 0 | Match the approved screens with real data. |
| 2. Search and Topic | Search screen, Topic page with My take, `## My ideas` edit | 1 | Match designs. Edits verified as text in Obsidian. |
| 3. Ask | `AskScreen`, `Cites`, cancel, retry, offline, not-in-KB | 0 | All five Ask states match. |
| 4. Cards data | `Cards`, `NoteEdit`, `Sched`, `Spark`, `CardStore`, pending queue, `writeNoBackup`, ingest prompt and `cards` step | 1 | JVM checks green. New ingests carry cards. Surgical-edit check passes. |
| 5. Bulk generation | `CardGen`, `CardPrompt`, Settings confirmation and progress, `Tagger` hook | 4 | 312 existing notes processed on a real run, resumable, estimate shown first. |
| 6. Spark UI and Settings | Spark screen (all states), takes, own cards, "Make card" selection, Settings, reminder, Feed strip, Keep | 4, 5 | All Spark and Settings designs match. Cap and reminder verified on device. |
| 7. Entities (optional) | Ingest entities, `Knowledge/Entities`, Capture Map and Unit rows, Agents.md patch | 1 | Entities appear on new ingests. UI unchanged at zero. |

Phases 1, 2 and 3 can run in parallel after 0. Phases 4 to 6 are last because they add the only new write format.

---

## 16. Decisions to confirm (with defaults)

| # | Decision | Default |
|---|---|---|
| 1 | Move `Sources/` to `Knowledge/Sources/` | Yes, done by the owner in Obsidian with link updating. The app works before and after. |
| 2 | Inbox file moves with the folder | Yes, to `Knowledge/Sources/Inbox.md`. The Mac agent and any Telegram bot need the new path. |
| 3 | Cards stored inside each Source note under `## Cards` | Yes. No other app data folder. |
| 4 | Bulk-generate cards for all existing sources | Yes, after one confirmation showing an estimate. Default model `anthropic/claude-haiku-5.5`. |
| 5 | Keep button meaning | Priority marker only. All cards are active; kept notes' new cards come first. |
| 6 | Where the owner's take lives | On the card (`Take`), on the note (`## My ideas`), and on the topic page (`## My take`). |
| 7 | Scheduler | SM-2 variant (8.2). FSRS later. |
| 8 | `Plan to try` on the Topic page | Removed. Apply is cut. |
| 9 | Entities | Optional Phase 7. UI hides them at zero. |
| 10 | Light primary | `#047857`. |
| 11 | Font | System font. Plus Jakarta Sans optional later. |
| 12 | Mac agent generates cards | No, in v1 only the phone does. The agent must preserve `## Cards`. |
| 13 | Cards per day | One cap, default 7, range 3 to 20. |
| 14 | Review history | Last 50 entries per card in `Log`. Older history is dropped. |

---

## 17. Messages to others

To the designer (none blocks the build):

- "Synced to Mac" cannot be shown. Statuses are Captured, Ingested, Processed.
- `Plan to try` is cut. Drop it from the final package.
- Add a "Take" field to the revealed Spark card ("Your note", tap to edit), an Edit sheet with Question, Answer and Take, and a "Write your own card" sheet. One extra Settings row "Make cards for existing notes" with its confirmation and progress. The "Capture and ingest" settings screen reuses tokens with no new design.
- Light primary is `#047857`.

### Appendix A: patch for `Agents.md` (needs the owner's OK; it is the Mac agent's canonical file)

1. Replace every `Sources/` path with `Knowledge/Sources/` (Source File Format, Inbox Entry Format, Ingest, Process inbox, the folder table).
2. Add under "Source File Format":

```markdown
Sparkbox adds these to Source notes. Preserve them exactly when you edit a Source note, never rewrite or summarize them, and do not generate them yourself:
- Frontmatter keys `curiosity`, `quality`, `kept`, `made-topics-new`, `made-topics-updated`, `made-people-new`, `made-people-updated`, `made-entities-new`, `made-entities-updated`.
- A `## Cards` section at the end of the note: blocks headed `### c_<id>` with lines `Q`, `A`, `Basis`, `Take`, `State`, `Log`, `Origin`, `Parked`, `Flag`. `Take` is the owner's own words.
- The `## My ideas` section is the owner's own words.
`Knowledge/Sources/My cards.md` is the owner's drill file, not a source. Do not index, lint or ingest it.
```

3. Under Knowledge Page Formats add: Topic pages may contain a `## My take` section written by the owner in Sparkbox. Preserve it.
4. Optional (Phase 7): Entity page format `Knowledge/Entities/[Name].md`: frontmatter (type: entity, kind, appears-in, last-updated), `## What it is`, `## Where it appears`.

---

## 18. Risks

| Risk | Mitigation |
|---|---|
| The phone and the Mac agent both edit a Source note | Surgical transform of freshly read text, `Stale` re-apply, backups, read-back verification, debounced writes, conflict copies ignored and surfaced. |
| Review writes rewrite large notes | Batched and debounced. `writeNoBackup` avoids copying. Only the `## Cards` section changes. |
| Bulk generation cost | One confirmation with a computed estimate, 12,000 character cap per note, cheap default model, Pause, 10 notes per run, resumable, never repeats. |
| Poor cards from the model | Medium or high claims only, 4 per note, validation (7.3), Flag and Edit always available. |
| 900 cards flood the owner | Daily cap, due-first ordering, new cards pause when behind, no counts of what is waiting. |
| The move breaks links | Done in Obsidian with link updating. App resolves both forms meanwhile. |
| New launcher breaks widgets, tiles, shares | Keep `SetupActivity` and `AskActivity` exported with current intents. Test in device check 2. |
| No `RecyclerView` | `ListView` with view types. A few hundred items is fine. |
| Edge-to-edge on target 35 | Insets in `MainActivity`, checked in device check 7. |
