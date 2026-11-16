# Scout implementation plan

Date: 2026-11-16. Status: independently reviewed; findings resolved. Scope: implementation planning only; no application changes authorized by this document. Base: Sparkbox `2f90cc639547c9d01bbbcd63252d35afe43b7054`.

Inputs: [design spec](../specs/2026-11-16-scout-agent-design.md), [ChatGPT mobile study](../../research/2026-11-16-chatgpt-mobile-ui-study.md), [reference investigation](../../research/2026-11-16-scout-reference-study.md). The design supplies behavior and architecture; the mobile study supplies observed interactions and proposed native visual tokens. Browser CSS values are not Android dimensions.

## Outcome and constraints

Deliver a native research workspace that answers with inspectable evidence, accepts context and directions reliably, saves only individually approved note changes, and discovers useful sources manually or through optional schedules. Preserve phone-only operation, the existing vault roots, Java core, Compose UI, encrypted credentials, hybrid retrieval, verified writes and usage accounting.

Idle composer: text, +, Send. During execution, Stop stays accessible; Send direction may coexist. Research/source chips appear only when active. Premium presentation means one reading type scale, consistent spacing, real tables/code blocks, stable scrolling and useful source previews.

Do not add a server, WebView, agent framework, database, WorkManager, vector store, routing model or general workflow engine. Do not redesign other Sparkbox screens. Do not implement voice/file capabilities the current Scout path cannot support merely to fill the menu. The existing single-note attach path must continue working throughout migration.

All task paths below are relative to `/Users/venkateshmurugadas/software_codes/sparkbox`. `src/` file names refer to `src/me/venkatesh/saveobsidian/`. Line anchors identify the inspected base; search symbols again before editing.

## Execution rules

1. Recheck Git status and local instructions. Preserve the research documents and unrelated work. Use the suitable existing checkout; create a separate branch/worktree only when implementation begins and context requires it.
2. For each changed method, inspect callers first. Keep each task independently reviewable; the numbered tasks are proposed commit boundaries, not instructions to commit this planning document now.
3. Reuse `tests/AgentTest.java`, `VaultToolsTest.java`, `McpTest.java`, `SessionUsageTest.java` and the `CaptureTest` runner. Add focused cases for behavior, not boilerplate per-method suites. New pure Java files/tests must be added to both `build.gradle.kts`'s explicit source list and `build.py`'s JVM-check list.
4. Never use the owner's real vault for destructive fixtures. Use a disposable selected test vault and fake providers. Do not print keys or read `.env` as part of plan execution.
5. Keep one authoritative session JSONL. Draft and schedule files are local UI/scheduling state, not competing transcripts or vault knowledge. Preserve existing usage records and compatibility.
6. Run the smallest applicable check after each task. Run the complete build/lint/device gate at release boundaries, not after every text/style change.

## Dependency and release map

| Release slice | Tasks | Required dependencies |
| --- | --- | --- |
| R1: truthful, durable execution | 1–5 | Sequential foundation; approval suspension in 5 depends on 2–4 |
| R2: research workspace | 6–11 | Evidence and limits depend on R1; UI uses existing callbacks until replacement is ready |
| R3: useful approved actions | 12–13 | R1 approvals plus R2 evidence/rendering |
| R4: manual discovery | 14 | R1–R3 mechanisms; read-only research works even before saving |
| R5: optional schedules | 15–16 | Manual discovery and all durable-run invariants |
| Release qualification | 17 | All included tasks; schedules stay disabled until their own gate passes |

Tasks 8–10 may be developed after task 3 without waiting for every network improvement, but do not ship an interface that implies unimplemented evidence or recovery guarantees. All three current pain areas—progress, evidence/usefulness and context/save friction—must improve before calling R2+R3 the first complete workspace release. Discovery and schedules remain required subsequent slices, not indefinite exclusions.

## Task 1. Establish fixtures and the current contract

**Files:** `tests/AgentTest.java`, `tests/VaultToolsTest.java`, `tests/McpTest.java`, `tests/CaptureTest.java`; `device-tests/me/venkatesh/saveobsidian/ReelTest.java` as the existing instrumentation entry-point reference.

Capture minimal legacy JSONL fixtures for finished, compacted, interrupted, usage-bearing and torn-final-line sessions. Use temporary files in tests, not copied personal transcripts. Add targeted reproductions alongside the implementation tasks for false successful tool labels, startup steering loss and abrupt provider EOF. Do not start a new test framework.

