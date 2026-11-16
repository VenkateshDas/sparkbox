# Scout implementation and qualification

Implementation date: 2026-11-16. This is a phone-testing candidate, not a qualified production release. No installation, paid live research, or selected-vault device writes were performed during implementation.

## Implemented behavior

Scout keeps visible history separate from compacted provider context. Session appends sync before authoritative memory advances; closed or failed storage stops effects. Interior corrupt records make the session read-only; a torn unclosed final record is removed before appending.

Directions are journaled before acknowledgment, including during tool-server startup. Correlated user messages consume queue items without replaying them after a crash. Queued directions support editing/removal and delivery after the answer. Draft text, caret, one note/quote attachment, Research, scope and expansion are saved per chat with AtomicFile.

Note changes suspend execution as individual stored proposals. Approvals identify exact proposals and pinned vault trees. Saving compares current content, serializes admission with Stop, writes through the pinned SAF tree, verifies read-back, and records a receipt. An admitted write finishes verification even if Stop arrives during storage access. Recovery recognizes already-written content and does not repeat completed writes. Existing frontmatter, My take, My ideas and card blocks are protected; explicit card generation permits reviewed additions. Frontmatter protection is deliberately stricter than selective key editing: existing frontmatter cannot be changed by Scout.

External effects also suspend. Approved dispatch intents and results are separate records. An intent without a result becomes outcome unknown and is not automatically dispatched again. Repeating a tool after an unknown outcome displays that prior uncertainty in its new approval preview. Declines survive interruption before result resolution.

Source IDs, coverage, ranges, excerpts and content hashes live in the journal. Read evidence and search metadata remain distinct. Generic MCP URLs have unknown page coverage. Citation target resolution is not claim verification. Private source markers export into ordinary links; unresolved markers remain visible. Recent bounded evidence descriptors survive compaction. The native source surface includes citation previews, Notes/Web/Captures filtering and validated Open/Attach actions. It does not yet provide the complete cited/other/unavailable evidence-browser contract from task 11.

The composer exposes context and request options under +. Stop remains available while typing and offline. Suggestions populate drafts. Answers use Scout-specific reading sizes, selectable prose, native tables and code with exact-whitespace Copy. Scrolling follows only while following the latest content; message identities and per-chat reading positions survive chat switches within the screen lifetime. Restoring the exact reading anchor across process death is not yet implemented.

Keep, Save source and Add to topic prepare note previews. Source index/log proposals depend on the source receipt and require separate approval. Card generation parses drafts without invoking the automatic card-writing pipeline. Edit and retry records its relationship to the selected prior answer and preserves earlier messages and receipts. Chat export shares a local Markdown file, excluding tool configuration and session metadata.

Manual discovery uses explicitly selected interests and optional Knowledge seed notes. It exposes only owner-verified read-only research tools, returns at most five evidence-backed candidates, and deduplicates known/seen URLs. Candidates have Open/Investigate/Save/Dismiss actions. Provider-specific account-selector normalization and provider-issued-ID deduplication remain unimplemented; meaningful URL parameters and path case are preserved.

Run configuration pins vault/model/Research/scope/thinking and server revisions. Vault-only requests skip MCP startup. Model/tool/search counts and active time survive explicit continuation. Two Scout executions may run at once; approvals release execution ownership while suspended. Transport work, SSE events, arguments, MCP bodies, pagination and journals are bounded. A journal reserves terminal/receipt capacity. Schemas have a bounded one-hour cache keyed by endpoint, credentials and host policy. Unknown model capacity uses a conservative internal fallback while the UI continues to show capacity as unknown.

## Schedules remain disabled

The dedicated JobService, receiver, schedule preferences, occurrence reservation and separate execution owner are implemented behind `ScoutJob.QUALIFIED = false`. Nothing schedules or dispatches automatically while this gate is false. Schedule preferences can be inspected and saved, but Enable is disabled.

