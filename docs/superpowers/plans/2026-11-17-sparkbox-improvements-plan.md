# Sparkbox improvements: design and implementation plan

Date: 2026-11-17. Inspected baseline: `main`, `5a70917` (`fix: persist processing and read source images`).

Status: implemented in Sparkbox 1.9.2 (version code 14). Automated checks, lint and signed release build pass. Venkatesh verified the APK and confirmed it works; see [phone checklist](../../2026-11-17-improvements-phone-checklist.md) and [build validation](../../2026-11-17-improvements-validation.md).

## Confirmed behavior

1. Feed cards open a full Markdown editor, not only the existing personal-section editor.
2. Keep bookmarks whole notes: Sources, Topics and People. Kept notes have an easy collection view. Keep changes neither Feed ordering nor Spark review ordering.
3. Card management supports creation, editing and deletion, including AI-generated cards. A Topic shows cards on itself and linked notes, grouped by the note that owns each card. Adding from a Topic creates a card on that Topic. Editing or deleting a linked card changes its original note.
4. Model selection becomes a searchable OpenRouter list wherever a model is currently configurable.
5. Text and voice capture retain Inbox as the default, with Existing note and New note destinations. Existing-note capture appends the original text/transcript. New personal notes live in `Knowledge/Topics/`; a supplied title is used, otherwise AI titles the note. Capture does not automatically rewrite the selected note.
6. Article images must inform processing. The latest commit already implements this path; validate and repair gaps rather than build a second path.

## Priority and release order

| Order | Deliverable | Reason | Relative effort |
|---|---|---|---|
| Verification first | Qualify existing article-image processing | Already implemented; establish what remains before allocating new work | Small automated review; device/provider checks required |
| 1 | Full Markdown editor | Direct control over saved knowledge; highest agreed feature priority | Medium |
| 2 | Keep bookmarks and collection | Frequent retrieval improvement; existing metadata and actions are reusable | Small–medium |
| 3 | Card management at each note level | Most persistence and editing behavior already exists | Medium |
| 4 | Searchable model picker | Replace one shared dialog; preserve existing settings | Small–medium |
| 5 | Text/voice capture destinations | Broadest change: queue, recording, transcription, destination writes and crash recovery | Large |

These are dependency and scope estimates, not measured delivery times. Each deliverable should be independently reviewable and releasable. Image defects that lose content or prevent processing block the affected processing release.

## Current implementation and reusable pieces

| Area | Evidence in current code | Change needed |
|---|---|---|
| Feed actions | `FeedView.kt:287` renders cards; `FeedScreen.java:53` handles actions. `canEdit` currently means Source lane/trash actions, not an editor. | Add a visible overflow action and full-editor navigation for persisted notes. |
| Personal editing | `UnitScreen.java:110` edits My ideas; `TopicScreen.java:87` edits My take through `Edits.section`. | Preserve these focused editors; add full Markdown editing separately. |
| Write safety | `VaultWrite.java:65` compares expected text, backs up and verifies read-back. `writePinned` checks vault identity. | Reuse for editor and capture writes. Preserve drafts on conflict or uncertain write. |
| Keep | `FeedView.kt:334` limits Keep to notes with recall cards. `CardStore.java:51` rebuilds kept paths only while scanning notes with cards. | Remove dependency on recall cards; load bookmarks from all eligible notes. |
| Keep semantics | `Spark.java:68` prioritizes fresh cards from kept notes. Feed itself sorts chronologically. | Remove the Spark priority effect and obsolete “up first in Spark” text. |
| Feed coverage | `Feed.java:126` builds Sources and recent active Topics; People are appended separately. | A Kept collection must query all bookmarked vault notes, including older Topics absent from the normal stream. |
| Cards | `CardsScreen.java:23` already lists, edits and adds cards, but Delete appears only for `mine()`. `CardSheet` and `CardStore` handle changes. | Expose existing operations across note types; allow deletion of generated cards. |
| Topic scope | `Study.topicOf` derives linked-note paths but does not explicitly add the Topic itself. `Study.filter` excludes flagged/parked cards. | Include direct Topic cards in study; use an unfiltered ownership list for management. |
| Models | `SettingsScreen.java:149` is one shared manual-ID dialog. `ScoutUsage.java:22` fetches and caches selected-model metadata. | Add a cached catalog and shared searchable picker. |
| Capture | `QuickActivity.java:81` queues text; `RecordService.java:112` queues voice; `Tagger.java:149` transcribes voice into Inbox. | Persist destination alongside capture; route to the chosen note without automatic ingest. |
| Images | `Extract.images` selects article figures; `Visuals.read` checkpoints paid results; `Ingest` feeds visual evidence into planning. | Qualify end-to-end behavior and recovery. |

## Architecture choice

