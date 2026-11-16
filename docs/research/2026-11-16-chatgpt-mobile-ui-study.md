# ChatGPT mobile web: interface and visual craft study

Date: 2026-11-16. Purpose: extend Scout's design with directly observed mobile interaction and typography patterns. This is a focused UX/design audit, not an audit of ChatGPT's entire product or research accuracy.

## Scope and evidence

Inspected authenticated ChatGPT web in the in-app browser at a measured **390 × 844 CSS-pixel viewport**, then checked a **320 × 740** narrow viewport. The inspected account/build showed Chat and Work modes and an Instant selection; features may differ by account, rollout, browser or date. This is responsive desktop-browser inspection, not a physical Android/iOS browser test.

Ran two public test prompts: one requesting a short sourced Android explanation with a comparison table and Java code; another requesting a numbered UI requirements list to inspect generation controls. A small synthetic text note was uploaded only to inspect its draft attachment UI, then removed from the draft. No personal notes were uploaded, no existing chats were opened, and no share link was created. Drawer evidence is cropped to navigation to exclude unrelated project/chat titles.

The first generated answer's WorkManager recommendation is test content, not an architecture decision for Sparkbox. Retain Sparkbox's existing JobScheduler design unless implementation evidence justifies changing it. The second generated requirements list is also test content, not research evidence.

Screenshots were captured during this run, saved and visually inspected. A blank loading capture of the Sources panel was rejected and replaced with the loaded state. Evidence folder: `/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/`. Exact computed style readings are in `typography-metrics.json`. Measurements are browser CSS values, not Android sp/dp specifications or proof of the exact resolved font face.

## Flow and findings

### 1. Entry, tools and model selection — clear hierarchy; long menu needs restraint

The start screen keeps the composer near the bottom and leaves the conversation canvas quiet. The + control opens an anchored menu with icons, action names and short explanations. File upload, web search and deep research are discoverable without occupying the idle toolbar. Model/effort choice has a separate compact popover.

For Scout: use one + menu with Add notes, Add quote/capture, Research and Sources. Keep model and tool configuration in a secondary section. Do not reproduce ChatGPT's long plugin list. The owner confirmed: **idle composer shows text, +, Send/Stop; Research and source chips appear only when active**.

Accessibility consideration: the measured composer button boxes were 36 × 36 CSS px. This is a reference observation, not the native target to adopt; Scout should retain at least 48 dp interactive areas. Icon-only controls need labels even if their appearance is minimal.

![Step 1: tools behind the + control](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/02-tools.jpg)

### 2. Long draft — strong full-height editor pattern

A long draft first scrolls within the compact composer; an Expand control then gives it most of the screen. The same text and lower action rail remain. This is more useful than forcing a large research brief through a four-line field. The normal conversation does not need to disappear just because the person types a little more.

For Scout: add an expanded editor backed by the same durable session draft. Collapse and Android Back preserve text, attachments and caret. Limit ordinary composer growth so an answer remains visible. Real keyboard/IME behavior was not exercised here.

![Step 2: expanded composer](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/05-expanded-composer.jpg)

### 3. Answer prose and table — readable, contained, restrained

Assistant text flows directly on the background. A heading hierarchy, semibold emphasis and generous line height do most of the work. The two-column table keeps its rows and header instead of becoming a sequence of labels. At 390 px the reading column is 358 px; at 320 px the table is 288 px and the document's scroll width remains 320 px. This test did not establish behavior for an arbitrary wide table.

For Scout: keep open-canvas prose, real table structure and source markers near claims. Do not add a card border around every paragraph. A table gets local horizontal scrolling only when it cannot fit. Retain selectable text.

![Step 3: answer hierarchy and comparison table](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/07-answer-table.jpg)

### 4. Code — separate block with useful local controls

Fenced code has a language label, Copy and a Wrap toggle. Wrapping changes the code block without widening the whole page. The captured wrapped example breaks a long identifier, which can hurt readability; the control should remain optional. The block also uses its own bounded scrolling region, so multiple scroll areas deserve device testing.

