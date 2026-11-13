# Sparkbox: final design round

> Historical: final design round notes. Palette and type decisions live on in `SparkTheme.kt`; type sizes were later reduced.

Direction is fixed: your palette, system font. Deliver one clean folder. When every item below is done, we build.

## Approved. Do not change.

feed, universal_hub_page, unit_detail, browse_search_vault, ask_answer_with_citations, spark_review_revealed, feed_failed_captures_offline, capture_map_2.

## Do these 7 things

1. **Delete 4 old screens** from the package: `ask_states_not_in_vault`, `capture_map_1`, `feed_capture_failed_quiet_state`, `spark_all_caught_up`.

2. **Redo 3 screen groups in the approved style** (same font, no avatar, plain words, no streaks, no scores):
   - **Ask states:** offline, "Not in your knowledge base" (actions: Search, Add note), streaming with Cancel, error with Retry.
   - **Spark states:** question side before "Show answer" (no Guess/Unsure/Sure), caught up (keep "Write your own card"), first run with no cards, daily cap reached.
   - **Unit, not processed:** one frame for a note the agent hasn't processed (no curiosity question, key notes, recall cards or entities).

3. **Nav on every screen:** Feed / Spark / Search / Ask. Feed and Hub still say "Claims".

4. **One DESIGN.md** (you have three). Use only these tokens:

   | Role | Dark | Light |
   |---|---|---|
   | Canvas | `#0f141b` | `#f8fafc` |
   | Card | `#171c23` | `#ffffff` |
   | Raised | `#1e242d` | `#f1f5f9` |
   | Border | `#28323f` | `#cbd5e1` |
   | Primary | `#10b981` | `#059669` |
   | Primary container | `#064e3b` | `#d1fae5` |
   | Text | `#f1f5f9` | `#0f172a` |
   | Text secondary | `#94a3b8` | `#475569` |
   | Warning / Error | `#d97706` / `#e11d48` | same |

5. **Light theme:** Feed, Unit, Hub, Ask, Spark review.

6. **Settings screen** (new): vault folder, "Check vault folder now", Ask model, API key, Spark notifications, theme. Plain list.

7. **Frame size:** 390x844 at 2x (780x1688) for every PNG.

## Small fixes

- Spark "Again <10m": change to "Again: later today".
- Ask: numbered markers in the answer matching the citation rows; wrap long citation titles to two lines.
- Capture Map: title it "What was added".
- Search placeholder must fit: "Search notes, topics, people".
- Feed: show Inspire, or say it is cut.
- Replace real names and domains in samples with placeholders.

## Do not design (not in v1)

Claims, Apply, Reflect, Week, Pursuits, graph view, streaks, retention scores, accounts, profile.
