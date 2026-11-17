# Capture processing: investigation and repair plan

Date: 2026-11-17. Baseline: `main`, `5b2c87e` (`fix(scout): smooth runtime and chat UI`). `git pull --ff-only origin main` reported already up to date. This investigation adds this report and a diagnostic script; it does not change application behavior.

## Findings

There are multiple defects, not one unreliable AI connection. Three failures are reproduced against production classes. Other findings below are source-level observations or device hypotheses, explicitly distinguished. The screenshots alone cannot identify whether the phone lost connectivity, Android stopped a job, or a provider failed.

### 1. DNS failure becomes an invalid URL error — reproduced

`PageTitle.java:51–58` resolves the host, checks its addresses, and returns false both for a private address and for `UnknownHostException`. `Web.java:83` turns either false into `IOException("Not a public web address")`.

The probe calls the real `Web.get` with a reserved `.invalid` hostname. Output:

```text
Actual: Not a public web address; offline=false
AssertionError: DNS failure must stay retryable as a network failure
```

This proves the error conversion, including loss of the exception type. It does not prove the exact DNS failure on the phone. A temporary DNS outage takes this same branch, becomes an ordinary capture failure, and consumes one of six tries (`Ingest.java:88–102`). `Web.get` already has a DNS catch, but the earlier conversion prevents that catch from seeing this failure.

Fix: separate address validation from name resolution. Reject actual non-public addresses; preserve resolution exceptions and their host context. Do not remove the private-address checks. A source host failing DNS does not establish that the whole phone, or OpenRouter, is offline. Retry transient resolution trouble without exhausting content attempts; distinguish a persistently missing domain in diagnostics.

### 2. A scheduled job can finish while processing remains on an unowned thread — reproduced with modeled Android callbacks

`CaptureService.java:325` calls `BackgroundJob.now`. That method both schedules a job and starts a plain thread. The thread and `onStartJob` both call `Tagger.run`. `RunGate.enter` returns null to the second caller and sets `again`; `Tagger.run` returns immediately. `BackgroundJob.onStartJob` then calls `jobFinished`, even though the first thread still owns processing.

The probe compiles the actual `BackgroundJob` and uses the actual `RunGate` and `Cancel`. Small platform stubs record `jobFinished`; a latch holds the competing worker. Output:

```text
jobFinished=true; raw worker alive=true
AssertionError: Scheduled job finished while processing still runs outside job ownership
```

This proves premature job completion in the overlap case. Stubs do not reproduce Android process management or establish its frequency on this phone. The inferred consequence is that closing the activity leaves work without the intended active-job lifecycle protection.

Manual Retry and Retry all start plain threads directly (`Ingest.java:152–166`), bypassing even `BackgroundJob.now` and its explicit post-run retry scheduling. `onStopJob` halts the global gate instead of only the run owned by that callback (`BackgroundJob.java:50`). An old callback can therefore affect another owner. `Tagger.run` can recursively restart after halt when `again` is true (`Tagger.java:56`).

Fix: one execution owner. All capture, retry, settings and startup triggers request durable work. Only its active job executes processing. A busy callback must not claim that the active owner's work completed. Track callback/run identity; stop only its token; never restart a stopped job on a plain thread. Reconcile new arrivals before completion and preserve a durable successor wakeup. Do not simply reschedule the active job ID mid-run: Android stops that job.

### 3. Article images disappear before the AI request — reproduced

`Extract.Page` has one preview-image string. `Extract.parse` reads Open Graph/Twitter preview metadata, but its text renderer drops `<img>` contents. `Fetch.Result.image` is copied but `Ingest.pageText` saves only `r.text`. `Model.completeJson` receives a string, not image content.

A fixture contains readable article prose and an informative diagram. Output:

```text
Extracted text chars=628; preview image=; diagram retained=false
AssertionError: Article diagram discarded before model input
```

Even a populated preview image is not sent by the ingest call. Seeing a thumbnail in Feed does not mean the model saw it.

### 4. Instagram photos and carousels lack visual processing — confirmed in source

`Instagram.Media` retains only a single image and a video. `Instagram.media` does not enumerate `carousel_media` or `edge_sidecar_to_children`. `Fetch.instagram` processes the caption; only its video branch calls a visual model. Photo captions can therefore be filed as processed while their images were never read. An image-only post may fail as having no readable content.

The Instagram cache condition in `Ingest.java:488` requires `### Video content` for every Instagram URL, including photo posts. Successful photo extraction therefore gets fetched again on later attempts. This needs an explicit media-completeness marker, not a video heading as a universal sentinel.

### 5. Recovery has gaps — confirmed in source