**Done when:** unchanged baseline runs with `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew captureTest --console=plain`; fixtures can be reopened without Android. Previous research recorded 76 Agent, 32 usage, 56 vault-tool, 15 MCP and 332 capture/append checks; verify current output rather than hardcoding these totals as success criteria.

## Task 2. Separate retained history from model context; make durability explicit

**Files:** `Session.java:66,74,193,224`, `Agent.java:109`, `Chat.java`, `AskScreen.java:152`; tests in `AgentTest`/`SessionUsageTest`.

Add a retained-message projection on the active path that ignores compaction/context-edit filtering. Keep `Session.context()` exclusively for provider context. Give visible rows stable entry/message IDs; map provisional acceptance IDs to stored messages rather than showing duplicates. Join tool calls to results by ID; show attempted/running/success/error/declined/unknown, never infer successful edits from a call name.

Add explicit durable append for acceptance, approvals, write intents and receipts: append, sync file descriptor, then advance in-memory authoritative state. A closed session rejects critical writes instead of silently succeeding. A storage failure sets a visible durability error and prevents further effects. Preserve usage entry semantics and version-1 readers where additive custom entries suffice. A torn last line is recoverable; interior structural corruption makes the session read-only until repaired.

**Done when:** compaction preserves old visible messages; failed/declined writes never show “Edited”; legacy unknown outcomes remain unknown; simulated append failure cannot permit a subsequent mutation. Usage totals remain unchanged.

## Task 3. Persist configuration, queues and per-session drafts

**Files:** `ScoutRuns.java:42,92,104,223`, `Agent.java:117–158`, `Scout.java:98`, `AskScreen.java:35,227`; new focused `ScoutState.java` only for the pure durable-state reducer.

Use `Session.appendCustom` for `run_config`, lifecycle, accepted/consumed queue messages and bounded partial-text deltas. Freeze run ID, generation, selected SAF tree identity, prompt/policy revisions, model, Research, source scope and limits before any provider/MCP operation. Sequence assignment occurs under session append ownership. Reject stale-generation callbacks; screen loads also carry selected-session plus view revision. A changed vault selection invalidates pending context/proposals and prevents resuming against a different tree, even when paths/content match.

Accept steering at runner level before `Agent` exists. Persist queue ID and text before acknowledging/clearing the composer. Store that queue ID on its appended user message before marking consumed; replay treats an existing correlated message as consumption even if the consumed marker was interrupted. Never mark consumed before the user message is durable. Support Edit/Remove before consumption and `followUp` for “After this answer.” Stop preserves unconsumed items for review. Process recreation does not auto-send unsent drafts or repeat completed effects.

Use Android `AtomicFile` per session/new-chat draft for text, bounded context descriptors, caret, active chips and expansion state. A pure data codec belongs with `ScoutState`; keep Android storage glue in existing runner/screen code unless it becomes unwieldy. Coalesce draft writes, but flush on explicit send and lifecycle transitions. Resume uses persisted run configuration; it must not silently turn Research off.

**Done when:** send during MCP startup is never lost; duplicate consume/replay yields one user message; chat switching/recreation restores the correct draft/context; late callbacks cannot change another chat. Persisted partial text never enters model context as a completed answer.

## Task 4. Reject incomplete provider streams and bound transport work

**Files:** `OpenRouter.java:27–85`, `Agent.java:330,363`, `Mcp.java:75`, `Cancel.java`; extend fake HTTP checks or add a small `OpenRouterTest.java` wired into the current runner.

Extract a small pure SSE decoder handling UTF-8, CR/LF/CRLF, comments and multiline data. Require a recognized completion boundary. EOF without a finish reason/valid terminal state is interrupted; `[DONE]` alone must not authorize incomplete tool arguments. Preserve completed text if only final usage is missing, with cost unknown. Invalidate all tool execution from a partial/truncated tool stream. Preserve attempt IDs, partial text and existing usage metering; a new network request is a visible retry, not stream replay.

Bound event bytes, tool arguments, MCP response bytes before JSON parsing, pagination and total call time; do not rely only on the existing 20 KiB output truncation. Initial transport ceilings: 1 MiB decoded SSE event, 256 KiB tool arguments and 1 MiB MCP response; exercise boundary cases before release. Use cancellation-aware backoff, not an uninterruptible sleep. Protocol failures are typed errors, not successful empty replies.