Use the existing Java actions/core and Compose UI, Markdown notes, private pending files, `VaultLock` and verified writes. No new database, scheduler, UI framework or networking dependency is needed.

Alternatives considered: a broader screen/state rewrite would expand risk without helping these six requests; a separate bookmark database would duplicate knowledge already represented by note metadata. Both are excluded.

New helpers are justified only for shared catalog parsing or destination routing that existing classes cannot express cleanly. Do not introduce a generic editor engine or capture workflow framework.

## 0. Qualify article-image processing

Files: `Extract.java`, `Fetch.java`, `Visuals.java`, `Content.java`, `Ingest.java`, `Model.java`, existing capture checks.

1. Trace an article figure through extraction, download, vision interpretation, checkpoint storage and the ingest prompt. Confirm the saved Source contains its visual contribution, with AI interpretation distinguished from source text.
2. Extend existing fixture checks only for missing coverage: relative URLs, lazy loading, `srcset`, captions, duplicate images, irrelevant logos, no-image articles and image-only content. Inspect `<picture>`/SVG cases; document unsupported formats or make a focused extraction fix when a real article requires it.
3. Verify interrupted batches. Current code reads at most three new media items per pass. A fourth image must be processed by a successor job; earlier paid results must be reused.
4. Test an inaccessible image and oversized/invalid media. Keep a truthful error or incomplete state. Never report full visual coverage when a selected image was unreadable.
5. Verify legacy text caches and uncommitted plans cannot bypass image processing. Already-filed notes are not automatically rewritten. Test visual prompt budgeting with many items.
6. Run one real article containing a meaningful chart and one with more than three figures on a test vault. Inspect resulting Source and Topic claims, not only network success.

Acceptance: a fact visible only in the chart reaches the processing evidence and saved output; interrupted processing preserves completed evidence and eventually covers every selected item. If current behavior passes, close this item with validation evidence and no feature rewrite.

## 1. Full Markdown editor from Feed

Primary files: `FeedView.kt`, `FeedScreen.java`, `MainActivity.java`, `PageView.kt`, `Edits.java`, `VaultWrite.java`. Add a dedicated `NoteEditorScreen.java`; reuse page blocks where adequate.

1. Add visible overflow with Edit for persisted Source, Topic and Person cards. Keep actions such as lane changes separately named. Pending Inbox captures retain their capture editor; they have no full note to edit yet.
2. Load the complete file off the main thread. Do not use clipped reader text, digest, cached summaries or `Vault.clipLines` as editor input. Store original bytes/text, pinned vault identity and path with the draft.
3. Present a multiline Markdown field with Save and Cancel. Frontmatter and all sections are editable. Changing the heading does not rename the file or rewrite backlinks.
4. Preserve draft and original snapshot in private storage across rotation, navigation and process death. Restore only for the same vault/path. Leaving with unsaved changes offers Keep draft, Discard or Continue editing.
5. Before loading a writable snapshot, settle queued card/bookmark operations for that path. If they remain unsaved, show that state rather than let the editor overwrite them.
6. On Save, call `VaultWrite.writePinned` against the exact original snapshot. A stale snapshot must not be automatically reapplied as a whole-file replacement. Retain the draft and offer reload of current content or copy/export of the draft. Reload must not silently discard it.
7. Preserve line endings and original content on open/cancel. Check no-op saves. On verification failure retain original and attempted content for recovery, show an uncertain state, and require inspection before retrying a potentially completed write.
8. After verified success, clear draft, rescan the changed note and refresh readers, Feed, card cache and search data. Avoid resetting Feed position merely because an editor closes.

Acceptance: edit a long Markdown Source, a Topic and a Person; reopen and see complete content. Concurrent Obsidian edits cause a conflict without overwritten content. Killing the app preserves the unsaved draft. Permission loss, changed vault and failed read-back leave a recoverable draft.

## 2. Keep as whole-note bookmarks

Files: `Feed.java`, `FeedScreen.java`, `FeedView.kt`, `CardStore.java`, `NoteEdit.java`, `Spark.java`; existing Feed/Spark checks.

1. Retain `kept: true` as the durable bookmark flag. Existing kept notes become bookmarks without a separate migration store.
2. Load bookmark state from every eligible persisted note, independent of `cardsText`. Keep pending operations visible until committed. Ensure Topic and Person items populate the same state as Sources.
3. Make Keep/Unkeep available on persisted cards regardless of recall-card count, including search results. For notes without frontmatter, add the minimal valid frontmatter needed for Keep while preserving the body. Do not silently succeed when `NoteEdit.setKey` would leave such a file unchanged.
4. Add a Kept chip beside existing Feed filters. Build its collection from all bookmarked notes in the vault snapshot, not `Feed.build`'s recent-Topic subset. Include bookmarked People. Search inside Kept must apply the same bookmark predicate.
5. Keep existing chronological behavior for the main Feed. Define stable newest-first ordering inside the collection without promoting bookmarked items elsewhere.
6. Remove Keep from fresh-card priority in `Spark.plan`; retain due ordering, daily cap, interleaving and elaboration gates. Remove “up first in Spark” wording. Update affected callers and checks; do not leave an unused priority input masquerading as active behavior.
7. Check queue persistence before showing a bookmark change as saved. `CardStore.change` currently updates memory before appending the durable operation and only logs append failures; give affected operations a visible failure outcome.

