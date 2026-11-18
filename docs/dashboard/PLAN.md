# Sparkbox dashboard: research, design and delivery plan

Research date: 17 November 2026. Inspected checkout: `c844471`, version 1.9.2. User choice: **Android-first, with a desktop preview**. Approved for implementation. Native dashboard and local accounting are implemented in version 1.10.0; see [implementation and validation](IMPLEMENTATION.md) for delivered scope and remaining device qualification. The sections below retain the original design rationale.

## Purpose

Understand what is in the knowledge garden, what gets used, what Scout does, what AI costs, and what needs attention. The dashboard should answer a question and open the relevant notes, cards or requests. It should not become another obligation to maintain.

Three primary signals: knowledge revisited, captures reaching usable notes, and recorded AI spend with coverage. Recall grades and successful retrieval help interpret reuse. Counts, tokens and time are diagnostics, not personal achievement scores. No arbitrary daily growth target, guilt streak, universal health score or automatic AI-written interpretation.

## What the code actually supports

| Evidence | Current behavior | Dashboard implication |
|---|---|---|
| `VaultStore.java:25–95` | Scans `Knowledge/` and legacy `Sources/`; caches parsed graph | Reuse cached graph. Do not expand journal/health access to build this feature. “Vault” means Sparkbox-visible vault. |
| `Vault.java:34–46, 200–229` | Catalog detection, metadata, links, card sections | Distinguish content notes, infrastructure, attachments and unreadable files. Reuse graph resolution. |
| `Usage.java:14–29` and `Usage.hit` callers | Feature counters and last-use timestamps in private preferences | Lifetime counts only. No honest historical hourly/day chart from these counters. Some declared features have no current callers. `ask` combines Ask and Scout. |
| `SessionUsage.java:24–77, 184–260` | Per-attempt request journal, fold by request ID, decimal costs, coverage, legacy usage | Build cross-session Scout analytics over this reducer, not another independent token counter. |
| `ScoutUsage.java:48–118` | Conversation, naming, cards and compaction transport; generation recovery, five missing charges per refresh | Include all request purposes and retries; bounded reconciliation. Model metadata is cached daily. |
| `Session.java:109–119, 231–251` | Timestamped append-only session JSONL; listing reads full session files | Aggregate off main thread; cache per-file summaries. Session deletion can erase local history. |
| `ScoutRuns.java:245–254` | Chat deletion deletes its JSONL | Show retained-history boundary. Decide whether future numeric usage survives chat deletion. Default follows existing deletion until explicitly changed. |
| `Model.java:162–170, 315–379`, `OpenRouter.java` | Non-streaming retry transport, direct short-timeout embedding path, separate streaming transport | App-wide cost needs both transports and the direct embedding path. Do not only instrument Scout. |
| `Cards.java:22, 63–94` | Card state plus newest 50 review entries; grade, seconds, optional confidence | Review history is retained history, not lifetime history. Unique cards and review attempts differ. |
| `CardStore.java:40–88` | In-memory card state includes pending writes | Dashboard can show pending save state; do not count a queued update as verified persistence. |
| `Sched.java:3–12`, `Study.java:18, 42–56` | FSRS-4.5; learned heuristic is interval ≥21 days; weak includes any prior lapse | Do not call the existing “mastery” proportion actual retention. README's SM-2 description is stale. |
| `SparkView.kt:285–350`, `Ui.java:39–55`, `SparkTheme.kt` | Topic tiles, horizontal deck chips, emerald actions, slate surfaces, 16–20dp corners | Extend this language. Android remains Compose plus Java state. |
| README and `docs/scout-qualification.md` | Scout schedules disabled pending qualification | Show “Disabled / awaiting qualification,” not a successful automation count. |

The repository and Mac vault were available. Phone preferences, pending captures, embedding cache and private Scout sessions were not. No actual token/cost totals or app usage history were obtained. No credentials were inspected. Source text was used locally for counting; this prototype makes no provider calls.

## Snapshot findings

The read-only script and `snapshot.json` contain the reproducible result and timestamp. Counts cover content files under the two supported roots. Files named index, log, Inbox and My cards are excluded from content counts. The owner's drill-file cards are included in recall inventory, never treated as a source.