Neither initial nor delayed processing jobs set `setPersisted(true)` (`BackgroundJob.java:18,36`). Existing boot receivers restore reminders or Scout; they do not restore capture processing. Queued data remains local, but processing can wait for the next app open after reboot.

The worker runs transcription, up to 40 tagging calls and up to 10 page backfills before ingest (`Tagger.java:31`). Ingest's eight-minute budget starts only inside ingest, not at job start, and is checked between captures. Network retry and model-format fallback loops can overrun a slice. This is a potential starvation/time-budget problem; it requires phone timing evidence to quantify.

Scheduling return values are ignored. An inbox read failure inside `retryDelay` returns no retry. These can make recovery look successful while no future work is registered.

### 6. UI and failure categories obscure recovery — confirmed in source

`Ingest.stage` trusts a persisted timestamp for six minutes without checking a live owner. Starting another attempt does not clear the previous error. Feed can therefore show both Processing now and an old offline error, as in the screenshot. A killed process can also leave a fresh-looking stage behind.

`Net.offline` identifies transport exceptions, but `Failures.recovery` labels every offline case as a connection to the AI service, including failures fetching an article. Cancellation is not a distinct ingest outcome. Provider HTTP 429 eventually falls into the capture retry ladder, while timeouts and some transport faults consume ordinary attempts. Preserve boundary, status, host and cause; cancellation must not penalize the capture.

For ordinary pages, `Ingest.pageText` ignores `Fetch.Result.problem` and `thin`. A sign-in wall, PDF URL or unreadable page can proceed to a title-only plan. An inaccessible source should remain visibly incomplete rather than imply its content was understood.

## Platform research and design choice

A useful promise is: capture remains durable; processing resumes with the UI closed whenever Android grants execution and network access; interruptions resume checkpoints without losing the capture. Immediate, uninterrupted phone-only execution in every state is impossible.