Acceptance: a Source without recall cards, an old Topic and a Person can all be kept, found, searched and unkept. Restart preserves state. Keep changes neither main Feed placement nor Spark study order.

## 3. Card management at each note level

Files: `CardsScreen.java`, `CardSheet.java`, `CardStore.java`, `Cards.java`, `UnitScreen.java`, `TopicScreen.java`, `MainActivity.java`, `VaultActivity.java`, `Study.java` and relevant tests.

1. Expose Cards from Source and Topic detail pages and the generic note reader, including Person/entity notes handled there. Empty lists still offer Add card.
2. Reuse `CardsScreen` for a single owning note. For a Topic, show the Topic plus its linked-note owners, grouped and labeled with the owning note. Avoid duplicate cards when multiple links resolve to the same note.
3. List all cards for management, including parked and flagged cards. Study filters are inappropriate here because users must be able to edit cards excluded from review.
4. Add from a Topic writes directly to that Topic. Each linked card retains its original `(path, id)`. Edit and Delete operate on that owner, never a copied card.
5. Allow Delete for both user-created and generated cards. Confirm deletion with question and owning note; do not delete the note. Preserve existing generation markers so routine backfill does not immediately recreate deleted generated cards.
6. Reuse `Cards.setKeys`, `Cards.add`, `Cards.remove` and queued writes. Editing Q/A preserves ID, review state, logs and unrelated content. Correct the dialog behavior that dismisses after invalid empty input: keep entered text and show validation inline.
7. Update Topic study scope to include cards stored on the Topic itself, including Topics with no linked Sources. Preserve existing filtering for study only.
8. Show pending/failed saves rather than imply every optimistic edit reached the vault. Reuse the queue outcome handling introduced for bookmarks.

Acceptance: add a direct Topic card; edit a linked Source card from the Topic; delete an AI card; restart and verify all three. Parked/flagged cards remain manageable. Direct Topic cards appear in Topic study. Unrelated cards and study history remain intact.

## 4. Searchable OpenRouter model picker

Files: `SettingsScreen.java`, existing settings UI/dialog support, `ScoutUsage.java`, `OpenRouter.java`. Add a small `ModelCatalog.java` if required for shared parsing/cache behavior.

1. Replace the shared manual-ID dialog for ingest, lane/folder classification, tagging, Scout and card generation with a searchable selection list. Display model name and exact ID; preserve the current selection.
2. Fetch `GET /api/v1/models` off the main thread. Cache the last successful catalog privately, refresh after roughly 24 hours, and allow explicit refresh. A failed refresh must not erase a usable cache or current settings.
3. Reuse existing request transport and metadata conventions. Do not make Scout usage load the entire catalog for every chat or duplicate network code unnecessarily.
4. Narrow Scout results using advertised tool support. Show relevant capabilities for other roles. Treat catalog claims as metadata, not proof a provider request will succeed; retain runtime validation and readable errors.
5. Keep a small Custom model ID fallback for stale catalogs, aliases and compatible IDs not listed. Mark a current model absent from the catalog without silently replacing it.
6. Preserve existing preference names and defaults. Selection persists only the chosen model ID. Cancel changes nothing. Loading, empty results, stale/offline cache and Retry are distinct states.
7. Limit scope to currently configurable roles. Fixed vision, video, transcription and embedding models are separate existing decisions; do not add new settings as part of replacing this dialog.

Acceptance: search by provider, name or ID; select and reopen; verify correct role preference changed. Test no network, malformed catalog, missing current model, custom ID and Scout capability filtering.

API reference checked on 2026-11-17: [OpenRouter model catalog](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties). The response exposes IDs, names, input/output modalities, context length, pricing and supported parameters. No model-price comparison or recommendation is required for this feature.

## 5. Text and voice capture destinations

Files: `QuickActivity.java`, `QuickView.kt`, `FeedScreen.java`, `RecordService.java`, `CaptureService.java`, `Tagger.java`, `BackgroundJob.java`, `VaultWrite.java`, `IngestText.java`, existing capture/recovery checks. Add one small destination-writing helper if sharing the route between text and voice requires it.

### Interaction

Keep Inbox preselected. Add a destination selector with Inbox, Existing note and New note. Existing note opens a title/path search over writable knowledge notes; duplicate titles show paths. New note offers an optional title. The selected target is visible before saving/recording completes.