| Metric | Observed |
|---|---:|
| Content notes | 1,065 |
| Source records | 475 |
| Topics | 155 |
| People | 210 |
| Entities | 199 |
| Learnings | 25 |
| Other content notes | 1 |
| Valid-looking card blocks | 2,824 |
| Cards with saved scheduling state | 15 |
| Retained review entries | 17 |
| Source quality: full / partial / title_only / failed | 137 / 265 / 67 / 6 |
| Inbox entries | 1 |
| Files rejected by strict YAML parsing | 22 |

Strict YAML failures commonly involve apostrophes inside single-quoted link arrays. The research scanner uses a limited scalar fallback to count these files and flags them. It does not repair them. Sparkbox uses a more permissive parser: a strict YAML warning is not proof Sparkbox cannot open that file. The source manifest declares 446 records; filesystem contains 475 under this definition. Reconcile paths and exclusions before treating that difference as missing data.

Capture dates describe surviving notes. They are not app-open dates. The Mac snapshot does not prove phone sync freshness. Card counts come from a small research parser, not the production Java parser; the production dashboard must use `Cards.parse`, `Study.filter` and `Spark.plan` for exact parity.

## Approaches considered

| Approach | Benefit | Cost / limit | Decision |
|---|---|---|---|
| Native dashboard over existing caches and Scout journals | Offline, private, low maintenance; reuses current navigation | Needs modest local history and broader cost instrumentation | **Recommended** |
| Desktop dashboard reading Markdown only | Easy browsing and vault health | Cannot see private Android usage or Scout charges | Use as this design preview; optional explicit export later |
| Hosted analytics platform / warehouse | Cross-device, durable history, complex queries | Accounts, uploads, new privacy surface and operational cost | Not justified for this personal app |

Start with current facts and transparent gaps. Add collection only where a chosen view needs it. No analytics SDK, remote database, new background scheduler, graph-layout engine or recurring AI calls.

## Navigation and visual design

Open **Dashboard** from the existing app menu. Keep Feed, Spark and Scout as core tasks. The dashboard opens Overview, with a horizontally scrollable section selector: Overview, Vault, Habits, Recall, Scout, Costs, Health. Desktop preview uses a quiet sidebar with the same sections. Detail screens preserve filters and scroll on Back.

Use the current palette: background `#0F141B`, surface `#171C23`, raised `#1E242D`, text `#F1F5F9`, secondary `#94A3B8`, border `#28323F`, emerald `#10B981`. Light mode follows existing `Ui` values. Reuse system sans, tabular numbers, 4dp spacing, 16–20dp radii and topic-tile tints. Emerald indicates actions and selected state; amber indicates incomplete evidence; rose indicates failure. Text accompanies color.

Signature element: a quiet “Captured / Connected / Revisited” strip. These are separate observations, not a conversion funnel unless they share capture IDs and a defined cohort. Dense details live one tap away. Initial phone viewport contains title, data freshness, section chips and two compact metric rows; graphs follow vertically. Two columns only where labels remain readable. Table details become stacked rows on phones.

### Overview

Answer “What is here, what changed, what needs me?” Show current inventory, source quality, reviewed-card coverage, recorded spend and its coverage. Put actionable issues before decorative history. A source-date chart is explicitly labelled as surviving source records. Its period selector must not change current inventory totals. Show recent notes and a route to each detail view. Never infer learning from note accumulation.

### Vault

Inventory by content kind, subject category, Craft/Life/unclassified, source medium and quality. Searchable note table with type, captured date, quality, card coverage and keep status. Sort by captured date or title. Drill into a subject or quality segment. Show source-to-topic links and orphan/unresolved-link lists using existing graph resolution; do not count ambiguous basename matches as resolved. Semantic duplicate detection is later, opt-in and labelled candidate-only. A force-directed graph adds little to the first version; lists and topic tiles answer the question more directly.

### Habits

Separate capture, annotation, reading, search, Ask, Scout and review. Daily activity, weekday/hour distribution, feature mix, capture origins, app-visible foreground time and capture-to-revisit delay. Historical lifetime counters get a distinct card with “before detailed tracking.” Offer heatmap and equivalent list. Zero means observed inactivity; unknown means not collected. For this snapshot, only source capture dates are available; the mockup's actual screen says usage history unavailable. Explicit demo mode demonstrates the future chart.

### Recall

Inventory, active/new/due/overdue/parked/flagged, latest grades, attempt grades, weak spots, review activity, elapsed answer time, confidence versus grade and topic coverage. Reuse current FSRS state. Present “long-interval cards” rather than an unsupported mastery percentage. Explain all-review versus latest-grade denominators. Drill to topic/card and “Study this scope.” Seven-day workload is a projection from current due dates; future grades change it. It is not a guarantee.

