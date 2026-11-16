# Scout reference investigation

Date: 2026-11-16. Scope: inspiration and design research; no application implementation.

## Evidence boundaries

Three repositories were inspected. The two references were cloned locally with `--depth 1`; findings refer to these exact snapshots, not an assumed production configuration.

| Repository | Snapshot | Local checkout |
| --- | --- | --- |
| Sparkbox | `2f90cc639547c9d01bbbcd63252d35afe43b7054` | `/Users/venkateshmurugadas/software_codes/sparkbox` |
| DeepSeek chat-demo | `5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c` | `/Users/venkateshmurugadas/Projects/scout-reference-research/chat-demo` |
| Hugging Face paperswithcode | `3c4cee93d6080b3f3922940d1842395996695234` | `/Users/venkateshmurugadas/Projects/scout-reference-research/paperswithcode` |

Observed live: DeepSeek entry screen, settings, populated conversation, activity timeline, source panel; Papers with Code entry screen and authenticated research chat. DeepSeek's populated conversation used a labelled synthetic archive through its public import UI. No DeepSeek generation ran and no API key was supplied. Synthetic wording and elapsed times are fixtures, not measured model behavior. Mobile DeepSeek behavior below comes from CSS inspection; responsive viewport application was inconsistent during browser inspection, so it is not claimed as a verified mobile test.

Sparkbox UI findings come from current Compose/Java code. An existing `docs/baseline/dark-ask.jpg` was inspected and actually showed Search; it is not accepted as evidence of current Scout appearance. No phone UI, production keys, private session logs, or personal note contents were accessed. Existing architecture and agent-design documents were checked against source because their implementation status is partly stale.

The user's refinements: use DeepSeek primarily for frontend inspiration, Papers with Code primarily for backend inspiration; also run real Papers with Code questions to study answer style.

## What the repositories actually implement

