# Sparkbox Agent: design

Status: draft v2. Replaces the single-shot tool loop in `Ask.java`.

## Decision

Port the [Pi harness](https://github.com/earendil-works/pi) design to plain Java. No new dependencies, no
Kotlin runtime. No clean JVM port exists (surveyed 2026-11-13: pi2kotlin, pi-agent-kotlin, jpi, pi-droid:
Kotlin+OkHttp, stalled, or not Android). They are references, not dependencies; pi2kotlin and
pi-agent-kotlin are MIT, so logic may be ported from them with attribution.

## Staying in sync with Pi

- **Pinned upstream:** `earendil-works/pi` @ `3e23dd9f53be214006041e1279f2dd57008f61c0` (2026-11-13).
- Every ported class starts with `// Port of pi packages/<path> @ <short sha>`.
- Port upstream tests too (`packages/agent/test/agent-loop.test.ts`, `agent.test.ts`, compaction and
  truncate tests) as Java self-checks in `tests/` using a fake model (Pi's `ai/src/providers/faux.ts`).
- Sync: `git diff <pinned>..origin/main -- packages/agent packages/ai/src packages/coding-agent/src/core`
  on each Pi release; port what matters; bump the pin.
- Every Pi feature appears in the matrix below with a status. Anything not listed is a gap in this doc.

## Parity matrix

Status: **M#** = ported in that milestone, **adapt** = ported with a stated change, **skip** = deliberately not ported (reason).

### Agent loop (`agent/src/agent-loop.ts`, `agent.ts`, `types.ts`) -> `Agent`

| Pi feature | Status | Note |
|---|---|---|
| Outer loop (follow-ups) + inner loop (tool calls, steering) | M1 | |
| Message types: system, user, assistant, toolResult | M1 | |
| `AgentMessage` custom messages + `transformContext` / `convertToLlm` on every request (must not throw) | M1 | UI-only messages (confirm cards, notices) live in the log, filtered before each call |
| Events: agent/turn/message/tool_execution start/update/end, message_update deltas | M1 | Java listener interface |
| `agent_settled` vs `agent_end{willRetry}` | M3 | UI busy state follows settled |
| Stream contract: never throw, failure = final assistant message with stopReason error/aborted | M1 | wraps `Model.stream` |
| Stop reasons: pending, stop, length, toolUse, error, aborted | M1 | |
| Partial assistant message replaced in place while streaming | M1 | |
| Tool preflight (find, prepareArguments, validate+coerce, beforeToolCall) sequential, execute parallel | M1 | |
| Results appended in call order, `tool_execution_end` in finish order | M1 | |
| Per-tool `executionMode: sequential` | M1 | |
| Unknown tool / bad args -> error result for model to self-correct | M1 | |
| `stopReason: length` with tool calls: fail all calls, never run truncated args | M1 | |
| `afterToolCall` patch; `terminate` (stop only if all in batch set it) | M1 | |
| Abort: one controller per run, reaches stream + tools + hooks; aborted calls get "Operation aborted" | M3 | reuses `Cancel` |
| Steering queue (after tools, before next call) and follow-up queue (when would stop) | M3 | |
| Queue modes one-at-a-time / all | M3 | |
| `prompt` while busy rejected; dispositions started/queued/handled | M3 | |
| `clear_queue` returns queued text; abort restores it to input | M3 | |
| Hooks: `prepareRequest`, `prepareNextTurn` (compaction runs here), `finishTurn`, `getApiKey` | M1/M2 | Java interfaces; no dynamic loading |
| Tool loadout deltas recorded in transcript | M5 | only matters once tools can change mid-session |
| Per-path file mutation queue | adapt M4 | reuse `VaultLock` |
| Nested tool calls / `replay` | skip | no use yet |

### Tool contract and validation (`agent/types.ts`, `ai/utils/validation.ts`, `schema`)

| Pi feature | Status | Note |
|---|---|---|
| `AgentTool{name,label,description,parameters,prepareArguments,execute(id,params,signal,onUpdate)}` | M1 | |
| Result `{content, details, structuredContent, isError, terminate}`; `details` never sent to model | M1 | details feed the UI cards |
| JSON-Schema validation with coercion, errors as dotted paths (`edits.0.oldText`) | M1 | small hand-written validator over `Schemas`; no library |
| `ToolCall.argumentsError` for invalid model JSON; salvage partial JSON | M1 | `JsonFix` already exists |
| `annotations` (readOnlyHint, destructiveHint) | M4 | drives the confirm gate |
| MCP client and MCP tools as `AgentTool`s | M6 | see "MCP" below |
| Tool exposure `deferred` + `tool_search` | M6 | only when connected MCP servers expose many tools |
| Tool exposure `model-only`/`hidden`/`codemode` | skip | codemode is a Pi opt-in layer (QuickJS); not needed |

### Truncation (`coding-agent/core/tools/truncate.ts`) -> `Truncate`

| Pi feature | Status |
|---|---|
| 2000 lines / 50KB dual cap, never mid-line | M1 |
| Head truncation (read, search) and tail (logs) | M1 |
| Footer "[Showing lines X-Y of N ...]" with how to get the rest; tool descriptions state the limit | M1 |
| Full output spilled to temp file, readable with offset/limit | adapt M1: spill to app cache dir; `read` can page it |

### Session log (`session-manager.ts`, `docs/session-format.md`) -> `Session`

| Pi feature | Status | Note |
|---|---|---|
| Append-only JSONL, header line with version, id, timestamp | M2 | |
| Entries with `id` (8 hex) / `parentId`; leaf pointer = active branch; context rebuilt by walking leaf to root | M2 | linear in practice, tree-capable |
| Entry types: message, model_change, thinking_level_change, usage, compaction, custom, custom_message, label, session_info | M2 | |
| `context_edit` (omit/replace one entry in future context only) | M2 | hides a noisy tool result without rewriting history |
| `branch_summary`, fork, clone, `/tree` | skip v1 | `parentId` kept so it can be added |
| Version migrations on load | M2 | version field from day one |
| Session list, new, switch, name, stats | M3 | |
| Export JSONL | M3 | HTML export and gist share: skip |
| Per-conversation file survives process kill; resume | M3 | durable runner; echoes `pi-durable` idea |

### Compaction (`core/compaction/*`, `docs/compaction.md`) -> `Compact`

| Pi feature | Status |
|---|---|
| Trigger: contextTokens > window - reserve (16384); checked before prompt and between turns | M2 |
| Token estimate: last real usage + chars/4 for trailing messages | M2 |
| Cut point: keep ~20k recent, cut at user boundary, never between tool call and result | M2 |
| Split-turn handling (two summaries merged) | M2 |
| Summary: fixed template (Goal, Constraints, Progress, Key Decisions, Next Steps, Critical Context), preserve exact paths | M2 |
| Iterative update prompt with `<previous-summary>` | M2 |
| Serialize history as labeled text for the summarizer, drop thinking, cap tool results at 2000 chars | M2 |
| Reject summary if stopReason length/error | M2 |
| Cumulative `<read-files>`/`<modified-files>` | adapt M2: note paths read/edited |
| Overflow detection (~25 provider regexes + silent heuristic) then compact + one retry | M2 | start with OpenRouter/GLM/OpenAI-style messages only |
| Manual compact with custom instructions; per-model reserve/keep overrides; compaction events | M3 |
| Summary calls with cache writes disabled; summary spend recorded as `usage` entries | M2 |
| `session_before_compact` hook | skip | no extension system |

### Context, prompt, skills (`system-prompt.ts`, `skills.ts`, `prompt-templates.ts`) -> `Prompt`

| Pi feature | Status | Note |
|---|---|---|
| Short base prompt built from named, tagged sections; later changes as patch messages (stable prefix) | M5 | |
| Context file (AGENTS.md style) | adapt M5 | `Knowledge/AGENTS.md` style note in the vault, injected as project context |
| Skills: name + description in prompt, full file read on demand; `/skill:name` forces | M5 | see "Skills" below |
| Prompt templates: `/command` with `$1`, `$@`, `${1:-default}` | M5 | notes under `Prompts/`, expanded in the Ask input |
| `SYSTEM.md` / `APPEND_SYSTEM.md` overrides | skip | one prompt, in code |
| Packages (npm/git), project trust gate, themes | skip | no user-installed code on a phone |

### Skills (`coding-agent/core/skills.ts`, `docs/skills.md`) -> `Skills`, M5

Port of the Agent Skills loader; the model sees only an index, full text is loaded on demand.

| Pi feature | Status | Note |
|---|---|---|
| Skill = directory with `SKILL.md`; frontmatter `name` (<=64, lowercase-hyphen), `description` (<=1024), `disable-model-invocation`, plus `license`/`compatibility`/`metadata`/`allowed-tools` | M5 | small frontmatter parser; unknown keys ignored |
| Name falls back to parent directory; invalid skill skipped with a warning; duplicate name: first wins | M5 | warnings shown in settings, not fatal |
| `formatSkillsForPrompt`: `<skills>` block of name + description + path; hidden when `disable-model-invocation` | M5 | |
| Model loads the full file with `read` on demand ("progressive disclosure") | M5 | needs `Skills/` readable by `read` |
| `/skill:name` forces loading; prompt templates `/command` | M5 | slash commands in the Ask input |
| Discovery order: global, project, parent dirs | adapt M5 | sources, first wins: (1) vault `Skills/`, (2) built-in skills shipped in app assets. No parent-dir walk |
| Skill scripts / `bash`-run helpers | skip | no shell; a skill is instructions plus the agent's tools (including MCP tools) |
| Skill packages from npm/git | skip | skills arrive by file sync into the vault, or by app update |
| `allowed-tools` | adapt M5 | advisory only; the tool gate stays authoritative |

### MCP client (`packages/mcp`, ~1.1k lines TS, own client, no SDK) -> `Mcp`, M6

Pi's MCP package is small and self-contained, so it ports directly. Android limits what is possible.

| Pi feature | Status | Note |
|---|---|---|
| JSON-RPC client: initialize handshake, protocol-version negotiation, request correlation, per-request timeout, cancellation, server requests | M6 | `client.ts`, `protocol/*` |
| Streamable HTTP transport (`url`, `headers`) | M6 | over `HttpURLConnection` + the existing SSE reader; headers carry bearer tokens |
| Legacy HTTP+SSE transport | skip unless a needed server requires it | |
| **stdio transport / pipe transport** | **skip** | Android cannot spawn arbitrary server processes. Only remote (HTTP) servers, or a server on the user's computer reached over the LAN/Tailscale |
| `mcpServers` config shape (shared with other MCP clients), `enabled`, `description`, `timeout` | M6 | stored in app settings; headers hold secrets, so same storage as the OpenRouter key |
| `listTools`, `callTool`; tools wrapped as `AgentTool` | M6 | |
| `toLlmContent`: text/image pass through, audio/resource-link/binary become placeholders; `structuredContent` becomes JSON | M6 | |
| Naming `mcp__<server>__<tool>`, <=64 chars `[A-Za-z0-9_-]`, hash suffix on collision (`naming.ts`) | M6 | |
| `normalizeMcpInputSchema` (object schema with `properties`) | M6 | |
| Output limit: 20KB, cut in the middle, full text saved and readable (`output-limit.ts`) | M6 | reuses `Truncate` spill |
| `isError` on result mapped to tool error | M6 | |
| Roots capability, progress forwarding | skip v1 | |
| OAuth for MCP servers (`oauth/*`, loopback redirect) | defer | v1 is static bearer headers. Later: Android Custom Tab + app-scheme redirect instead of a loopback server |
| Server `tools/list_changed`, resources, prompts | skip v1 | tools only |

MCP-specific safeguards (not in Pi, needed on a phone with personal data):
- A server is off until the user adds it. Nothing from the vault is sent to it except what the model puts in a tool call.
- Tools default to the confirm gate; a server's `readOnlyHint` can be honoured only for servers the user marked trusted.
- Tool results are untrusted text: shown to the model fenced, never allowed to widen permissions or trigger writes without the gate.
- Per-server enable switch and a visible "calling <server>.<tool>" card with the arguments.

### Models, thinking, usage (`ai/*`)

| Pi feature | Status | Note |
|---|---|---|
| One stream fn per wire API, normalized event stream | adapt M1 | one adapter: existing `Model.stream` (OpenRouter, openai-completions) behind a small `Provider` seam for tests |
| `Model` record: contextWindow, maxTokens, cost, reasoning, compat flags | M2 | deferred from M1 (ponytail): nothing reads it until compaction needs the window |
| Capability check for tool support | M1 done | Pi has none. A 400 on a request with tools is reported with "this model may not support tool calls"; no model table |
| Thinking levels with per-model map and clamping; level on each message; `thinking_level_change` entry | M5 | fixes the GLM thinking-eats-budget issue |
| Usage on every assistant message (in/out/cacheRead/cacheWrite/reasoning) and cost, plus separate non-message spend | M2 | feeds the existing `usage` store |
| Retry: classify retryable vs quota/billing; agent-level 3 retries, 2s base backoff, 60s cap; retry events | M1 | `Model.stream` has none today |
| `transform-messages` cross-provider handoff | skip | single provider; revisit if model switching mid-conversation is added |
| Prompt caching (retention, session id, cache_control) | M5 | pass through where OpenRouter supports it |
| Cache warming, WebSocket transport, 40 OAuth providers, catalog generator | skip | |

### Embedding API (`sdk`, `rpc-commands`) -> `Agent` facade

`prompt`, `steer`, `followUp`, `abort`, `waitForIdle`, `subscribe`, `dispose`, `getState`, `getMessages`, `compact`,
`setModel`, `setThinkingLevel`, queue-mode setters: **M3**. RPC/stdio, extension-UI sub-protocol, `bash`: **skip**
(the confirm gate plays the role of `ctx.ui.confirm`).

### Extensions

Pi's event/hook surface is ported as plain Java interfaces on `Agent` (`beforeToolCall`, `afterToolCall`,
`transformContext`, `prepareRequest`, `prepareNextTurn`, `finishTurn`) **M1**. No dynamic extension loading,
commands/shortcuts/renderers, or `sendMessage` injection: **skip** (no runtime code loading on Android).
MCP servers and skills are the supported extension points instead: they add tools and instructions without running
downloaded code in the app.

### Tests

Faux provider (scripted responses, errors, aborts, truncated tool calls), plus ports of Pi's loop, queue,
truncation and compaction scenarios: **M1-M3**. Classes go in the `pure` list in `build.gradle.kts`.

## Tools (Pi's filesystem set, over the vault)

`read`, `grep`, `find`, `ls`, `edit`, `write`, plus one `search` (embeddings; grep cannot find meaning).

- Roots stay `Knowledge/` and `Sources/` (plus `Skills/`, `Prompts/` when M5 lands). Journals stay excluded.
- `grep`/`find`/`ls` run on the in-memory vault snapshot (SAF reads are slow). `read` hits the file.
- `edit` (exact text replacement) / `write` go through `VaultWrite` + `VaultLock` (backup, stale check, read-back)
  behind a confirm card. No delete, no bash.
- Semantics, arguments, limits and messages are ported from `packages/coding-agent/src/core/tools/*.ts` (M4, `VaultTools`).
  Not ported: fuzzy matching in `edit` (exact text only), images, `.gitignore`, dotfiles.
- Add a tool only when files cannot express the need (capture queue, cards).

## Divergences from Pi (deliberate)

- Writes need confirmation (Pi has no approvals; a phone holding personal data should).
- Tool-support capability check, and read-only background mode (no Pi equivalent).
- Single provider; sessions linear for now.
- Loop runs in a durable runner so Android killing the process resumes from the log.

## Milestones

M1 status: built on branch `feat/agent-design` (`Agent`, `Tool`, `Validate`, `Truncate`, `tests/AgentTest.java`, `Model.provider`); not wired into the Ask screen yet.
Wiring status: the Ask tab is now **Scout** (`Scout.java` glue, `AskScreen` runs `Agent` with `VaultTools` + `inbox`, one `Session` file per conversation in `files/sessions`, confirm dialog before edit/write, messages sent while it runs steer it, Retry/Ask again use `resume()`). The old `askHistory` is not migrated. Not built: the foreground-service runner (a run dies with the app; the log lets Retry continue), a session picker, thinking levels. Not run on a phone yet.
M4 status: built (`VaultTools`: `read/grep/find/ls/search/edit/write` over a `Files` seam, `Confirm` gate before every change, writes only to `.md` under the given roots, `tests/VaultToolsTest.java`). Not built here: the Android `Files` adapter (snapshot paths, `VaultWrite`, embeddings `search`) and the confirm card, which belong with wiring the Ask screen; inbox is not a vault path yet.
M3 status: built in plain Java (`Agent` queues/abort/settle/resume, `Compact.now`, `Session` list/name/stats/export, model memory). Not built here: the Android foreground-service runner (belongs with wiring the Ask screen). Dropped from Pi: prompt dispositions (`running()` tells the UI whether to prompt or steer). Added: the last allowed turn has no tools and asks for the answer (as Ask does today).
M2 status: built (`Session`, `Compact`, `OpenRouter` stream extracted from `Model`, overflow retry, `context_edit`, usage totals). Live check: `scripts/live-agent.sh` (needs `OPENROUTER_API_KEY` in `.env`). Context-window table still deferred: `Compact` takes the window as a constructor argument until the Ask screen is wired.

1. **Core:** `Agent` loop, tool contract + validation, `Truncate`, stop-reason/error contract, retry, `Provider` seam,
   model table + capability check, hook interfaces, faux provider + ported loop tests.
2. **Memory:** `Session` JSONL, usage/cost, `Compact`, overflow detection, `context_edit`.
3. **Control:** steering/follow-up queues, abort, dispositions, `agent_settled`, facade, session list/switch/export,
   resume after process death, manual compact.
4. **Vault tools:** `read/grep/find/ls/search`, then `edit/write` with the confirm gate and annotations.
5. **Prompt layer + skills:** named sections, vault context file, `Skills` loader (vault `Skills/` + built-ins),
   `<skills>` index, `/skill:name`, prompt templates, thinking levels, caching.
6. **MCP:** `Mcp` client over Streamable HTTP, server settings screen, tool wrapping/naming/limits,
   `tool_search` if tool count grows, confirm-gate integration, ported `packages/mcp/test` scenarios
   (against Pi's in-memory transport idea: a fake server).
7. **Research mode:** larger step budget, scratchpad entry, citations checked against tool results.
8. **Background:** headless read + queue mode via `BackgroundJob`/`RunGate`.

## Decided

- MCP servers are remote Streamable HTTP only. No stdio, no bridge. (2026-11-13)

## Open

- Migrate the 20-message `askHistory` into the new log, or start fresh? (default: start fresh)
- Replace the Ask screen, or ship behind a toggle? (default: replace, same screen)