Use the same route from Quick capture, Feed text capture and voice capture. Widget/tile recording can still start immediately with Inbox as default; the sheet may change destination for that active recording. Stop from a notification uses the destination already persisted for the recording. Do not make a widget-only capture depend on an unopened picker.

### Durable job contract

Extend existing private capture JSON with a versioned destination mode, pinned vault identity, stable capture ID, selected path or supplied title, and durable preparation/write status. Persist these fields with the initial capture. Legacy jobs with no destination fields continue to Inbox. A pending job never follows a later Settings vault change.

For voice, persist destination with recording state and carry it into the queued job. Persist the transcript before attempting a vault write or AI title. Keep paid transcription results across retries. Do not delete audio or the job until destination content is verified; empty/failed transcription has an explicit recoverable outcome.

### Existing note

Append the exact captured text/transcript under `## My Notes`, with captured timestamp and Sparkbox attribution. Preserve paragraph structure; do not summarize, merge claims or rewrite existing prose. Use a stable capture marker so identical thoughts captured twice remain distinct while retrying one capture cannot duplicate it.

Validate the chosen path against the pinned vault and allowed note roots. Exclude Inbox, indexes, trash, conflict copies and attachments. If the note disappears or moves, retain the capture and offer target selection; never recreate an old path silently.

### New note

Write `Knowledge/Topics/<safe title>.md` with Topic metadata, `confidence: low`, and the user's text under `## My Notes` with attribution. Do not invent research claims or source citations. Use a provided title without an AI request. With a blank title, use the existing configured ingest model for a bounded title-only request; treat captured content as data, not instructions to the title service.

Queue immediately while offline. Show Waiting for title when a model/key is unavailable, with a manual-title alternative. Do not block local capture on the title request. Persist the generated title and chosen final path before creation. Reuse filename sanitization, reject empty/invalid results and handle duplicate names with a deterministic suffix tied to the capture. Never overwrite another note with the same title.

Update the knowledge index and append a `[NOTE]` audit entry once, using the same stable capture identity. Checkpoint these writes so a failed index/log update does not duplicate the note or its content on retry.

### Shared writer and recovery

Route direct captures through existing background ownership and verified vault writes; text with a supplied title or existing target requires no model key. Explicitly audit `Tagger`'s key/ingest gates so they do not strand these local-only jobs.

Prepare and persist intended content/target before writing. After a crash during write, inspect current content for the capture marker before retrying. When content is neither the known original nor verified result, retain recovery state and require inspection rather than blindly replay. Reuse `VaultLock`, pinned writes and existing recovery storage. A permission error or changed vault retains capture data.

Direct captures bypass Inbox ingest and automatic model rewriting. Inbox captures retain their current processing behavior. Refresh the vault and relevant screens after success; remove pending data only after required writes are verified.

Acceptance: offline text appends to an existing note without an AI key; titled text creates a note locally; blank-title text waits safely then receives an AI title; voice does the same after transcription. Kill after transcription, note write and index/log write separately: no lost content, repeat paid calls or duplicate append. Changing vault, deleting the target or losing permissions preserves the pending capture.

## Validation and delivery

Baseline command: `./gradlew captureTest --console=plain` passed on 2026-11-17: Capture 342, Agent 79, Session usage 32, Vault tools 62, MCP 24, Scout 94, Retrieval 38; total 671. No Android device was connected. No paid provider calls were made.

For each deliverable, run existing checks plus focused regression cases for its meaningful branches. Use current test harnesses rather than introduce a test framework. Run lint/build for UI changes and `./gradlew check` plus `./gradlew assembleRelease` before release. Existing real-device startup/scroll and capture-loss gates remain applicable; do not invent new benchmark targets.

Use an isolated test vault for destructive, sync-conflict and process-death checks. Required phone coverage: editor drafts/conflicts, bookmark persistence, Topic card ownership, model-picker offline behavior, voice destination recovery, and image evidence reaching saved notes. Check large fonts, keyboard visibility, scrolling, back navigation, TalkBack labels and light/dark appearance on changed screens.

Suggested review units, in order:

1. Image qualification evidence and only demonstrated repairs.
2. Full editor with draft/conflict recovery.
3. Bookmark semantics, collection and removal of Spark priority.
4. Scoped card management and direct Topic study support.
5. Cached model catalog and shared picker.
6. Durable destination fields and text routing.
7. Voice routing, optional AI title and crash recovery qualification.

Do not commit keys, private queue/draft files, settings, logs containing private content or device vault fixtures. No deployment, APK installation or implementation is included in this planning task.

## Completion criteria

All six requests have either demonstrated passing behavior or their planned implementation and validation completed. Existing notes, pending captures and recall history survive upgrade. Every user write is visibly saved, pending, conflicted or failed; an optimistic screen update alone is not proof of a vault write.