### Scout

Session list searchable by name; model/purpose/status/date filters. Per-session requests, reported input/output, cached and reasoning subsets, known charge, missing-charge count, wall duration, tool calls, proposals, applied changes, compactions and outcome. A request timeline opens request ID, generation ID, requested/actual model, purpose, retry link, elapsed time and provider-reported usage. Keep provider metadata separate from content. Context history shows next-input estimate, reported input points and model/window changes; output reserve gets its own marker. No “total context” sum across simultaneous chats.

Separate run completion, request transport result, tool failure, proposal approval and verified write. A generated answer is not proof research was useful. Offer optional “Useful / Not useful” feedback later, not an automatic quality score. Schedules remain visibly disabled until the existing qualification work passes.

### Costs

First show **Recorded AI spend**, coverage fraction and missing requests. Split Scout versus ingest, classify, card generation, voice transcription, image/video understanding, embeddings/search, Ask, direct-note titling and optional recall feedback. Group by model, feature, purpose, day and session. Details are a filtered request ledger; export uses exactly that filter.

Compare local recorded spend with provider key usage only in a separate reconciliation panel. Provider key totals can include other apps and use UTC boundaries; they are not automatically Sparkbox totals. Current key balance/caps are not a vault metric. Keep provider currency as USD. INR/EUR conversion is optional, dated and visibly estimated. A manual monthly budget is a preference; alerts are observational, not a hard cap. Do not promise an exact stop while requests are in flight. Optional scenario calculator is clearly hypothetical, not a new model recommendation or a retrospective bill.

### Health

Vault access/freshness; scan progress; unreadable notes; strict YAML warnings; incomplete source extraction; title-only records; manifest mismatch; orphan topics; unresolved/ambiguous links; pending capture count and oldest age; failed jobs by reason; pending card saves; recovery copies; embedding coverage/staleness; Scout interruptions and pending approvals. These need explicit availability. “No data from phone” is not “healthy.” Show concise remediation links; preview changes before bulk repairs. No automatic reprocessing on dashboard open.

## Expanded cost and token tracking

Added 18 November 2026 following review. Track each user-visible operation from start to verified outcome, with its individual AI requests underneath. The Costs mockup now includes workflow filtering, input/output totals, daily token consumption, workflow unit metrics, stage breakdowns, card economics, retry/failure spend, operation cost percentiles and end-to-end operation timelines. The values remain explicit demo data until the phone ledger is connected.

### What belongs to each operation

| Operation | AI stages attributed to it | Outcome and units |
|---|---|---|
| Capture processing | Optional voice transcription and image/video extraction; classification; ingest planning; embedded card drafting/repair; embeddings/indexing | One accepted capture ID, one processing operation, verified note outcome. Distinguish note saved from indexing completed. |
| Standalone card creation | Drafting and any model-based repair/regeneration | One card job; drafts, rejected drafts, approved cards, verified saved cards. Local validation and manual editing are not AI calls. |
| Scout interaction | Conversation, continuations, reasoning, compaction, naming and any model-backed card/research tools | One accepted user interaction; requests linked to interaction, run and session. A session can contain many interactions. |
| Ask | Query embedding/retrieval when paid; answer generation | One accepted question; answer outcome. Local retrieval has no model charge. |
| Direct capture | Optional transcription and AI title generation | One text/voice capture to an existing or new note. Local save remains a separate stage. |
| Semantic search | Paid query embeddings; paid reranking only if actually enabled | One deliberate search; local lexical lookup has no model charge. |
| Recall feedback | Optional requested AI answer check | One requested check. Ordinary local card scheduling/review has no model charge. |

Capture itself has **zero AI tokens and zero model charge** when it only saves locally. That does not assert zero device/network expense. Optional transcription and subsequent processing can incur charges. Images, audio and video can have modality-specific units; absent token fields are unavailable, never manufactured from characters, seconds or bytes.

Track two independent dimensions: **originating workflow** and **AI stage/feature**. A Cards request inside an ingest operation belongs to Capture processing in the workflow view and Cards in the stage view. The same request appears once in the ledger. Do not add the workflow and stage views. Scout invokes a tool and the tool makes an AI call: link that request to the tool call and original interaction, rather than creating an unrelated card operation. Independently billed external tool charges remain explicitly excluded until their billing data exists.