DeepSeek is a static React chat client. The browser sends requests directly to a configurable provider using an Anthropic-compatible Messages/Files interface and provider-specific search, fetch, title and speech capabilities. There is no application backend or locally implemented autonomous research server to transplant. Its source is valuable for presentation, stream reduction, local persistence and conversation interaction. [Repository README](https://github.com/deepseek-ai/chat-demo/blob/5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c/README.md).

Papers with Code is a React/FastAPI/PostgreSQL research product. Its code contains multiple execution routes: local MCP, Modal execution, and externally hosted Pi workers, selected through admission/settings. Source presence does not establish which production route handled a particular browser request. The live UI does establish the observed tool names and rendered answers. [Runner selection](https://github.com/huggingface/paperswithcode/blob/3c4cee93d6080b3f3922940d1842395996695234/backend/services/chat_turn_runner.py#L207).

Sparkbox already has a Java port of Pi concepts. `Scout` binds `Agent`, `Session`, `Compact`, `VaultTools`, `Mcp`, usage accounting, Android services and Compose. Replacing this with a Python agent service would discard working local-first behavior and violate the agreed phone-only architecture.

## DeepSeek: the UI worth borrowing

| Element | How it works | Scout adaptation |
| --- | --- | --- |
| Composer as one surface | Text occupies upper area; thinking/search toggles and attachment/voice/send occupy lower rail | One rounded composer; visible Research control, source scope and context attachments; secondary options in a sheet |
| Quiet answer canvas | Assistant Markdown flows directly on background; user prompt has a bubble | Remove permanent answer-card borders; reserve surfaces for previews, approvals and failures |
| Process separate from result | `ReplyArea` renders ordered thinking/search/open/find steps; final Markdown has its own renderer | Group tool activity under one outcome-aware summary; keep answer readable |
| Sources beside reading | Inline citation preview plus expandable source panel; metadata includes title, site, date, snippet | Tap citation opens native source sheet with excerpt and Open note/page |
| Progressive disclosure | Source count and small action rail appear after answer | Compact source count, Copy, Save and menu; avoid always-open logs |
| Reading control | `MessageList` tracks sticky scrolling, restores anchors and preserves branch-switch position | Follow only while near end; respect scroll-up; explicit Jump to latest |
| Independent conversation state | Message tree and selected branch are stored independently from visible branch | Retain JSONL; separate visible transcript from compacted model context; defer full branch UI |
| Stream handling | Validated protocol events reduce into typed content blocks; rendering batches by animation frame | Explicit provider terminal detection; structured local events; bounded Compose publication |
| Failure-specific actions | Upload retry, stopped answer, refusal and protocol errors have distinct presentations | Retry model step, reconnect tool, resolve conflict and resume interrupted run are distinct actions |
| Local history | IndexedDB separates summaries, messages and attachments; imports stage before atomic replacement | Keep current local files; add rebuildable session summaries only when needed |

Primary implementation anchors:

- [SessionView.tsx](https://github.com/deepseek-ai/chat-demo/blob/5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c/src/components/session/SessionView.tsx), `ChatInput.tsx`, `ChatInput.module.css`: shell and composer.
- [ReplyArea.tsx](https://github.com/deepseek-ai/chat-demo/blob/5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c/src/components/messages/ReplyArea.tsx#L56), `services/chat/view.ts:176`: ordered process projection.
- [AssistantMessage.tsx](https://github.com/deepseek-ai/chat-demo/blob/5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c/src/components/messages/AssistantMessage.tsx): final content, notices, sources and actions.
- [MessageList.tsx:33](https://github.com/deepseek-ai/chat-demo/blob/5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c/src/components/messages/MessageList.tsx#L33): sticky threshold and scroll ownership.
- [SearchPanel.tsx](https://github.com/deepseek-ai/chat-demo/blob/5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c/src/components/search/SearchPanel.tsx), `SourceCard.tsx`, `CitationLink.tsx`: evidence exploration. CSS changes desktop side panel to full-screen below 768 px; use native mobile navigation rather than copying breakpoint values.
- [sse.ts:20](https://github.com/deepseek-ai/chat-demo/blob/5d5dd754ebc1948e977ddb880a9eb5aa25f37b4c/src/services/api/sse.ts#L20), `parseEvent.ts`, `chat/reply.ts`, `chat/store.ts:962`: stream framing, validation, reduction, abort and settlement.
- `services/chat/db.ts`, `branches.ts`, `history/schema.ts`: local storage, branches and validated import.

Do not copy blindly. The synthetic fixture contained one successful and one failed page-open operation; the visible group said “Read 2 pages.” Scout must count successful reads, not requested opens. The reference uses clickable non-button elements in some places; native Scout controls still need correct accessibility semantics. DeepSeek's provider-specific search APIs are not a generic web-search implementation. Its localStorage API-key pattern is not a replacement for Sparkbox's encrypted key storage.

Captured examples:

![DeepSeek conversation with labelled synthetic content](/Users/venkateshmurugadas/Projects/scout-reference-research/deepseek-desktop-conversation.jpg)

![DeepSeek source panel with labelled synthetic content](/Users/venkateshmurugadas/Projects/scout-reference-research/deepseek-desktop-sources.jpg)

## Papers with Code: backend ideas to shrink for a phone

| Mechanism | Source | What Scout should retain |
| --- | --- | --- |
| One normalized event vocabulary | `backend/services/chat_turn_event_service.py`; frontend `chatLifecycle.ts` | Convert provider/tool events once; one projector drives live and restored UI |
| Detached execution | `chat_detached_turn_service.py`, `chat_live_stream_service.py` | A view observes work; closing it is not cancellation. Persist state so process death is explicit |
| Sequenced replay | `chat_turn_event_repository.py:41`; `chatReconnect.ts:17` | Monotonic local sequence, deduplication and replay. No SSE server needed for local observers |
| Ownership fencing | `chat_turn_job_repository.py:70`; `chatTurn.ts:82` | `runId` plus generation/view revision prevents late callbacks mutating another run or screen |
| Admission and limits | `chat_turn_lifecycle.py`, worker policies and prompt | Bound actual calls, time and outputs; reserve synthesis budget; never rely only on prompt instructions |
| Authoritative citations | `chat_citation_service.py`, `chat_stream_service.py`, frontend `chat.ts` | Citation targets come from structured evidence snapshots, not model-invented paths |
| Narrow tool access | `docs/chat-pwc-mcp.md`, `mcp_research_prompt.ts` | Known task-specific tools, explicit call/output budgets and request limits |
| Prompt contract | `backend/chat_sandbox_worker/prompt.py:237`, Pi prompt renderer | Version the response policy; exact source IDs; concise defaults; comparisons when useful |
| Relevance with reasons | `research_follow_service.py`, `research_topic_service.py` | User-selected interests; deduplication, freshness, diversity and “why this matches” |
| Durable delivery | `research_delivery_service.py` | Freeze a discovery result before notifying; stable IDs prevent repeated notifications |

Important distinction: the repository includes heavy server concerns—accounts, billing gateways, sandbox capacity, leases, external workers, database admission and artifact authoring. Scout needs their invariants, not their infrastructure. One process, local JSONL and Android JobScheduler can implement the relevant subset.

Reference links: [event journal](https://github.com/huggingface/paperswithcode/blob/3c4cee93d6080b3f3922940d1842395996695234/backend/services/chat_turn_event_repository.py#L41), [typed lifecycle](https://github.com/huggingface/paperswithcode/blob/3c4cee93d6080b3f3922940d1842395996695234/backend/services/chat_turn_lifecycle.py), [MCP integration](https://github.com/huggingface/paperswithcode/blob/3c4cee93d6080b3f3922940d1842395996695234/docs/chat-pwc-mcp.md), [research follows](https://github.com/huggingface/paperswithcode/blob/3c4cee93d6080b3f3922940d1842395996695234/docs/research-follows.md).

## Sparkbox: observed gaps, with exact anchors

Paths below are relative to Sparkbox's `src/me/venkatesh/saveobsidian/`.

| Finding | Evidence | Consequence |
| --- | --- | --- |
| Display uses compacted model context | `AskScreen.java:155` calls `Session.context()`; `Session.java:193` applies compaction/context edits | Old conversation can disappear from visible history although raw log remains |
| Attempts look completed | `Chat.java` derives work from assistant tool calls and ignores tool-result outcomes | Declined/failed writes may be labelled “Edited” or “Wrote” |
| Startup steering can disappear | `AskScreen.java:231` clears input after steering; `ScoutRuns.java:223` ignores it while `agent == null` | User message can be lost while MCP setup runs |
| Draft/context not per-session durable | `AskScreen.java` keeps one `draft`, `ctxPath`, `ctxTitle` in screen memory | Tab rebuilding partly works; process death and chat switching need stronger ownership |
| Approvals are in-memory waits | `ScoutRuns.java:199`, `Approval.answer` queue | Cannot restore exact pending change after process death; foreground work can wait for hours |
| Preview clipped | `VaultTools.java:301` clips to 300 characters | Full approved change is not reviewable in current card |
| Unknown citation removal keeps claim | `Scout.java:89`; `Cites.java:51,75` | Pointer presence is not claim support; user can see unsupported prose without warning |
| Inline citation navigation incomplete | `AskView.kt:154` supplies a no-op `Md.Open` handler; citations become `[n]` text | Sources require separate list; web evidence lacks unified native preview |
| Unconditional scroll | `AskView.kt:196` animates on item count or every 200 live characters | Reading older material can be interrupted |
| Unstable row identity | `AskView.kt:223` uses index without stable message key | Insertions risk moving expansion state to wrong row |
| Stop hidden when typing | `AskView.kt:246` uses Send instead of Stop if input contains text; offline disables shared action | Cancellation is not consistently reachable |
| Research mode not durable | `/research` parsed at startup; retry passes `research=false` | Recovery can silently change limits and research policy |
| Context display and policy diverge | `Scout.java:17,109` uses fixed 128K for compaction; `ScoutUsage` displays fetched capacity | Smaller models can overflow before normal threshold; large models compact unnecessarily |
| Abrupt stream EOF accepted | `OpenRouter.java:49–85`; no required terminal reason; `Agent.assistant` defaults to stop | Incomplete text or apparently valid partial tool arguments can be treated as finished; needs fixture reproduction |
| Persistence failure does not stop work | `Agent.java:109` emits `session_error` and continues; runner does not surface it explicitly | Durable history can diverge from real side effects |
| Eager MCP setup | `Scout.java:101`; `Mcp.connectAll` connects sequentially every run | Even a vault-only question waits for unrelated servers; impact not benchmarked |
| Tool transport caps output after receipt | `Mcp.post` buffers response; `Mcp.cap` applies later | Model text cap does not bound network bytes, parsing memory or total duration |
| Session loading scales with full logs | `Session.list` opens each whole file; `AskScreen.loadChats` opens sessions | Larger histories may become slow; measure before adding database |

These are code observations, not claims that every path failed on a device. The redesign spec gives targeted acceptance scenarios to reproduce and close them.

## External constraints verified

- Android documents aggregate time limits for `dataSync` foreground services and an `onTimeout` callback. A six-hour approval wait is not a safe scheduling strategy. [Android timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).
- Periodic background work is inexact; Android documents a minimum periodic interval of 15 minutes. Scout should promise a preferred window and show late/missed runs. [Work scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
- OpenRouter exposes context length, pricing and supported parameters. Existing Scout metadata caching should be extended, not duplicated. Metadata is model/provider-dependent and is not proof every routed endpoint supports every parameter. [Models API](https://openrouter.ai/docs/api/api-reference/models/get-models).
- Existing generation-ID cost reconciliation is appropriate; unknown cost must remain unknown. [Generation metadata](https://openrouter.ai/docs/api/api-reference/generations/get-generation).
- MCP tool annotations are hints. Keep host policy authoritative; trusting read-only metadata must be explicit. [MCP tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).

## Baseline validation

Ran `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew captureTest --console=plain` against unchanged Sparkbox source. Passed: Agent 76 checks; Session usage 32; Vault tools 56; MCP 15; Capture and append 332, as reported by the harness. This validates the existing baseline, not the proposed design or live Android behavior.

## Live response study

Authenticated queries run in the user's Papers with Code account, using public technical prompts only. No personal notes uploaded. The chat is private; no share link was created. Browser transcripts and screenshots are local research evidence, not independent scientific verification of generated claims.

### Query 1: comparative research

Prompt: “What is the difference between retrieval-augmented generation and long-context language models? Compare the evidence, cite relevant papers, and explain when each approach works best.”

Observed completed response: definition first, then named comparative evidence, then practical choice guidance. Section labels were “The core difference,” “What the comparisons show,” and “When to use each.” Citations linked paper titles beside empirical claims. The answer qualified numeric findings by benchmark/model/setup and avoided declaring a universal winner. It used short explanatory paragraphs and three decision bullets rather than a compulsory table.

Observed execution: method/task resolution, hybrid search, paper reads, targeted keyword search and paginated reading. Approximately twelve tool rows were visible. One paper was read again. Do not infer an efficiency benchmark from this single run; it demonstrates both grounded synthesis and possible redundant retrieval.

What to adapt: answer the distinction first; empirical claims get nearby sources and limitations; end in a practical decision. What to improve: one collapsed activity summary instead of a screen of raw tool identifiers; avoid repeated reads when evidence is unchanged.

Evidence: `/Users/venkateshmurugadas/Projects/scout-reference-research/pwc-query-1.txt` and `pwc-query-1.jpg`.

### Query 2: practical follow-up

Prompt: “For a personal Android notes assistant with a few thousand Markdown notes, what is the simplest retrieval design you would start with? Ground the recommendation in the papers you read, distinguish findings from your inference, and keep it under 250 words.”

Observed completed response: a direct recommendation, three implementation steps, a paragraph summarizing paper findings, then a paragraph explicitly marking its engineering inference. No additional tool calls appeared for this follow-up; the response reused prior evidence. This is a strong interaction pattern: a small answer can remain grounded without repeating the investigation.

The recommendation was local lexical search with SQLite FTS5. It was not based on inspecting Sparkbox. Scout already has hybrid retrieval, so that specific recommendation is not adopted. Borrow the evidence/inference distinction and progressive depth, not a context-free architecture suggestion.

Evidence: `/Users/venkateshmurugadas/Projects/scout-reference-research/pwc-query-2.txt`.

### Query 3: challenge the evidence boundary

Prompt: “Did any paper you actually read directly evaluate an Android personal-note assistant or establish SQLite FTS5 as better than hybrid retrieval for that use case? Separate direct evidence from extrapolation. Do not invent a benchmark.”

Observed completed response: it said no such direct evaluation or comparison existed in the papers it had read. It separated direct research findings from extrapolation. It also acknowledged that it had not successfully retrieved the full text of Lost in the Middle and should not have represented that paper as read. This is the assistant's admission, not an independent audit of the paper claims.

Expanded tool inspection added a concrete UI warning: `pwc_read_paper paper=18067` showed a success label but its expanded visible result was empty. Another read (`paper=3613`) contained paginated Markdown plus an explicit truncation notice. Transport/tool completion, returned content, coverage and claim support must therefore be separate states in Scout. A green tool label alone is insufficient.

The improvement is not to make every answer longer. It is to keep short answers useful while recording enough provenance that limitations surface before the person has to challenge them.

Evidence: `/Users/venkateshmurugadas/Projects/scout-reference-research/pwc-query-3.txt` and `pwc-query-3.jpg`. All three queries completed in the same private research chat. No general performance or quality rate can be inferred from this small sample.

![Papers with Code explicitly separates evidence from extrapolation](/Users/venkateshmurugadas/Projects/scout-reference-research/pwc-query-3.jpg)

## Final synthesis

Adopt DeepSeek's quiet hierarchy and source exploration. Adopt Papers with Code's event/state/evidence boundaries and answer discipline. Improve on both references by distinguishing successful execution from successful evidence retrieval. Preserve Sparkbox's existing local architecture and hybrid search, then fix the gaps that currently make execution hard to trust.

Follow-up: [ChatGPT mobile UI and visual craft study](2026-11-16-chatgpt-mobile-ui-study.md) adds a captured ten-step interface audit at 390 px, a 320 px layout check, computed typography and concrete native Scout tokens. The owner chose a minimal idle composer: text, +, Send/Stop; only active Research/source chips remain visible. The main design spec now incorporates that preference, an expanded editor, structured table/code presentation and a scoped reading type scale.