**Done when:** split UTF-8/multiline events parse correctly; malformed/oversized/abrupt streams never run a tool; Stop interrupts backoff and connections; existing successful JSON/SSE MCP fixtures still pass.

## Task 5. Replace blocking approvals with durable proposals and receipts

**Files:** `Tool.java`, `Agent.java:280,390`, `VaultTools.java:303,367`, `ScoutRuns.java:195–219`, `VaultWrite.java:63`, `ScoutService.java`; add `ScoutChanges.java` only if proposal validation/application no longer fits existing classes.

Prepare a note-change proposal containing ID, pinned SAF tree identity, exact path/operation, full before/after content and hashes, originating call, source IDs and dependencies. Receipts also identify the tree. Extend the write boundary to accept and use the expected/pinned tree URI for actual storage access; checking preferences before a write that then resolves a different current tree is insufficient. Add a suspended-tool outcome distinct from success/error; preserve its pending call without sending a fabricated tool result. Mutation batches run sequentially. Save completed sibling results; mark not-started siblings deferred. Suspend without blocked thread/network connection and stop foreground execution when nothing else is active.

Approve/Decline addresses a proposal ID, not “whatever approval is current.” Persist decision first. Under `VaultLock`, compare tree/current content; invalidate stale approvals. Immediately before write admission, serialize the cancellation/current-generation check with Stop through the owning run's effect gate. If Stop wins, do not start the intent/effect. If the write has already been admitted, finish its verification/receipt and stop subsequent effects; do not promise rollback of an in-flight storage write. Persist intent, use `VaultWrite` read-back verification, then persist receipt before announcing success. Resolve the original tool result once; deferred siblings receive not-executed results and may be proposed again only through a new ordinary turn. `answerInterruptedCalls` recognizes intact suspended proposals rather than destroying them.

On restart: validate tree identity first; current equals after means recover receipt; current equals before retains explicit continuation; neither means conflict review. Create-at-existing-path is a conflict. Decline is durable. Guard protected frontmatter, `My take`, `My ideas` and existing card/review blocks through deterministic preservation checks, not prompt trust.

External calls reuse suspension with a distinct proposal kind: server identity, exact endpoint, credential/config/policy revision, tool name, exact arguments/hash, originating call and dispatch state. No note path/content hashes are required. Before dispatch, revalidate identity/revision, cancellation and generation, then durably record dispatch intent. Record returned result separately. A dispatch intent without a durable result is outcome unknown; never redispatch it automatically or apply local-note hash reconciliation. Endpoint/config changes invalidate unexecuted approval. A new user-authorized attempt is a separate operation that explicitly acknowledges the unknown prior outcome.

**Done when:** crash injection before/after decision, intent, vault write and receipt yields no blind replay; repeated approval is idempotent; changed notes require a new preview; one preview cannot approve multiple note changes. Add two-vault fixtures with identical paths/before-content, Stop racing approval/lock acquisition/recovery, and remote interruption after dispatch/config change. Local display remains available without network. Do not defer these cases until UI polish.

## Task 6. Introduce structured evidence without replacing retrieval

**Files:** `Scout.java:89,98`, `VaultTools.java:159,277`, `Mcp.java:203`, `Cites.java`, `Chat.java`; focused new `ScoutEvidence.java` and fake-tool cases.

Register host-assigned source IDs with exact path/URL, title, origin, call, coverage, excerpt/range, retrieval time and content hash. Search metadata is not read evidence. Include IDs beside model-visible excerpts; `Tool.Result.details` alone is insufficient because it is excluded from context. Resolve `[[cite:<id>]]` before normal wiki links, then number per answer. Keep legacy citations readable with honest unknown coverage.

Remove silent claim laundering by citation stripping: unresolved targets stay visibly unresolved; no blanket verified badge. No second verifier model per answer. Retain source identities/excerpts across compaction in a bounded selection of relevant descriptors included in the next request; the durable full registry remains outside model context. Export/Keep converts IDs into vault links or ordinary web citations.

Reuse `Ask.search` and existing keyword/vector caches. Cache repeated reads by content revision and invalidate on writes. Map known MCP structured schemas conservatively; generic text remains inspectable with unknown coverage. Never lowercase case-sensitive URL paths or infer canonical URLs from guesses.

