# Sparkbox: feedback on design round 3

> Historical: design review notes from round 3. The shipped screens are the Compose versions in `src/*View.kt`.

For the product designer. Screens reviewed: Feed, Ask, Spark (question), Spark (revealed), plus DESIGN.md.

## Overall

Good progress. Most of the round 2 visual problems are fixed in these four screens. But only 4 of about 15 screens came back, so we cannot build the app from this package. Round 4 needs the missing screens (section 4) and a cleaned token file (section 2, item 1).

## 1. Fixed, thank you

- "Vault checked 8m ago" replaces "Synced to Mac". Profile avatar removed.
- No monospace labels, no glow circles, no pulsing dot. No text under 12px.
- No "overdue" wording. Spark card count and progress now agree.
- Spark answer: nested screenshot gone, source quote is collapsed.
- Feed: capture strip opens the Capture Map. A not-processed ("Ingested") card exists, which matches our real vault.
- Ask: single-source warning is a good addition.

## 2. Must fix

1. **One token table.** DESIGN.md still contradicts itself: front matter says primary `#4edea3`, surface-container `#1b2027`, on-surface `#dee2ed`; the prose says `#10b981`, `#171c23`, `#f1f5f9`. Screens mix both. Deliver one final table, dark and light.
2. **Backdrop blur.** Every screen still has `backdrop-blur` on the header and nav, which the doc bans. Use solid surfaces.
3. **Jargon still present.** Replace with plain words:
   - Feed: "Focus on deliberate assimilation" (drop it).
   - Ask: "Active Knowledge Retrieval", "Vault Synthesis", "Verified Vault Claim" (nothing is verified; say "Claim").
   - Spark revealed: "Schedule next encounter" (use "How well did you recall it?"), "Your Anchor Note" (use "Your note"), "Core Synthesis" (use "Answer").
4. **Ingested chip.** Nearly invisible against the card. It must meet 4.5:1.
5. **Capture strip is cut off** ("...1 person, 3 en…"). Let it wrap to two lines or shorten.
6. **Spark action names differ between sides.** Question: "Sleep (24h)", "Open Source", "Edit". Answer: "Park 24h", "Flag bad", "View source", "Edit". Use one set: Park 24h, Flag, Source, Edit.
7. **Rating buttons.** "Good 4d" is pre-highlighted while the text says "Tap rating to advance". Do not pre-select. Also say how "Again <10m" works: our scheduler counts days, so it would re-show in the same session.
8. **Pre-calibration row (Guess / Unsure / Sure).** New feature, adds a tap to every card, not in our plan. Remove it. If you think it is valuable, argue for it separately.
9. **Features that do not exist.** Remove or justify each: mic button in Ask input, unlabeled filter icon in the input, tune and refresh icons in the Ask header, "Make Flashcard" chip, "Keep as Claim" chip.
10. **Citations.** Ask cites a note by title and path only. Remove "Matched 94%" and "Section: Evaluator Reliability". Show title, lane/status chip, and tap to open.
11. **Ask states missing.** Add: offline, "Not in your knowledge base", error, streaming text, and action chips that fit (currently cut off).
12. **Frame size.** Exports are 384 to 780px wide. Deliver 390x844.

## 3. Should fix

- Feed still has two rows under the header (Latest/Inspire/Try plus filter chips). Keep it to one control if you can.
- Spark question: source chip truncates ("Deterministic Verificati…"). Let it wrap.
- Ask header: "Sparkbox" plus the green dot and "Vault checked" on the Ask screen duplicates the page title. Title it "Ask".
- Ask answer: numbered list is good; keep answers short.

## 4. Still not delivered (round 4)

Round 2 revisions, still open (see `designer-feedback-round2.md`):
- Hub, Unit, Capture Map, Claims, Apply, Reflect (each had open items: wrong headers, missing tab bar, button overlap, truncated names, too many sections).

Never delivered:
- Search (word and meaning-based).
- Browse for topics, people, entities (about 35 topics, 108 people; needs search and sort).
- Week, Pursuits setup, Settings, Inspire.
- States: empty feed, offline, captured only, agent not finished, possible duplicate, capture failed with Retry / Needs attention.
- Spark: empty (no cards yet, with "write your own card"), finished, cap reached, nothing due.
- Light theme.

## 5. What we can build now vs later

For your prioritising:
- **Now:** Feed shell with chips, topic cards, Ask layout, Hub and Unit (from existing parsed notes and links).
- **After engine work:** Spark (needs a scheduler and an event log).
- **After agent changes:** curiosity prompt, topic/person counts on cards, Claims.

So round 4 priority: token file, Hub, Unit, Capture Map, Browse, Search, Ask states, Feed states. Spark, Claims, Apply, Reflect, Week after that.

## 6. Handoff format

Native Android (Java, code-built views). Deliver: one token table (dark and light), component list, per-screen specs with all states, 390x844 frames. If you keep Plus Jakarta Sans, say so explicitly: the app currently uses the system font and would need it bundled.