For Scout: a native code block with language, Copy and Wrap is justified. Preserve exact copied whitespace; do not copy soft display wraps. Avoid shrinking code to unreadable text to make it fit.

![Step 4: code block with local controls](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/09-code.jpg)

### 5. Inline citation — fast preview, limited space

Tapping a small source chip opens a compact popover with source titles and publisher names. Several sources can be grouped behind a +count. This keeps references out of the prose flow while preserving access. At phone width the preview covers part of the composer, so it should be short and easy to close.

For Scout: tap a reference for one relevant excerpt; provide Open and All sources. Keep the previous full-height source-list design for multi-source inspection. The citation must expose an accessible name and evidence coverage, not just a tiny superscript or ambiguous site icon.

![Step 5: inline source preview](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/08-citation-preview.jpg)

### 6. All sources — good dedicated reading surface; weak snippets and duplication

View sources opens a nearly full-height overlay with its own scrolling and an explicit close control. It separates cited Sources from More. This avoids squeezing a desktop side panel into a narrow column.

However, several visible snippets contain website navigation boilerplate instead of claim evidence. The list includes apparent duplicates distinguished by language, account-selector or tracking query parameters. That does not prove identical underlying content, but it is a clear deduplication opportunity for this provider.

For Scout: show a supporting excerpt, not navigation text. Separate cited evidence, other retrieved material and unavailable sources. Use provider-aware canonicalization; do not strip arbitrary meaningful query parameters globally. Closing must restore the answer anchor and focus to the invoking reference/action. Complete screen-reader focus restoration was not tested here.

![Step 6: dedicated source list](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/13-all-sources.jpg)

### 7. Answer and conversation menus — useful separation of scope

The answer's action row exposes Copy and a small number of common actions. More contains Regenerate, View sources and Branch. Separately, the conversation title opens Rename, Pin, View files, Archive and Delete. The drawer holds navigation and history, with the current task remaining behind it.

For Scout: keep Copy, Keep and More below an answer; put Rename/Export/Delete on the conversation. Do not place destructive chat actions among answer actions or clutter every response with all possible tools. Full branch UI, cloud sharing and a plugin ecosystem remain out of scope.

![Step 7: answer-scoped menu](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/12-answer-menu.jpg)

### 8. Context attachment — readiness is visible; tile is too tall for notes

Uploading the synthetic text file first shows an uploading indicator; after completion its filename becomes an openable tile. It has a Remove action. A file-only draft can enable Send. The square tile consumes substantial vertical space on a phone.

For Scout: reuse the readiness/removal behavior, but prefer a compact note row or chip with a file-type icon and title. Quoted text gets a short excerpt with an expand action; images can justify thumbnails. Distinguish attached, loading, unavailable and too-large states. A failed context item must not disappear silently.

![Step 8: synthetic text attachment](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/14-attachment.jpg)

### 9. Editing a prompt — clear cancel path

Edit message replaces the user prompt with a local editor and Cancel/Send controls. The answer remains below it. Cancel returned to the original transcript without sending another request.

For Scout: an explicit Edit and retry action is useful, but preserve the original attempt and all verified write receipts. Editing an old prompt must never replay its historical mutations. Full branch navigation is not required to make retry history honest.

![Step 9: editing an existing prompt](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/19-edit-message.jpg)

### 10. Typing during generation and narrow layout — Stop remains available

During the second response, typing a new draft left Stop visible. This is directly useful to Scout, whose current control switches based on whether input is blank. A Scroll to bottom button appears while away from latest content. In the captured test the reader remained above the new answer instead of being pulled to the bottom. Full touch/streaming scroll behavior still needs a real-device test.

For Scout: preserve independent cancellation and draft entry, display queued steering acknowledgements, and respect reading position. At 320 dp the header may truncate a long title, but must retain navigation and cancellation.

![Step 10: draft text and Stop coexist](/Users/venkateshmurugadas/Projects/scout-reference-research/chatgpt-mobile/20-typing-while-running.jpg)

## Measured visual system

