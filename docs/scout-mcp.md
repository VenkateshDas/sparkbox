# Scout MCP connection checks

Investigated 2026-11-17. A saved, enabled server is not proof that Scout can connect to it. The phone was not connected to this development machine, so its saved endpoint and credentials could not be tested directly.

## Findings

- **Vault + web** is the default for new chats. The source setting stays in the composer options menu. Selecting **Vault only** deliberately prevents MCP connections. Saved explicit per-chat choices are preserved.
- Connection errors were only transient status text, overwritten by “Thinking…” when the model started. They now remain visible in the chat.
- An allowlist containing the wrong remote tool names could silently expose zero tools. Scout now reports that condition.
- Credentials and endpoints could not be edited without recreating the server. Settings now supports editing, selecting the authentication header, and testing the saved connection.

TinyFish's [official MCP documentation](https://docs.tinyfish.ai/mcp-integration) documents `https://agent.tinyfish.ai/mcp`, bearer authentication for API-key clients, and the `search` and `fetch_content` tools. Its [official proxy implementation](https://github.com/tinyfish-io/tinyfish-mcp-server) sends API keys upstream using `X-API-Key`. Sparkbox now supports either static header. It does not implement browser OAuth, and another app's browser sign-in is not a Sparkbox credential.

A public initialize request to TinyFish without credentials returned HTTP 401 with an authentication challenge. This confirms that the endpoint was reachable and authentication was required; it does not establish that the owner's saved credential is invalid.

## Use

1. Open **Settings → Scout tools → TinyFish → Test connection**. The test performs initialization and live tool discovery. It does not invoke a web tool or automation.
2. If authentication fails, choose **Edit connection**, confirm the HTTPS MCP endpoint, select the appropriate header, and enter the API key. **X-API-Key** is supported for the TinyFish API-key path. Leaving the token blank preserves the existing encrypted credential. Save and test again.
3. Confirm that `search` and `fetch_content` appear. An empty allowed-tools setting exposes all discovered tools; `search, fetch_content` restricts Scout to those two.
4. New chats default to **Vault + web**. For an existing vault-only chat, change Sources in the composer options menu, then send a new request. Request scope and connection revisions stay fixed while a run is active.

Open **Scout → + → Approvals** to choose **Ask for approval** (default) or **Full access**. The selection is saved with the chat draft and applies to the next new request. It stays fixed during a run and its retries. Full access automatically applies requested note changes and calls enabled tools, then continues the answer without waiting for a tap. It grants no additional Android file access and does not enable disabled servers or bypass tool allowlists, vault protections, cancellation, budgets, revision checks, or dispatch records. Discovery remains read-only. Unknown external outcomes still require explicit review; they are never retried automatically. Manual Save/Keep proposals remain explicit.

In Ask for approval mode, exact remote names explicitly selected as verified read-only research tools can still run without a prompt. Connection testing does not change permissions. There is no automated “unsafe action” classifier; the two modes describe actual behavior.

## Validation

Offline HTTP fixtures cover initialization, JSON/SSE discovery, bearer and X-API-Key headers, empty allowlists, credential redaction in server errors, connection failure, and tool filtering. The UI-state check verifies that a connection failure survives transient status changes and completion. Discovery requests use a shorter timeout than tool execution. A real authenticated TinyFish search remains a phone verification step.

## Approval responsiveness

Approval previously held the same session monitor that the chat needs for history and usage reads while a remote call or vault operation ran. A slow approved fetch could therefore block the UI long enough to cause an Android application-not-responding failure. Approval effects now serialize on a separate per-session lock; journal reads remain available during remote and storage I/O. Existing durable decisions, dispatch fences, cancellation, and write verification remain in place.

The regression fixture blocks a fake remote call and a fake note read, then checks that history, journal, and usage can still be read before the I/O finishes. The old implementation fails this check. Waiting for approval no longer produces a false app-closed message. After the final approval resolves, the interrupted model turn resumes from its saved tool results; manual note proposals do not resume an unrelated conversation.


## Full access runtime repair

The first implementation still suspended after every proposed action. Later calls in that batch became “Deferred,” and note approval triggered a blocking vault scan followed by a rebuilt agent and MCP discovery. Full access now resolves approvals inside the original tool batch. It adopts the already-journaled tool result exactly once and proceeds to the next call. Manual approvals retain their existing pause/resume behavior.

Approved web calls reuse the run's connected MCP client rather than initialize a second session. Verified note changes update the run's in-memory graph immediately. Read caches are invalidated before the proposal can suspend. One background vault refresh runs after the agent settles; no full vault walk or agent rebuild sits between full-access actions. Cancellation, immutable run policy, note conflict/protected-field checks, durable dispatch fences, and explicit review of unknown remote outcomes remain in place.

The screenshot's exact “Incomplete tool arguments” path was reproduced with the real SSE decoder. The decoder rejected malformed tool JSON before the agent could recover; no tool had executed. It now reports that specific condition separately. The agent makes one immediate regeneration attempt, with a larger output allowance bounded by its output ceiling and the context allowance. It asks for complete JSON and shorter individual changes. If regeneration also fails, execution stops; partial arguments are never repaired into a write or dispatched. Other SSE protocol failures remain fatal. Output starts at up to 4096 tokens and may grow to at most 16384 within the context allowance. This is independent of the much larger input context window. OpenRouter documents both the output limit and hidden reasoning costs in its [parameter reference](https://github.com/OpenRouterTeam/docs/blob/main/api_reference/parameters.mdx) and completion boundaries in its [streaming reference](https://github.com/OpenRouterTeam/docs/blob/main/api_reference/streaming.mdx).

Long streamed arguments also repeatedly copied and UTF-8 encoded their growing prefix. They now use one buffer per call and validate the final byte length before releasing a result. A desktop JVM comparison of the real old/new decoders, using 80,014 argument characters across 2001 SSE chunks, produced identical output: median **12.89 ms before, 6.04 ms after** over seven measured alternating runs after warm-up. This measures decoder work only, not phone latency, provider generation, network, or vault I/O.

Regression fixtures cover a mixed note/web batch completing in one agent run and two model requests, no deferred siblings or duplicated tool results, citations from approved web output, real vault-tool cache freshness across read/edit/read/create/read, bounded malformed-argument regeneration, approval failure retention, cancellation, and unknown dispatch outcomes. Failed or paused work no longer remains labeled “Working…”. Run `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew captureTest askStateTest lintDebug assembleDebug --console=plain` to validate. No phone was connected, so actual device timing and the owner's provider response remain unverified.