### Additional measures and their definitions

| Measure | Calculation and interpretation |
|---|---|
| Cost per completed capture | Mean of the whole-operation charges for completed captures whose every charge is known. Show the priced-completion sample count. Include embedded cards and indexing when those requests are part of that operation. |
| Tokens per completed capture | Mean reported input + output across whole completed capture operations with every token pair reported. The token-complete cohort can differ from the charge-complete cohort. |
| Cost per accepted capture | Known attributed charges / all accepted captures in the matched cohort, including zero-AI local captures. This is a partial lower-bound average when charges are missing; label it accordingly. Requires operation records even when there are no requests. |
| Amortized cost per completed capture | All known charges in a capture cohort, including failed/interrupted captures, divided by verified completions in that cohort. Keep this separate from the successful-operation mean; show failures and missing coverage. |
| Capture-stage cost and tokens | Sum by extraction, transcription, classification, ingest, embedded cards and indexing. Show attempts, charge/token coverage and modality. Manual or local stages appear as zero-AI stages, not fake provider requests. |
| Capture-to-ready latency | Acceptance to verified note save, plus a separate indexing completion time. Queue waiting, local work and model time have different timestamps. Never sum concurrent request durations and call that wall time. |
| Cost and tokens per card job | Sum drafting, repair and regeneration requests correlated to one job. Distinguish completed jobs from failed/abandoned jobs. |
| Drafted / approved / saved cards | Count actual card IDs at each verified transition. Drafts can be rejected or repaired; approval is not persistence. The demo simplifies these outcomes to drafted/saved; production records all transitions. |
| Card-stage cost per saved card | Card-stage charges across fully priced completed operations with saved cards / verified saved cards in exactly those operations. Include drafting/repair retries. Do not divide by rejected or unsaved drafts. |
| Card-stage tokens per saved card | The analogous ratio using a fully token-reported saved-card cohort. The demo reports this only when the displayed priced cohort is also token-complete. |
| Whole-pipeline allocation per card | Optional separate metric: capture processing cost / saved cards in a matched cohort. Name it explicitly because it includes extraction and other work, unlike card-stage cost. |
| Cost and tokens per Scout interaction | Sum every attributed request for one accepted interaction, including its continuations, compaction, naming and AI tool work. Completed-interaction averages use whole, fully reported operations and display the sample size. |
| Scout session and run spend | Sum distinct requests across interactions, including interrupted/failed work. Sessions, runs, interactions and HTTP attempts are separate counts. |
| Scout overhead by purpose | Tokens/cost for compaction, naming, retries and continuations, with conversation and AI tool work separate. Overhead is diagnostic, not automatically waste. Compaction cannot prove counterfactual savings. |
| Daily input/output consumption | Reported sums by original request date; clearly mark partial reporting and preserve separate series. Cached input is included in input; reasoning is included in output. |
| Cache-read share | Sum reported cache-read tokens / input tokens over requests where both fields are reported. Show reporting coverage. Cache-write count and its charge remain separate; no assumed cache savings. |
| Reasoning share | Reported reasoning / output tokens over requests reporting both. A missing reasoning field does not mean no reasoning. |
| Cost per million reported tokens | Optional observed ratio within a single model/modality and a charge/token-complete cohort. This is an effective workload rate, not an advertised model price. Do not compare mixed audio/image workloads using this ratio. |
| Retry-attempt cost/tokens | Requests with attempt >0, not every request in an operation that eventually retried. The preceding failed attempt remains in failure metrics. |
| Failed/interrupted spend | Known charges and reported tokens for unsuccessful request outcomes; optionally group by failed operation as a different view. The sets can overlap retries; do not add these diagnostic totals. |
| Median/p95 operation cost | Nearest-rank quantiles of fully priced completed whole-operation charges; show n. Small samples remain visible, without interpreting p95 as a stable forecast. |
| Most expensive operations | Ranked operation records with modality, stage contributions, model, token/cost coverage and outcome. Drill to each request, preserving its charge receipt. |

The mockup demonstrates the core workflow/stage totals, completed-operation means, card ratios, daily token totals, median/p95, retry/failure charges and operation timelines. The other measures above are production requirements; they are not fabricated from the existing vault snapshot.

### Required event correlation

Use a local operation record even when an operation makes no AI requests:

```json
{
  "operationId": "local-id",
  "workflow": "capture_processing",
  "captureId": "optional-local-id",
  "cardJobId": "optional-local-id",
  "sessionId": "optional-local-id",
  "runId": "optional-local-id",
  "interactionId": "optional-local-id",
  "startedAt": "UTC timestamp",
  "noteSavedAt": null,
  "indexCompletedAt": null,
  "outcome": "pending",
  "cardsDrafted": 0,
  "cardsApproved": 0,
  "cardsSaved": 0
}
```

Each request carries the existing request ID plus operation ID, workflow, stage, role, model/provider, attempt and related-request IDs, optional parent tool-call ID, start/end timestamps, outcome, generation ID and nullable reported usage fields. Fields include input/output, cached reads/writes, reasoning, audio duration or provider modality counts where available, original reported cost and currency, and independent charge status. Do not store new prompt, note or recording copies for analytics. Scope events to the vault/device and retain existing source-of-truth rules.

An operation may cross midnight. Request spend charts attribute each charge to its own request start. Unit economics use the operation-start cohort and fetch **all** requests of each included operation, even those outside that date window. Explain this distinction; a filtered daily request sum need not equal the unit-cohort numerator. Missing/ongoing operations are not completed units. Later verified writes and recovered charges update the same IDs.

### Filter and export behavior

Date/workflow/model filters constrain request totals, stage tables, daily charts and request exports. The mockup excludes partially model-filtered operations from whole-operation averages and states how many were excluded. Opening an operation shows its complete timeline regardless of the model filter. Never divide a fragment of an operation's cost by a whole saved-card or capture count.

Exports include active filters, request rows, coverage and whole matching operation metadata. Native exports should additionally include metric definitions and cohort boundaries so averages can be reproduced. Local zero-request operations must be included in the native operation export. Existing private history cannot be backfilled into new IDs when correlations were not recorded; show “unattributed historical usage” separately.

Promote workflow attribution, card output verification and Scout interaction IDs into Phase 2. They are necessary for the requested tracking, not optional later reporting. Add acceptance checks for retry/repair billing, embedded-card attribution, operation requests crossing midnight, partial model filters, zero-AI captures, audio usage without token fields, zero saved cards and missing final cost. No production collection has been added in this mockup revision.

## Metric catalog

Availability: **V** = derivable from current vault/cache, **S** = existing Scout session journal, **P** = existing private phone state, **N** = new events needed, **L** = later candidate. Availability does not mean collected in this desktop research snapshot. Current-state metrics ignore date filters; event metrics use the selected period. All ratios show numerator, denominator and excluded/unknown counts.