Android explicitly defers ordinary jobs and network access in Doze. Force stop keeps the package stopped until user interaction. WorkManager uses JobScheduler and is subject to those limits too. A server can continue processing an already uploaded item independently of the phone; it cannot receive a capture that the phone never uploaded or write to a disconnected local vault. That would be a separate architecture decision, not this bug fix. [Doze documentation](https://developer.android.com/training/monitoring-device-state/doze-standby), [stopped-state behavior](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state).

Keep native JobScheduler for the first repair. Existing queue, plan and write checkpoints already solve much of persistence. WorkManager is a reasonable standard alternative, but replacing scheduling alone would not fix DNS classification, media extraction, stale UI, or unowned Retry threads. Avoid a migration until the repaired lifecycle has been qualified. Android documents persisted jobs, stopping an active job when its ID is replaced, and expedited jobs with quota limits. Use expedited scheduling for fresh user captures on API 31+ with ordinary-job fallback if scheduling fails; delayed retries remain ordinary persisted jobs. [JobInfo.Builder reference](https://developer.android.com/reference/kotlin/android/app/job/JobInfo.Builder).

Use the job's assigned network for connections and resolution on API 28+, rather than silently relying on the process default route; retain an API 26–27 fallback. Review all fetch/model paths together. Do not bind the entire process to a network and disrupt Scout. This is a correctness requirement from Android's API documentation, not proof of the screenshot's cause. [JobParameters.getNetwork](https://developer.android.com/reference/android/app/job/JobParameters#getNetwork()).

An always-running foreground service is not the minimal fix. Android 15 limits background dataSync service time; Android 16 also applies job quota concerns to long-running WorkManager workers. Consider a visible, user-started processing service only if measured job slices cannot finish important work. [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

## Minimal repair sequence

1. **Repair lifecycle ownership and recovery.** Remove direct Tagger threads from `now`, Retry and Retry all. Persist initial and retry jobs. Check scheduling results. Use one owner token per admitted run, stop promptly at safe boundaries, and durably schedule pending work before relinquishing ownership. Recover pending capture-append work as well as inbox ingest. Preserve uncertain-write recovery instead of replaying an unverified append.
2. **Repair errors and progress.** Preserve DNS exceptions without weakening public-host checks. Separate cancellation, source access, provider authentication, throttling, timeout and connectivity. Show Waiting for network, Waiting for Android, Needs sign-in or Processing only when true. Clear old errors on a new attempt; invalidate stages when their process/run no longer exists. Keep transient infrastructure failures from exhausting content retries; use bounded backoff rather than a hot retry loop.
3. **Bound the work.** Give the whole run a monotonic time budget, prioritize fresh capture processing over backfill/embeddings, and checkpoint between safe units. Propagate stop tokens through fetch, provider retry and child threads. Retain valid paid plans for write-conflict retries; the current manual `forget` removes plans even when only a write failed. Verify idempotent append/write behavior under process death; SharedPreferences `apply` is asynchronous, so a checkpoint call alone is not proof of crash durability.
4. **Add visual evidence once, before planning.** Extend the existing fetch result and content cache with ordered media references and extraction status. Collect article figures and lazy-loaded images from the selected article, with alt text/captions; use preview metadata as fallback. Read Instagram image candidates and every carousel child in order, including mixed video/photo posts. Reuse bounded download/resize and the existing vision transport. Batch media under byte/pixel/token budgets and checkpoint remaining batches instead of silently ignoring later slides. Supply grounded visual extraction plus article/caption text to the existing plan call. Do not use the existing 300-token thumbnail/tag description as the entire document interpretation.
5. **Version evidence and plans.** Existing text-only caches and plans must not silently skip the visual stage. Record text, media identity, inspected/skipped/failed status, extraction version and evidence hash alongside Why/Ideas. Reuse completed visual work across retries; rebuild an uncommitted plan when its evidence changes. Do not rewrite already-filed notes automatically as part of upgrading the app.

Image extraction should report what is visible, transcribe legible labels, identify chart structure and mark uncertainty. Keep source text separate from visual interpretation. Treat source text and image instructions as untrusted content. Download private Instagram media on the phone and send image bytes; never send Instagram session cookies to OpenRouter. OpenRouter documents image URLs and base64 inputs, multiple images and provider-specific limits. Its current guide uses the Responses API; the app uses chat/completions, so qualify the existing transport/model with a small real image request before committing to payload changes. A metadata thumbnail is insufficient evidence of model vision support. [OpenRouter image inputs](https://openrouter.ai/docs/guides/overview/multimodal/image-understanding).

Dynamic LinkedIn posts, access-controlled articles and private Instagram content remain access problems. Support available authenticated content or shared media, and report incomplete evidence honestly. Never claim a login wall or a caption is the complete post.

## Validation and remaining evidence

`./gradlew captureTest --console=plain` passed: Capture 332, Agent 79, Session usage 32, Vault tools 62, MCP 24, Scout 94, Retrieval 38. Existing JVM checks do not exercise Android job lifecycles.

Run the deliberately red diagnostic with:

```bash
./gradlew captureTest
python3 scripts/probe-processing.py
```

The probe compiles production classes into a temporary directory. It uses no vault or API key. It currently exits 1 with three failures. The DNS probe requires local resolver failure for the reserved `.invalid` domain; a resolver that rewrites nonexistent domains needs a controlled resolver seam instead. The background probe models callbacks, not an Android service process. Turn each probe into a normal regression check at the appropriate seam during implementation.

No ADB device was connected. Device reproduction, provider requests, OEM battery settings, Doze behavior, reboot recovery, SAF writes and real Instagram access remain unverified. No credentials were read or paid model calls made.

| Device case | Required result |
|---|---|
| Share, press Home, then swipe app from Recents | Capture eventually files with no app reopen and no lost queue item. Record OEM/OS behavior. |
| Kill the background process, without Force stop | Job resumes; completed evidence and valid plan are reused; no duplicate claims or inbox append. |
| Restart phone with pending work | Persisted work resumes after unlock/network access without reopening Sparkbox. |
| Offline capture, DNS outage, Wi-Fi/mobile handoff | Honest waiting state; no invalid-URL message from DNS; automatic recovery. |
| Doze, battery saver and exhausted expedited quota | Persisted queue survives; ordinary-job fallback works; no false completion. |
| Force stop, then open/share again | No promised execution while stopped; pending work resumes after user interaction. |
| Stop callback during fetch/model/write, then a new capture | Old owner cannot stop new owner; no post-stop recursion or unsafe write replay. |
| More than eight captures; one slow reel; retries due | Successor work remains registered; one slow item does not starve new captures indefinitely. |
| 401/402, 429, 5xx, timeout, blocked source | Correct recovery action and boundary; transient faults do not exhaust content attempts. |
| Article diagram, image-only post, carousel, mixed reel/photo | Visual content reaches extraction; ordered coverage recorded; partial access clearly shown. |
| Sync conflict after paid plan | Plan reused; note and topic claims appear once; uncertain writes remain recoverable. |

Collect job ID, run ID, capture ID, stage duration, stop reason, network transport, error boundary and HTTP status. Exclude keys, cookies, full private URLs and source bodies from logs. Phone logs are needed to determine which of these verified defects explains the reported frequency.