Update `Scout.SYSTEM`/`RESEARCH` in this task: host citation IDs replace raw-path/URL citation instructions; distinguish source scope, found/excerpt/full coverage, findings versus inference, and concise versus requested research depth. Remove the conflicting blanket ban on external URLs while retaining host-validated target rules. Read existing evidence before repeating search; use response shapes from design section 4 rather than one compulsory template. Honor Why/Ideas only from host-identified owner fields, not similarly labelled copied article text. Prompt changes carry a persisted revision.

**Done when:** fake/metadata-only references cannot look fully read; citations survive compaction and export; invalid targets remain visible; note, capture and web evidence share one registry and navigation contract. Fake-provider request fixtures assert the prompt/citation/scope contract. An owner-reviewed fixed question set covers explanation, comparison, missing evidence, web supplementation and follow-up reuse; judge supported claims and usefulness separately from target resolution. Do not label a source-ID parser test an answer-quality evaluation.

## Task 7. Enforce source scope, capabilities, budgets and context sizing

**Files:** `Scout.java:72,98`, `ScoutUsage.java:17`, `SessionUsage.java`, `Compact.java:32`, `Agent.java:217,330`, `Mcp.java:203,227`, `SettingsScreen.java`.

Vault-only runs skip external MCP setup. Persist selected server/tool allowlists, capability roles and policy revision. Cache validated schemas by endpoint/credential revision/policy; invalidate on edits and bound freshness. Separate verified read-only host policy from server annotation hints. Unknown or mutating tools use the durable call-approval path; discovery exposes only permitted research capabilities.

Keep 12/30 model-turn caps. Enforce proposed 24/60 total tool calls, 6/12 total external searches, 5/15 active minutes and at most two concurrent external reads per run; persist cumulative counters across resume, counting retries. Add one shared Scout admission gate across manual, resumed and JobScheduler owners, initially at most two executing runs. A suspended approval releases its permit; resume reacquires it. A full gate defers jobs and visibly queues/manual-rejects new work without losing the request; completion, cancellation and failed startup always release ownership. Reserve synthesis capacity. Journal bounds are 8 MiB/4096 records per run with a concrete reserved tail of 64 KiB/32 records for terminal/error/receipt records; reject new proposals that cannot fit before effects. Reaching a cap stops retrieval with a truthful partial outcome.

Extend existing model metadata cache for supported parameters/pricing. Use one full-request estimate, including prompt/tools, for display and compaction. Unknown capacity/pricing remains explicit; fallback context handling is conservative and output reserve fits small models. Existing generation-ID reconciliation stays authoritative for reported cost. No automatic model switch or guaranteed currency cap.

**Done when:** vault questions make no MCP connection; denied tools cannot escape scope; resumed budgets do not reset; small-window requests compact coherently; terminal records fit when budget is exhausted; unknown spend cannot appear as zero. Concurrent-chat-plus-schedule fixtures prove admission bounds, permit release after all terminal/startup-failure paths, and resumed approvals cannot bypass the gate.

## Task 8. Build the minimal composer and full-height draft editor

**Files:** `AskView.kt:91,112,193`, `AskScreen.java:35,134,227`, `MainActivity.java:224`; use existing Compose/Material controls.

Replace the permanent control rail with + and Send/Stop. Put Add notes, Add quote/capture, Research and Sources first in the menu; model/tools are secondary. Add active removable Research/Vault + web chips; no default Vault chip. Configuration edits affect future requests only. Suggestions fill the draft instead of sending immediately.

Use the task-3 draft as the single input state for compact/expanded editors. Grow to six lines or 30% of available height, then scroll internally; offer Expand. Collapse/Back preserves caret/context. Context rows remain compact with preview/remove/loading/unavailable states. Extend existing `Ask about this` attachment without sending it automatically; selection uses current vault paths. Missing context blocks Send until resolved or explicitly removed.

Keep Stop usable offline and while typing; a separate Send direction handles queued text. Cap visible menu features to capabilities that actually work. Integrate IME/safe-area padding through the existing shell rather than adding a separate Activity for editing.

**Done when:** empty, long, offline, attached, researching, awaiting approval and running-with-draft states remain operable at 320 dp and 200% font; Back collapses before leaving chat; no implicit send or lost direction.

## Task 9. Apply scoped typography, layout and stable reading behavior