| Metric | Definition / decision | Source; caveat | Priority |
|---|---|---|---|
| Content inventory | Distinct content paths by kind | V; exclude infrastructure, show skipped count | First |
| Source quality mix | Count by recorded quality | V; metadata is extraction completeness, not truth | First |
| Subject / lane mix | Counts and dated additions by primary category; Craft/Life separate | V; unclassified bucket; topic membership can overlap | First |
| Source-card coverage | Sources with ≥1 valid card / eligible source notes | V; choose eligibility explicitly, show all-source baseline | First |
| Why / ideas coverage | Non-placeholder user sections / sources | V; section existence alone is not author intent | Next |
| Kept notes | Distinct notes with `kept: true` | V; not necessarily read | First |
| Dated source additions | Surviving source records by `captured` | V; not capture event counts; undated bucket | First |
| Topic evidence depth | Resolved distinct source links per Topic | V; recompute, do not trust stale `source-count` alone | Next |
| Thin topics | Topics with one or no resolved sources | V; no automatic deletion | Next |
| Unresolved links | Distinct unresolved references and occurrences | V; separate external roots and ambiguous names | Next |
| Isolated notes | No resolved inbound or outbound note links | V; exclude indexes from interpretation | Next |
| Duplicate sources | Same normalized source URL | V; candidate list, not auto-merge | Next |
| Similar notes | Semantic duplicate candidates | L; embedding cost and false positives | Later |
| Knowledge bytes | Markdown content bytes; attachments separate | V/P; not device disk total | Next |
| Capture volume | Accepted unique captures by event time | N + existing lifetime P; duplicate shares excluded | Next |
| Capture medium/origin | URL/text/voice/photo/PDF/video; entry point separately | N; do not infer historic origin from note type | Next |
| Annotation use | Captures with owner Why/Ideas / captures in cohort | N/V; direct thoughts form separate cohort | Next |
| Direct-note saves | Verified existing/new-note capture writes | N/P; not all captures pass Inbox | Next |
| Ingest completion | Successfully processed eligible captures / accepted eligible captures in cohort | N/P; queued, failed and cancelled separate | Next |
| Processing latency | Accepted to verified note write; p50/p95 and sample count | N; exclude still-pending from completed latency, show age | Next |
| Backlog / oldest age | Pending unique capture IDs, age from accepted time | P; unavailable in Mac snapshot | First phone |
| Retry and terminal failure | Attempts and affected captures by error category | P/N; eventual success does not erase retries | Next |
| Capture-to-use | Capture cohort read/reviewed/queried within 7/30 days | N; wait for complete observation window | Later |
| Notes revisited | Distinct note IDs opened meaningfully in period | N; define visibility threshold, avoid recomposition events | Next |
| Features used | Interaction counts by explicit feature/entry point | P lifetime / N period; Ask and Scout split prospectively | Next |
| Active days | Local dates with a deliberate user action | N; background ingest does not count | Next |
| Hour/weekday pattern | Action counts by event's local time and offset | N; travel/DST policy required | Next |
| Foreground time | Sum visible/resumed intervals, idle cutoff | N; time spent is not value | Later |
| Search outcomes | Searches, no-result rate, clicked note, latency | N; no raw query required | Next |
| Ask grounding | Questions with valid citations / eligible answers | N; citation presence is not correctness | Later |
| Card inventory | Valid card IDs by owning path | V/P; composite key prevents collision | First |
| Due / new / overdue | Same rules as `Study.filter` and `Spark.plan` | V/P; parked/flagged excluded from active | First |
| Review attempts | Every retained grade entry in period | V; deleted/truncated cards missing | First |
| Unique cards reviewed | Distinct card keys with ≥1 grade | V; differs from attempt count | First |
| Again/Hard/Good/Easy | Counts per attempt, or latest grade per card/day | V; denominator toggle clearly labelled | First |
| Grade success proxy | Good+Easy / graded attempts; separate Hard | V; not measured long-term retention | Next |
| Answer time | Retained seconds to reveal, median and count | V; excludes post-reveal reading; pauses can inflate | Next |
| Confidence calibration | Grade mix for Sure / Not sure / Guessing | V; optional missing values; no accuracy claim | Next |
| Weak spots | Existing lapse/latest-grade heuristic | V; historical lapse can stay weak forever | First |
| Long-interval cards | Eligible cards with interval ≥21d / eligible cards | V; current heuristic, not mastery | Next |
| Due workload | Current due dates grouped into future dates | V; projection changes after review | Next |
| Scout sessions/runs | Distinct sessions; distinct starts/runs separately | S; resumed runs not new conversations | First |
| Requests/attempts | Distinct request IDs after updates folded | S; retries really consume requests | First |
| Input/output tokens | Sum reported prompt and completion tokens | S/N; missing usage remains unknown | First |
| Reasoning tokens | Reported subset of output | S/N; never add again to output | First |
| Cached input | Reported subset of input and reporting coverage | S/N; unsupported != zero | First |
| Cache writes | Reported write tokens separately | S/N; do not assume free | Next |
| Context pressure | Next-input estimate / known window, reserve separately | S; unknown window means no percent | First |
| Compactions | Events plus before/after context estimates | S; reduced context is not proven money saved | First |
| Retry overhead | Reported tokens/cost for attempts with attempt>0 | S/N; continuations separate; not automatically waste | First |
| Request latency | Duration by outcome/model; p50/p95, n | S/N; not same as whole-run wall time | First |
| First visible token | First rendered chunk minus send time | N; distinct from network first-byte | Later |
| Tool calls/errors | Started/completed calls correlated by ID | S/N; verify durable event coverage before charting | Next |
| Research/discovery | Searches, fetched/read sources, candidate decisions | S/N; unique sources and fetch attempts separate | Next |
| Proposals / writes | Proposed, approved, declined, conflict, verified writes | S; approval is not verified application | Next |
| Run outcomes | Complete/interrupted/cancelled/error/budget limit | S/N; explicit terminal markers preferable | First |
| Recorded spend | Decimal sum of known reported charges | S/N; partial coverage displayed beside amount | First |
| Charge coverage | Known-cost requests / eligible recorded attempts | S/N; historical unenumerated gaps separately flagged | First |
| Feature/model spend | Sum recorded costs grouped by attributed feature/model | S/N; requested and actual model distinct | First Scout; Next all app |
| Cost per completed ingest | All attributed capture-attempt charges / completed captures | N; compare matched cohort, include failed attempts | Next |
| Cost per Scout run | All request purposes for matching runs / completed runs | S; also show failed/cancelled cost separately | Next |
| Monthly pacing | Recorded spend / observed calendar days × days in month | S/N; lower bound when coverage partial; optional | Later |
| Key usage / remaining cap | Provider key metadata at refresh time | Provider; broader scope, UTC, nullable limit | Later |
| Pending saves / recovery | Queue entries and oldest age; recovery copies | P; verified persistence separate | First phone |
| Embedding coverage | Current model/hash embeddings / eligible notes | P; stale model/hash separate | Next |
| Scan and UI health | Scan duration/skips, startup and jank | P/N; diagnostic pane, not overview hero | Next |