Do not remove the gate until duplicate triggers, cancellation/restart ownership, reboot/timezone changes, quiet hours, notification delivery, configuration/key failures and delayed execution pass on a disposable test vault. Quiet-hour suppression currently retains results in the chat without a later deferred notification. The complete notification replay/reconciliation behavior still needs implementation and qualification. The scheduler code is not release-ready merely because it compiles.

## Host validation

Run from the repository root:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew captureTest
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew assembleRelease check assembleDebugAndroidTest
```

The new Scout regression cases cover retained history, durable queues, corrupt/failed storage, malformed/oversized/incomplete SSE, cancellation, pinned-tree proposals, decline/repeated approval, write recovery, suspended batches, deferred siblings, Stop during lock acquisition, citation export, Markdown blocks, journal reserve, persisted budgets, and remote outcome uncertainty. Existing Agent, usage, vault-tool, MCP and capture checks remain in the same runner.

These are behavior checks, not an answer-quality evaluation or a complete crash-state proof. In particular, cumulative admission/time counters and the disabled scheduler need broader concurrency/recreation coverage before release.

## Phone handoff

Connect the phone before installation. Use a disposable SAF-selected test vault for write scenarios, never the owner's vault. Existing instrumentation now accepts an explicit Scout suite:

```sh
adb shell am instrument -w -e suite scout \
  me.venkatesh.saveobsidian.test/me.venkatesh.saveobsidian.ReelTest
```

That suite is a safe private-storage/prompt smoke check only. It does not claim to exercise UI lifecycle, accessibility, SAF races or schedules. The old reel test remains the default when `suite=scout` is absent.

The remaining device matrix is: real keyboard/Back and expanded editor; chat switching/recreation and late callbacks after delete; Stop and process death around decisions/intents/writes/receipts; revoked SAF access and concurrent note edits; 320/390 dp, dark/light, font scales 100/130/200%; long tables/code/URLs; scroll-up while streaming; TalkBack focus and source/approval actions. Measure accepted echo, live publication rate, first useful response, calls, usage coverage, source resolution and repeated reads. No earlier measurements count as new Scout measurements.

## Answer-quality questions for owner review

Use selected synthetic notes or an explicitly approved small test set. Evaluate support and usefulness separately from citation navigation.

1. Explain a mechanism in two selected notes, with the key difference between evidence and inference.
2. Compare two approaches whose notes disagree; identify agreement and unresolved disagreement.
3. Ask about something absent from the test vault; require an honest missing-evidence answer.
4. Enable Vault + web for the same gap; require useful supplementation with honest page coverage.
5. Ask a follow-up answerable from prior retrieved evidence; check whether Scout avoids unnecessary rereads.

This set still requires owner review. No paid live smoke test or full-vault benchmark has been run.

## Device session and handoff — 2026-11-16

The signed release APK was built and installed on the connected Android 17/API 37 phone. `assembleRelease check` passed after the device-driven UI fixes; lint reported warnings, with no new blocking errors. The instrumentation APK was removed afterward. Original app preferences, model credentials and display settings were restored, and the temporary chats, drafts, external test vault and matching fixture backups were removed.

Observed device checks passed: the private-storage smoke suite; expanded-editor Back at the normal scale; keyboard edits and lifecycle-flushed draft/Research/quote restoration after process restart; source filtering and excerpt preview; and real SAF read-back/receipt/idempotence, protected-section preservation, concurrent-edit conflict and Stop-before-write admission on the disposable vault. A 320 dp/200% layout was inspected, but the complete accessibility/font matrix was not completed.

Device testing found and fixed three presentation problems: Jump to latest targeted the beginning of an oversized final answer rather than its end; header icon buttons lacked accessible labels; and a web-only reference was described as a cited note. The source list also now uses recorded web coverage instead of always displaying unknown coverage.

The subsequent normal release-app test sent a real read-only Scout request using the configured vault and model. The run ended before an answer completed, with the first vault tool displayed as an error and the answer marked incomplete. Its cause was not diagnosed before the owner requested all testing stop. Normal end-to-end Scout answering and citation navigation therefore remain unverified. Do not treat installation, the disposable checks or the passing host checks as evidence that the complete implementation plan is finished.