**Files:** `SparkTheme.kt:32`, `AskView.kt:154,165,193,305,394`, `AskScreen.java`; no global `Ui.type` or Feed restyling.

Define one Scout style set: body 16/26 sp, answer heading 18/28 semibold, section 16/24 semibold, composer 16/24, source title 15/22 medium, metadata 13/18, table 14/22 and code 13/20 monospace. Retained TextViews use matching values and avoid size compression. Use system fonts, existing slate/emerald roles, 16 dp gutters, 24 dp section/turn gaps, 24 dp composer radius and 48 dp touch areas. Remove ordinary answer-card borders.

Stable message/block IDs own expansion and scroll state. Follow only at the bottom; scroll-up pauses following until Jump to latest. Opening/closing source and activity surfaces restores anchor and focus. Coalesce live state publication to at most ten updates/second; final/structural events flush immediately. Cache only proven repeated work, not a new global rendering cache.

**Done when:** streaming/final text has no size shift; tab/theme/session changes retain the correct view; screen reader labels exist; screenshot comparison verifies density, baselines, gutters and dark/light treatment. Keep visual checks separate from claims of full accessibility compliance.

## Task 10. Render structured tables/code and selectable answers

**Files:** `Md.java:29`, `AskView.kt:154`; new small `ScoutMarkdown.java` block splitter and `ScoutAnswerView.kt` if needed; reuse `Md` inline formatting through a narrow shared helper.

Support only needed blocks: prose/headings/lists/quotes, pipe tables and fenced code. Retain exact raw text for Copy. A table keeps headers/cells and local overflow; code has language, Copy and optional Wrap, without execution. Incomplete fences stream as provisional blocks. Mermaid/HTML remain explicit text/open-in-Obsidian fallbacks; do not add a browser engine or silently drop content.

Do not split links or code using naive blank-line regexes. Add a small fixture covering fenced pipes, escaped table pipes, blank lines in code, partial fences and citation markers. Keep the existing note viewer on its old rendering path unless a shared inline fix is necessary and verified there too.

**Done when:** paragraph/list selection and citation navigation coexist; long code/URLs do not widen the page; wrapped code copies byte-equivalent whitespace; stable completed blocks do not reparse on every text delta.

## Task 11. Expose activity, evidence previews and honest recovery

**Files:** `Chat.java`, `AskScreen.java:152,243`, `AskView.kt:305,382,394`; `ScoutSourcesView.kt` for native source/approval sheets if splitting improves readability.

Show one compact outcome-aware activity summary, expandable into real operations. Hide raw tool names/arguments under Details. Retry countdown, compaction, partial completion, missing capability and interrupted state remain distinct. Source counts represent actual registered evidence; “one note” does not assert one independent source. Preserve existing `SessionUsageScreen`/`SessionUsageView` access through the chat menu; the composer redesign must not remove context/cost history or change reported/unknown charge semantics.

Tap citation for a short excerpt/coverage preview; All sources opens a full-height sheet filtered by Notes/Web/Captures and cited/other/unavailable. Open note/page and Attach use validated paths/URLs. Show relevant excerpts, not navigation boilerplate. Restore invoking focus and answer anchor on close. Review approval as full diff, with exact proposal ID and Approve/Decline; disable stale/resolved actions. Expose chat search/rename/delete/export separately from answer actions. Export uses local file sharing only on user action and excludes keys; warn that transcripts contain selected note text.

**Done when:** every reference opens the correct source; failed/declined/unknown operations are distinguishable; source/approval sheets work with keyboard and TalkBack; delete during an active run fences late callbacks and cannot recreate its session.

## Task 12. Add Keep, Save source and Add to topic through proposal reuse

**Files:** `AskScreen.java`, `Scout.java`, `ScoutEvidence.java`, task-5 proposal code, `IngestText.java:40,72,112–129`, `Layout.java`, `VaultWrite.java`.

Keep resolves private citations and prepares a title/path preview under `Knowledge/Learnings/Scout/` by default. Save source reuses `IngestText.sourceNote` and existing category/year conventions; source metadata is real, Why/Ideas are owner-provided or left empty. Add to topic prepares exact targeted edits against current content. Do not call `Ingest.process` from Scout or enqueue a captured link as a shortcut around individual approvals.

Represent source/topic/index/log changes as individually approved proposals with dependencies. Apply source first; dependent references cannot apply until its verified receipt exists. Declining or conflicting an ancestor invalidates dependent pending proposals. Partial approval is displayed honestly. Owner sections and special drill files remain protected.