## Accounting rules that must survive implementation

1. One actual HTTP attempt is one request. Update by `(deviceId, requestId)`; replaying or recovering a charge cannot add another request. Import generation IDs only when uniqueness is verified; they may be absent.
2. Use `BigDecimal` for USD amounts. Sum unrounded amounts, round for display. A returned zero is a known zero. Missing cost is unknown. Show known-cost sum plus missing/pending counts and start of coverage.
3. Total reported processed tokens = prompt + completion only when both exist. Cached input and reasoning output are subsets. Display per-field coverage; do not promote partial fields into a complete total.
4. Repeated conversation history counts on every billed input. Current next-input estimate is a gauge, not a lifetime counter. Never add context snapshots to processed-token totals.
5. Attribute costs to request start time. A later recovered cost updates that original day. Retain last-updated time separately. In-flight requests remain pending; process death becomes interrupted after run recovery, never fabricated completion.
6. Dashboard period = half-open `[start, end)` in a visible timezone. Offer 7/30/90 days, calendar month, all retained history and custom dates in production. Compare equal-duration previous periods; current partial day is labelled. Missing prior coverage means no percentage change.
7. Never add app ledger totals to Scout session totals if both represent the same requests. The cross-session reducer handles existing history; a future app ledger owns new requests globally. Shared IDs and an explicit cutover timestamp prevent duplication.
8. Provider charge is authoritative for recorded requests; historical price-table multiplication is only an estimate. Multimodal, caching, BYOK and independent tool charges can differ. Show external tools/fees as excluded unless separately recorded.
9. Request status and charge status are separate. Reconciliation currently writes a status; the dashboard must preserve the execution outcome rather than count “reconciled” as transport success.
10. Deleted sessions and review-log truncation bound historical coverage. Do not silently preserve deleted chat content. A future “retain anonymous numeric spend” preference needs a clear deletion policy and user choice; default retains no extra copy.

