# Native dashboard implementation

Version 1.10.0, code 15. Entry: **Feed → menu → Dashboard**, also **Settings → Dashboard**. The original HTML mockup remains an independent design preview, with explicit demo usage. The Android dashboard uses actual local data only.

## Delivered

- Seven native Compose sections: Overview, Vault, Habits, Recall, Scout, Costs and Health, using the existing Spark palette and components.
- Current vault inventory, source quality and category counts, note filtering and links, scheduler-derived recall inventory, retained review grades, unresolved references, isolated notes, pending captures, pending card writes and recovery counts.
- Private request accounting across both provider transports, including direct query embeddings, images/video, transcription, classification, ingest, cards, Ask and Scout. Retries remain separate requests. Scout's existing IDs prevent nested transport accounting and cross-session imports from counting the same attempt twice.
- Input/output, cache reads/writes, reasoning, reported charge coverage, daily cost/token history, workflow and stage breakdowns, retries/failures, cost/token averages per fully reported completed operation, median/p95 operation cost, per-saved-card economics and individual request/operation receipts.
- Capture UUID correlation through a private hashed map; direct capture, processing, standalone cards, semantic search, Ask, recall feedback and Scout have explicit operation scopes. Parallel classification, naming and tool workers inherit the current immutable scope. Generic indexing is a separately named shared batch.
- Verified card-write receipts survive approval interruption and are idempotent by proposal ID. In-memory drafts do not count as saved cards. Persisted recall events are deduplicated by the existing card queue operation ID before the queue is removed.
- Date/model/workflow filters, filtered JSON export through the existing local FileProvider, pause/clear controls and local 90-day retention. No dependency, account, external analytics service or new periodic scheduler.

## Important boundaries

The initial ingest request returns both note planning and recall cards. Its total charge belongs to capture processing. There is no separately observable embedded-card drafting charge, so the dashboard does not manufacture one. Standalone card requests and Scout card requests have identifiable card stages. Shared background embedding batches are shown separately; no arbitrary allocation to individual captures.

AI spend is app/device-wide; inventory refers to the currently selected Sparkbox-visible vault roots. Operation IDs for captures/card jobs are scoped by a hash of vault identity. New metadata does not contain prompts, notes, URLs, credentials, recordings or provider error text. Exports are explicit user actions and contain metadata, not chat bodies.

Legacy Scout sessions supply existing request history and partial-history flags. Existing lifetime feature counters cannot reconstruct earlier daily use. Clearing the dashboard journal does not delete Scout conversations, so their existing usage remains visible. Pausing new dashboard recording also leaves Scout's pre-existing per-chat usage behavior intact. Deleting a Scout conversation removes its mirrored journal data through a tombstone, including when recording is paused.

Completed-operation means require retained, whole request history and complete reporting. Partly model-filtered operations are excluded. Input/output and cost completeness use separate cohorts. Failed/interrupted costs remain in total spend. Queued Scout inputs delivered in a shared turn are marked superseded/batched rather than assigned fictitiously independent completed-operation costs. Background jobs do not count as active user days.

Statistical metadata is retained for 90 days, cleaned on application start and dashboard maintenance, with a 24 MiB local safety ceiling. A storage problem is surfaced as incomplete coverage. Existing Scout journals retain their existing independent lifecycle. Process-killed pending requests are shown as interrupted when no request/run is active.

## Validation

Run:

```sh
./gradlew check assembleRelease
python3 build.py
node docs/dashboard/check.cjs
```

The accounting checks cover exact decimal sums, unknown versus zero, retries, stream metadata deduplication, nested-meter suppression, request-date preservation, session deletion while paused, torn writes, clear during active operations, card receipt replay and multiple approvals, retention compaction, inventory exclusions and graph resolution. Native operation-cohort tests cover missing history, Scout scope, model fragments and independent token/cost completeness.

The signed release build includes R8/resource shrinking and uses the existing signing identity. The artifact is `build/gradle/outputs/apk/release/sparkbox-release.apk`. It has not been installed in this session: no Android device or emulator is connected. A successful build does not verify phone layout, Android lifecycle behavior or actual provider billing.

## Device qualification still required

Before distributing: compare totals against one actual Scout conversation; process one disposable capture; generate/save cards; verify real provider receipts and retry attribution; kill/restart during an attempt; test pause/clear/delete; test offline and revoked vault permissions; inspect 360dp/130% font and TalkBack; check theme and Back restoration. Use a disposable test vault or back up the phone vault first. Do not mass-reprocess the owner's notes for validation.

The wider metric catalog includes optional future work: complete hourly/foreground usage, matched capture-to-revisit cohorts, budget pacing/provider-key reconciliation, strict YAML validation and semantic duplicate inspection. The dashboard exposes only data that the current app can support; it does not show fabricated values for those diagnostics. Whole-app on-device qualification remains a release gate.
