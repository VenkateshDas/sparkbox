# Instagram reel processing

Investigated on a connected OnePlus phone on November 15, 2026, from main at `b91f69f`.

## Failure and cause

All five pending reels had recorded `The model's answer had no topics`. Four had reached four attempts, one had reached two. An earlier error on one capture was `Not a public web address`.

The original fetch path had special handling for YouTube but treated Instagram as an ordinary article. The phone reproduction returned title `Instagram`, zero content characters and `Very little readable text`. Ingest still asked the model for topics from that empty input, so changing the text model could not recover the reel.

Instagram's public `/p/<shortcode>/embed/` endpoint contains a JSON string with `gql_data.shortcode_media`, including the caption, owner and signed MP4 URL. During testing, the full mobile and desktop browser identities returned login HTML; a minimal `Mozilla/5.0` identity returned the media data. This is a public endpoint, not an authenticated API contract, and Instagram can change it.

## Model choice

[OpenRouter's GLM 5.3 Flash page](https://openrouter.ai/z-ai/glm-5.3-flash) and live model metadata list text, image and video inputs. A real base64 MP4 request succeeded with `z-ai/glm-5.3-flash`, served by Together. Its scene description was checked against a frame of the actual reel.

[OpenRouter's video documentation](https://openrouter.ai/docs/guides/overview/multimodal/videos) specifies `video_url` parts in Chat Completions. Base64 data URLs avoid depending on a provider's ability to fetch an Instagram CDN URL before it expires. GLM's advertised modalities do not include audio, so the existing Whisper transcription path reads the audio track separately. The transcript accompanies the video request; the prompt explicitly forbids inventing speech from frames.

Live testing also exposed a separate billing gate: `This request requires at least $1.00 in balance for video`. The key was valid. After the owner adjusted the account/key allowance, requests succeeded. The app now preserves OpenRouter's actual 402 reason instead of replacing every 402 with `out of credit`.

## Implementation

`Instagram` parses the embed JSON and requires a matching shortcode. `Fetch` downloads the actual video with a 24 MB cap and public-host checks on redirects. `Model.watch` checks for an audio track, transcribes it when present, and sends the full video plus transcript to GLM. Ingest caches that text before asking for the knowledge plan. A failed watch cannot silently become a caption-only Source note.

Video shares use the existing file queue and opt-in attachment description stage. WebM video no longer gets mistaken for a voice note. Silent videos skip transcription. Unsupported, private, blocked and oversized videos keep explicit errors rather than fabricated content.

## Verification

### Follow-up: reels available only with account access

Three later captures exposed a separate case. Two processed successfully; `DfvBoXwovJx` remained. Its public embed returned `contextJSON: null` and Instagram's broken/removed-post message, although the owner could play it while signed in. Multiple embed variants and anonymous Chrome requests returned no media. The current metadata query returned a valid video for a control reel, but an empty/error response for this reel. It was not a video-model failure.

The extractor now supports the `xdt_api__v1__media__shortcode__web_info.items` format used by the current [Instaloader implementation](https://github.com/instaloader/instaloader/blob/master/instaloader/structures.py). A fallback queries Instagram directly when embed data is missing. [Meta documents that private, age-restricted and embed-disabled accounts are unsupported by public embeds](https://developers.facebook.com/documentation/instagram-platform/oembed).

The Instagram access screen uses Instagram's own HTTPS login inside an Android WebView. Its session stays in the app's private WebView cookie store. Metadata requests attach cookies only to a fixed Instagram HTTPS origin, never follow redirects with them and never send them to the model service. There is no JavaScript bridge or file access. The owner can sign out of this session independently of the Instagram app.

Access failures have a separate non-retrying status and an Instagram access action in capture detail. They do not exhaust repeated model calls or falsely imply the post was deleted. Offline failures retain the existing automatic network retry behavior.

After the owner signed in and tapped Retry, the same capture completed through the real pipeline. Its new Source note, `Claude Code skill for animated explainer videos`, contains the caption, video content and transcript. Comparison against the follow-up backup found one new file, eight changed and none deleted, with an empty inbox. Tests also cover the actual current API response, access-error rejection and wrong-shortcode rejection.

`./gradlew check assembleRelease assembleDebug` passes. The JVM checks cover URL validation, nested embed JSON, Unicode captions, wrong-post rejection and source/billing error classification. The live check passed on a real reel with caption, video description and transcript, and on a synthetic silent MP4 with the correct visual summary.

Recovery completed all five pending reels. A before/after vault comparison found five new Source notes, each containing Caption, Video content and Transcript sections; 24 files were created, 13 changed and none deleted. The Instagram inbox was empty afterward. The original vault backup remains under the ignored `build/reel-backup/` directory.

The manual device runner uses the phone's existing key without exporting it:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r build/gradle/outputs/apk/debug/sparkbox-debug.apk
adb install -r build/gradle/outputs/apk/androidTest/debug/sparkbox-debug-androidTest.apk
adb shell am instrument -w -e watch true -e url 'https://www.instagram.com/reel/SHORTCODE/' me.venkatesh.saveobsidian.test/me.venkatesh.saveobsidian.ReelTest
```

This live check incurs model usage but does not write the vault. The optional `-e recover true` mode invokes the app's existing Retry failed captures action and waits for Instagram captures to finish; it writes real notes. Back up the vault before using it.