**Done when:** one answer becomes valid Markdown without private source IDs; every touched note has a full approved preview; interrupted multi-note saves resume from receipts, never regenerate and reapply earlier writes.

## Task 13. Add reviewed card proposals and explicit answer retry

**Files:** `CardGen.java:133,150`, `CardPrompt.java`, `Cards.java`, `CardStore.java`, `AskScreen.java`, `Session.java`.

Extract only the existing model-to-card generation/parsing step that can return a draft without writing. Do not invoke `CardGen.run/process`, because those paths write automatically. Show generated cards as proposals; append new validated blocks through task 5, preserving all existing card IDs, owner Take and review schedules. Explicit Make cards is the only scoped permission for adding new card blocks; it does not authorize rewriting old ones.

Edit/retry answer stores a new attempt linked to the chosen user message while preserving earlier answers and write receipts. No full branch-tree UI. Treat retry as a new provider request with its own usage and explicit relationship; never replay the original tool side effects.

**Done when:** rejecting cards writes nothing; accepting adds only reviewed blocks; retry cannot repeat a completed mutation; stopped/partial output remains inspectable.

## Task 14. Implement finite manual discovery

**Files:** focused new `ScoutDiscovery.java`, `Scout.java`, `AskScreen.java`, `AskView.kt`, `ScoutEvidence.java`; existing `VaultStore` graph/search.

Persist explicitly selected interests and seed note references in one private AtomicFile. Suggestions require owner selection; do not read Daily/Pacts or infer new roots. Reuse the read-only research runner with a bounded discovery request. Return at most five candidates with evidence-linked relevance, novelty, coverage and Open/Investigate/Save/Dismiss actions. Persist candidates before marking the run complete.

Deduplicate known source URLs and provider-issued IDs against the vault and seen ledger. Normalize only known tracking parameters and provider-specific account-selector variants; keep meaningful parameters and case-sensitive paths. Use a deterministic interest/host diversity tie-breaker; no recommendation model, extra vector database or numerical relevance claims. Investigate starts an ordinary Research request; Save uses task 12.

**Done when:** existing/dismissed sources do not reappear as new; reasons cite real selected interests; five is a finite default, not an infinite feed; missing web capability gives a concrete repair path.

## Task 15. Add optional JobScheduler discovery with separate ownership

**Files:** new `ScoutJob.java` and small `ScoutScheduleReceiver.java`, `ScoutDiscovery.java`, `ScoutRuns.java:92`, `ScoutService.java`, `MainActivity.java`, `AndroidManifest.xml`; reuse rather than alter Tagger's job/run gate.

Add a dedicated non-exported JobService and distinct job IDs. Split runner execution owner into user-initiated foreground and JobScheduler-managed modes. A scheduled run must not call `ScoutService.sync/startForegroundService`; `JobService` supplies execution lifetime. Manual runs retain the existing foreground service. Neither path overlaps a second owner for the same occurrence/session.

Persist schedule ID, enabled state, local daily/weekly window, timezone/follow-device choice, constraints, interest, result/budget caps and next occurrence. Schedules default off. One daily run returns at most three suggestions under normal caps. Reserve `(scheduleId, occurrenceId)` durably before dispatch; concurrent app-open/job triggers reuse it. Missed windows coalesce into one catch-up, not a backlog.

Reschedule on app open, boot/package replacement and time/timezone changes through the dedicated receiver. Do not register these actions on `Reminder`: its current non-boot path sends daily Spark notifications and must remain unaffected. Persisted JobScheduler configuration and schedule records must agree. `onStopJob` cancels/checkpoints only its Scout owner; preserve budget and useful read checkpoints, then release ownership before retry can claim it. Force-stop limitations remain visible.

**Done when:** duplicate triggers/reboot/time changes cannot launch duplicate occurrences; scheduled work never launches an illegal foreground service or stops ingest; permission/key/tool failures pause visibly; late execution is described as delayed rather than exact delivery.

## Task 16. Make schedules, notifications and service timeouts reviewable

**Files:** `ScoutDiscovery.java`, `ScoutJob.java`, `ScoutService.java:23`, `ScoutRuns.java:231`, `AskView.kt` or a focused discovery settings sheet; `AndroidManifest.xml` only for required receiver actions.