OpenRouter documents usage costs and token fields directly in responses, generation lookup for missing metadata, and separate key-level usage. These are the recommended evidence sources. See [usage accounting](https://openrouter.ai/docs/cookbook/administration/usage-accounting), [generation metadata](https://openrouter.ai/docs/api/api-reference/generations/get-request-&-usage-metadata-for-a-generation), and [key limits and usage](https://openrouter.ai/docs/api_reference/limits). Use their field meanings; no telemetry service is needed. Read on 17 November 2026.

## Minimal architecture

Keep existing Java logic / Compose UI split. Proposed files are additions, not currently implemented:

- `Dashboard.java`: pure reducers and metric definitions; no Android calls or network.
- `DashboardScreen.java`: background loading, date/scope state, navigation, refresh and export. Reuses existing screen lifecycle and actions.
- `DashboardView.kt`: Overview and section content, reusing `SparkTheme`, `TopBar`, pills and shared page blocks.
- Add a small `Usage` daily-event store only when Habits ships. Existing lifetime values remain a separate legacy baseline. Reuse existing private JSONL patterns; SQLite is unnecessary until measured volume or query latency justifies migration.
- Add a small common request ledger when all-app cost ships. Transport boundaries: `Model.send`/`exchange` including direct embedding lookup, and `OpenRouter.stream` via existing attempt hooks. Reuse existing Scout request IDs; avoid nesting two meters for Scout naming. Add explicit feature/operation attribution at callers; no fragile global mutable feature name.

Use cached vault/card state immediately, then refresh on existing scan/session/queue change notifications. Compute on a worker. Cache summaries keyed by vault identity and session file revision/length; invalidate deletions and model changes. No repeated full scan on section switches, no model call on open, no perpetual polling. An active Scout request can update visible counters through existing watcher callbacks.

Data classification per metric: current snapshot / historical recorded / estimated / unavailable. Add `asOf`, `coverageStart`, known count, missing count, scope and parser/skipped issues to the view model. Per-request export contains numbers/IDs/statuses and no note bodies, prompts, URLs or credentials by default. Note paths are optional. Exports are user-triggered and local. Retention defaults for new events: 90 days of metadata, older daily aggregates only if the user selects retention; clearing statistics deletes both. Do not enable new collection until that setting and policy are reviewable.

## Implementation sequence and acceptance criteria

### Phase 0 — approve design and inventory semantics

Review this plan and mockup. Confirm content exclusions, timezone, “revisited” meaning and whether numeric spend should survive chat deletion. These do not block the mockup. No live app modifications in this research task.

### Phase 1 — useful dashboard without new tracking

Add menu entry and native Overview/Vault/Recall/Health. Reuse graph, cards and queue accessors. Show stale/partial/permission states. Add Scout aggregation from existing journals and link existing per-session usage screen. Current-state rows ignore date range explicitly. Costs hero says recorded Scout spend until broader tracking exists.

Acceptance: totals reconcile with included path/card lists; invalid files visible; empty vault/permission denied recover cleanly; duplicate/replayed request updates count once; missing charge never displayed as zero; no provider calls on open. Existing screens retain performance.

### Phase 2 — app-wide request accounting

Trace all `Model.post`, direct `Model.exchange`, `OpenRouter.stream` and tool/provider billing call sites before edits. Add shared IDs, operation correlation, feature attribution, start/update/end events, coverage epoch and decimal sum. Preserve existing Scout detail data. Handle retry, fallback, cancellation, stream-without-final-usage and process kill. Keep reconciliation bounded and manually triggered from detail.

Acceptance: each actual attempt recorded once across both transports; Scout naming not double counted; embedding/transcription/video paths represented even if no tokens reported; delayed charges update original day; independent tool fees labelled excluded. Test with synthetic provider responses and minimal device calls, not bulk re-ingest.

### Phase 3 — usage history

Instrument capture acceptance, verified write, meaningful note open, search/result click, Ask/Scout sends and successful review persistence. Deduplicate by event ID. Distinguish capture IDs from note IDs; direct-note saves have their own outcome. Add daily charts and heatmap with visible coverage start. Import retained card review history with a historical-partial flag; never invent older feature events.

Acceptance: no events from recomposition; background work does not count as active use; retry/import replay cannot duplicate events; local midnight, travel and DST behave as documented; charts and filtered export agree; old counters remain unchanged.

### Phase 4 — useful diagnosis, only after evidence accumulates

Add matched capture cohorts, time-to-use, topic evidence depth, embedding freshness, optional key reconciliation and optional budget pacing. Add estimated memory metrics only with clear FSRS semantics. No automatic model ranking without comparable workloads and user feedback. Cross-device display can use explicit sanitized exports; no backend required.

## Focused validation

Pure Java reducer fixtures: partial/missing/zero cost; decimal rounding; repeated updates; retries versus continuations; naming/compaction; legacy usage; context/model changes; date boundaries; zero denominator; duplicate card IDs across files; retained-log limits; session deletion; transport-versus-charge status. One coherent fixture per failure mode, no test framework expansion.

Device checks: permission loss; offline/stale cache; active request updates; process kill mid-write; 130% font; TalkBack reading order; light/dark themes; 360dp width; filtered export; back restoration. Use existing startup/scrolling baseline gates. Target cached dashboard paint under 300ms and section switch under 100ms as proposed measurement targets, not proven performance. Profile before optimizing; keep scans off main thread.

The HTML mockup is intentionally isolated under `docs/dashboard/`. It uses local snapshot data and explicit deterministic demo events, no dependencies or server calls. It demonstrates interaction and hierarchy; provider synchronization, native actions, full graph resolution and all catalog metrics belong to production phases above. No note or source content is edited by the prototype.

## Review decisions

Confirmed: Android-first; desktop preview. Proposed defaults: current device timezone; user-triggered export; no remote telemetry; no extra history surviving chat deletion; USD for billing; no hard spending limit; native dashboard in menu. Remaining product choices can be settled during implementation: meaningful-open threshold, optional numeric-history retention, monthly budget, app-wide event retention and a dedicated OpenRouter key if provider reconciliation is desired.