The measured computed font stack begins `-apple-system-body, ui-sans-serif, -apple-system, system-ui` and includes Segoe UI/Helvetica/Arial fallbacks. This does not establish the actual resolved face and is not evidence that an OpenAI proprietary font should be copied. Use Android's native system sans in Scout; a premium result does not require downloading a new font.

| Element | Measured ChatGPT CSS | Proposed Scout native token |
| --- | --- | --- |
| Answer paragraphs | 16 px, weight 400, 26 px line height | 16 sp / 26 sp, normal |
| Main answer heading (h2) | 18 px, weight 600, 28 px line height | 18 sp / 28 sp, semibold |
| Section heading (h3) | 16 px, weight 600, 26 px line height; 24 px top margin | 16 sp / 24 sp, semibold; 24 dp section gap |
| Strong emphasis | Weight 600 | Semibold; no artificial extra-heavy bold |
| Table header / body | 14 px / 20 px; weights 600 / 400 | 14 sp / 22 sp; same weight distinction |
| Composer text | 16 px / 26 px, normal spacing | 16 sp / 24 sp, font scale respected |
| Fenced code | 12 px / 20 px; system monospace stack | 13 sp / 20 sp, native monospace |
| Inline code | About 14.88 px; weight 500; small shaded background | 15 sp with body line height; 4 dp horizontal inset |
| Reading column | 16 px side gutters at both sampled widths | 16 dp side gutters; no nested answer-card inset |
| Paragraph spacing | Ordinary paragraph example: 16 px top margin | 12–16 dp according to block context, centrally defined |
| Composer shape | 28 px radius; 358 px wide at 390 px viewport | 24 dp default radius; consistent component shape |
| Composer surface | `#1B1B1B`, no border in measured ancestor | Existing Sparkbox raised surface, not a copied neutral-black theme |
| Page / text | `#000000` / `#EDEDED` in this dark session | Existing Sparkbox slate / ink roles in dark and light |
| Small composer buttons | 36 × 36 CSS px boxes | At least 48 × 48 dp touch areas, 20–22 dp glyphs |

CSS px and native sp/dp are different units. The right column is a proposed design translation, not a claim of pixel-identical rendering. Device font scaling, font metrics, density and theme all affect the result.

## Sparkbox rendering gaps found in code

- `SparkTheme.kt:37` defines compact global text: bodyLarge 14/20 sp, bodyMedium 13/18 sp. Scout needs a scoped reading scale, not an unreviewed app-wide typography change.
- `AskView.kt:154` independently constructs a TextView at 15 sp with a 1.35 line-spacing multiplier. It bypasses the Compose text scale, so streamed/final/composer text can feel inconsistent.
- `Md.java:29` renders the whole answer into one Spannable. Its table handling deliberately converts columns into stacked rows; fenced code becomes monochrome text lines; Mermaid shows an open-in-Obsidian notice. It cannot produce independent table/code controls inside that TextView.
- `Ui.java` applies an automatic size-compression helper for its text factory. New Scout text must not accidentally pass through that helper after choosing explicit reading sizes.

The smallest viable improvement is a Scout-specific block presentation for paragraphs/headings, tables and fenced code, reusing existing inline formatting and link policy. Do not rewrite the whole note reader, add a WebView, or build a general Markdown framework for this design exercise.

## Accessibility and evidence limits

Confirmed: narrow viewport dimensions; sampled computed styles; bounded page width for the generated table; named controls in the accessibility tree; expanded editor; source popover/list; attachment readiness; Stop while typing; edit Cancel path.

Not verified: physical phone keyboard/IME and browser chrome, safe-area changes, touch gestures, Voice recording/playback, camera permissions, screen-reader announcements/focus restoration, complete keyboard navigation, light theme, reduced motion, offline/recovery behavior, all table widths, or all ChatGPT features. Do not claim WCAG compliance from this sample. The native Scout acceptance matrix must cover these gaps.

The largest quality gains are consistent typography, open reading space, predictable local actions and useful evidence previews. Adding more buttons, decorative gradients or another typeface does not substitute for those fundamentals.