Expose schedule controls inside Discover; show preferred window, next/last actual run, delayed/paused state, timezone, network/battery constraints and caps. Disabled stays disabled after reboot. Show a single notification only after durable nonempty findings or an actionable failure; stable occurrence notification IDs make replacement idempotent. Honor permission and quiet hours; no notification spam for empty results.

Implement applicable `Service.onTimeout` handling with synchronous stop of new effects, bounded checkpoint attempt and prompt `stopSelf`; never wait for network or an approval in the callback. Persist/interrogate global active-run state before stopping the shared service. Pending approvals remain suspended without consuming foreground lifetime. Startup reconstructs interrupted states rather than claiming work continued through process death.

**Done when:** crash after result persistence/notification does not duplicate findings; notification opens the exact run; quiet hours and disabled permission do not lose results; timeout recovery is truthful and does not block service shutdown.

## Task 17. Qualify on a disposable Android vault and document release behavior

**Files:** focused additions under `device-tests/me/venkatesh/saveobsidian/`, `docs/baseline.md`, `README.md`, `docs/architecture.md`; no unrelated baseline changes.

Run `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew captureTest`, then `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew assembleRelease check` for an implementation release. Existing instrumentation uses a custom `ReelTest` runner; add a `suite=scout` dispatch to focused Scout device checks, retaining its current default reel paths. Invoke the built instrumentation with that explicit suite; do not claim `connectedAndroidTest` exercises Scout until its argument wiring does so.

Device scenarios: lifecycle/recreation and kill during every approval boundary; revoked SAF grant; concurrent Obsidian edit; job cancellation/reboot/timezone; late callbacks after delete; real keyboard and Back; TalkBack focus/announcements; dark/light at 100%, 130%, 200% fonts; 320/390 dp windows; long titles/tables/code/URLs; scroll-up during streaming. Use fake model/tool results for deterministic screenshots and failure injection. Optional paid live smoke tests require configured capability and an explicit small bound, not a full-vault benchmark.

Measure accepted echo (candidate target 100 ms), live publication rate, first useful status/text, actual tool/model calls, cost coverage, source resolution and repeated reads. Recheck existing startup/scrolling gates on the same device; do not report old measurements as new ones. Visually compare the implemented states with the captured references and native token table, not only unit tests.

**Done when:** all included slice gates pass, unsafe/unfinished slices remain inaccessible, legacy sessions load, no keys/session metadata enter vault notes, and docs explain phone-only timing/recovery limits. Installation/publishing is a separate user-authorized action; this plan does not perform it.

## Minimal file additions

Prefer existing files until a boundary becomes substantial. Expected focused additions: `ScoutState.java`, `ScoutEvidence.java`, `ScoutMarkdown.java`, `ScoutAnswerView.kt`, `ScoutSourcesView.kt`, `ScoutDiscovery.java`, `ScoutJob.java`, `ScoutScheduleReceiver.java`; optional `ScoutChanges.java` only if needed for proposal complexity. No per-state classes, generic event bus, repository/factory layers or one-implementation interfaces. Only introduce a test seam where it enables a real failure case.

## Requirement coverage

| Requirement | Tasks |
| --- | --- |
| Durable history, steering, lifecycle, no false success | 2–5 |
| Scoped research, truthful sources, efficiency/cost | 4, 6–7 |
| Minimal composer, context, expanded editing | 3, 8 |
| Premium type, spacing, palette, native rendering | 9–10, 17 |
| Activity, citations, sources, accessibility | 9, 11, 17 |
| Individual approvals, protected notes, recovery | 5, 12–13, 17 |
| Keep/source/topic/cards/retry/export | 11–13 |
| Manual discovery | 14 |
| Optional schedules, no server, notifications | 15–16 |

## Review record

Independent subagent review identified five material gaps; all were incorporated: pinned vault identity in task 3/5, cancellation/write admission in task 5, separate remote-call proposals in task 5, shared admission/concurrent limits in task 7, and prompt/usefulness evaluation in task 6. Parent review additionally made queue handoff crash-safe, preserved usage navigation, isolated scheduling broadcasts from daily reminders and specified the existing instrumentation dispatch. The same reviewer checked the revisions and reported all five findings resolved, with no further material findings in the changed paragraphs. This validates the plan only. Tests and implementation outcomes above remain future acceptance gates, not completed work.
